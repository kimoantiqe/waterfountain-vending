package com.waterfountainmachine.app.admin.fragments

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.waterfountainmachine.app.activities.MainActivity
import com.waterfountainmachine.app.di.AuthModule
import com.waterfountainmachine.app.di.BackendModule
import com.waterfountainmachine.app.databinding.FragmentSystemBinding
import com.waterfountainmachine.app.utils.AdminDebugConfig
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.utils.LogCollector
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class SystemFragment : Fragment() {
    
    private var _binding: FragmentSystemBinding? = null
    private val binding get() = _binding!!
    
    companion object {
        private const val TAG = "SystemFragment"
        private var bootTime: Long = 0L
    }
    
    init {
        if (bootTime == 0L) {
            bootTime = System.currentTimeMillis()
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSystemBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupSystemControls()
        setupKioskSettings()
        loadCurrentSettings()
    }
    
    private fun setupSystemControls() {
        // System restart
        binding.restartSystemButton.setOnClickListener {
            restartSystem()
        }
        
        // Exit admin panel
        binding.exitAdminButton.setOnClickListener {
            exitAdminPanel()
        }
        
        binding.returnToMainButton.setOnClickListener {
            returnToMainScreen()
        }
        
        // Crash test button (for testing Crashlytics)
        binding.simulateCrashButton.setOnClickListener {
            simulateCrashForTesting()
        }
        
        // Open Android Settings button
        binding.openAndroidSettingsButton.setOnClickListener {
            openAndroidSettings()
        }
        
        // Change Admin PIN button
        binding.changeAdminPinButton.setOnClickListener {
            showChangePinDialog()
        }
        
        // Send Logs Now button
        binding.sendLogsNowButton.setOnClickListener {
            sendLogsNow()
        }
    }
    
    private fun setupKioskSettings() {
        // Note: Listeners are set up AFTER loading settings to prevent triggering on initial load
        // This prevents the restart dialog from appearing every time the screen is opened
    }
    
    private fun loadCurrentSettings() {
        val prefs = com.waterfountainmachine.app.utils.SecurePreferences.getSystemSettings(requireContext())
        
        // Load toggle states WITHOUT triggering listeners
        binding.kioskModeToggle.isChecked = prefs.getBoolean("kiosk_mode", true)
        
        // Load API mode (default to mock mode for safety)
        val useMockMode = AuthModule.loadApiModePreference(requireContext())
        binding.apiModeToggle.isChecked = !useMockMode // Toggle shows "Live SMS Authentication"
        
        // Load Backend Slot Service mode (default to real backend)
        val useMockSlotService = BackendModule.loadSlotServiceModePreference(requireContext())
        binding.slotServiceModeToggle.isChecked = !useMockSlotService // Toggle shows "Sync Slots with Backend"
        
        // Load Health Monitor mode (default to real health monitoring)
        val useMockHealthMonitor = com.waterfountainmachine.app.di.HealthMonitorModule.loadHealthMonitorModePreference(requireContext())
        binding.healthMonitorModeToggle.isChecked = !useMockHealthMonitor // Toggle shows "Send Health Heartbeats"
        
        // Load Remote Logging mode (check if remote logging is enabled)
        val remoteLoggingEnabled = com.yishengkj.logging.RemoteLoggingManager.getInstance(requireContext()).isEnabled()
        binding.remoteLoggingToggle.isChecked = remoteLoggingEnabled
        binding.sendLogsNowButton.isEnabled = remoteLoggingEnabled
        
        binding.adminLoggingToggle.isChecked = com.waterfountainmachine.app.utils.AdminDebugConfig.isAdminLoggingEnabled(requireContext())
        binding.analyticsDebugToggle.isChecked = prefs.getBoolean("analytics_debug_mode", false)
        binding.maintenanceModeToggle.isChecked = prefs.getBoolean("maintenance_mode", false)
        binding.hardwareModeToggle.isChecked = prefs.getBoolean("use_real_serial", false)
        binding.hideNavBarToggle.isChecked = prefs.getBoolean("hide_navigation_bar", true)
        
        // NOW set up listeners AFTER loading initial states
        // This prevents the restart dialog from appearing when entering the screen
        setupToggleListeners()
        
        // Load system info
        updateSystemInfo()
    }
    
    private fun setupToggleListeners() {
        // Kiosk mode toggle
        binding.kioskModeToggle.setOnCheckedChangeListener { _, isChecked ->
            updateKioskMode(isChecked)
        }
        
        // API mode toggle (for SMS authentication)
        binding.apiModeToggle.setOnCheckedChangeListener { _, isChecked ->
            updateApiMode(isChecked)
        }
        
        // Backend Slot Service mode toggle
        binding.slotServiceModeToggle.setOnCheckedChangeListener { _, isChecked ->
            updateSlotServiceMode(isChecked)
        }
        

        // Admin logging toggle
        binding.adminLoggingToggle.setOnCheckedChangeListener { _, isChecked ->
            updateAdminLogging(isChecked)
        }
        
        // Analytics debug mode toggle
        binding.analyticsDebugToggle.setOnCheckedChangeListener { _, isChecked ->
            updateAnalyticsDebugMode(isChecked)
        }
        
        // Remote logging toggle
        binding.remoteLoggingToggle.setOnCheckedChangeListener { _, isChecked ->
            updateRemoteLogging(isChecked)
        }
        
        // Maintenance mode toggle
        binding.maintenanceModeToggle.setOnCheckedChangeListener { _, isChecked ->
            toggleMaintenanceMode(isChecked)
        }
        
        // Hardware mode toggle
        binding.hardwareModeToggle.setOnCheckedChangeListener { _, isChecked ->
            updateHardwareMode(isChecked)
        }
        
        // Hide navigation bar toggle
        binding.hideNavBarToggle.setOnCheckedChangeListener { _, isChecked ->
            updateHideNavigationBar(isChecked)
        }
    }
    
    private fun updateKioskMode(enabled: Boolean) {
        lifecycleScope.launch {
            try {
                com.waterfountainmachine.app.utils.SecurePreferences.getSystemSettings(requireContext())
                    .edit()
                    .putBoolean("kiosk_mode", enabled)
                    .apply()
                
                binding.systemStatusText.text = "Kiosk mode ${if (enabled) "enabled" else "disabled"}"
                AdminDebugConfig.logAdminInfo(requireContext(), TAG, "Kiosk mode ${if (enabled) "enabled" else "disabled"}")
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Error updating kiosk mode", e)
                binding.systemStatusText.text = "Error updating kiosk mode: ${e.message}"
            }
        }
    }
    
    private fun updateApiMode(useRealApi: Boolean) {
        lifecycleScope.launch {
            try {
                AdminDebugConfig.logAdminInfo(requireContext(), TAG, "updateApiMode() called with useRealApi=$useRealApi")
                
                // useRealApi = true means Real API mode
                // useMockMode = false means Real API mode
                val useMockMode = !useRealApi
                
                AdminDebugConfig.logAdminInfo(requireContext(), TAG, "Saving preference: useMockMode=$useMockMode")
                
                // Save preference - will take effect on next app restart
                AuthModule.initialize(requireContext(), useMockMode)
                
                val modeName = if (useRealApi) "Real API" else "Mock Mode"
                binding.systemStatusText.text = "SMS Authentication: $modeName (restart required)"
                AdminDebugConfig.logAdminInfo(requireContext(), TAG, "API mode preference saved: $modeName")
                
                // Show restart required dialog
                showRestartRequiredDialog(modeName, isSlotService = false)
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Error updating API mode", e)
                binding.systemStatusText.text = "Error updating API mode: ${e.message}"
                
                // Revert toggle on error
                binding.apiModeToggle.isChecked = !useRealApi
            }
        }
    }
    
    private fun updateSlotServiceMode(useRealBackend: Boolean) {
        lifecycleScope.launch {
            try {
                AdminDebugConfig.logAdminInfo(requireContext(), TAG, "updateSlotServiceMode() called with useRealBackend=$useRealBackend")
                
                // useRealBackend = true means Real Backend mode
                // useMockMode = false means Real Backend mode
                val useMockMode = !useRealBackend
                
                AdminDebugConfig.logAdminInfo(requireContext(), TAG, "Saving preference: useMockMode=$useMockMode")
                
                // Save preference - will take effect on next app restart
                BackendModule.initialize(requireContext(), useMockMode)
                
                val modeName = if (useRealBackend) "Real Backend" else "Mock Mode"
                binding.systemStatusText.text = "Slot Service: $modeName (restart required)"
                AdminDebugConfig.logAdminInfo(requireContext(), TAG, "Slot service mode preference saved: $modeName")
                
                // Show restart required dialog
                showRestartRequiredDialog(modeName, isSlotService = true)
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Error updating slot service mode", e)
                binding.systemStatusText.text = "Error updating slot service mode: ${e.message}"
                
                // Revert toggle on error
                binding.slotServiceModeToggle.isChecked = !useRealBackend
            }
        }
    }
    
    private fun showRestartRequiredDialog(modeName: String, isSlotService: Boolean = false) {
        val message = buildString {
            if (isSlotService) {
                append("Slot Service mode changed to: $modeName\n\n")
                if (modeName == "Real Backend") {
                    append("⚠️ Real Backend Mode:\n")
                    append("• Syncs inventory with Firebase backend\n")
                    append("• Records vend events to database\n")
                    append("• Updates slot status after failures\n")
                    append("• Requires valid machine certificate\n\n")
                } else {
                    append("📱 Mock Mode:\n")
                    append("• Uses local inventory only\n")
                    append("• Vends work without backend\n")
                    append("• No data recorded to database\n")
                    append("• Great for testing\n\n")
                }
            } else {
                append("API mode changed to: $modeName\n\n")
                if (modeName == "Real API") {
                    append("⚠️ WARNING: Real API will send actual SMS messages via Twilio and incur costs.\n\n")
                    append("Make sure the machine is properly enrolled with a valid certificate.\n\n")
                    append("Mock code (123456) will no longer work.\n\n")
                }
            }
            append("The app must restart for this change to take effect.")
        }
        
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Restart Required")
            .setMessage(message)
            .setPositiveButton("Restart Now") { _, _ ->
                executeRestart()
            }
            .setNegativeButton("Restart Later", null)
            .setCancelable(false)
            .show()
    }
    
    private fun updateHideNavigationBar(enabled: Boolean) {
        lifecycleScope.launch {
            try {
                com.waterfountainmachine.app.utils.SecurePreferences.getSystemSettings(requireContext())
                    .edit()
                    .putBoolean("hide_navigation_bar", enabled)
                    .apply()
                
                // Apply immediately to current activity
                if (enabled) {
                    com.waterfountainmachine.app.utils.ImmersiveModeHelper.enableImmersiveMode(requireActivity())
                } else {
                    com.waterfountainmachine.app.utils.ImmersiveModeHelper.disableImmersiveMode(requireActivity())
                }
                
                binding.systemStatusText.text = "Navigation bar ${if (enabled) "hidden" else "shown"}"
                AdminDebugConfig.logAdminInfo(requireContext(), TAG, "Hide navigation bar ${if (enabled) "enabled" else "disabled"}")
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Error updating hide navigation bar", e)
                binding.systemStatusText.text = "Error updating hide navigation bar: ${e.message}"
            }
        }
    }
    
    private fun updateRemoteLogging(enabled: Boolean) {
        lifecycleScope.launch {
            try {
                val loggingManager = com.yishengkj.logging.RemoteLoggingManager.getInstance(requireContext())
                
                if (enabled) {
                    loggingManager.enable()
                    binding.systemStatusText.text = "Remote logging enabled - logs will upload every 2 hours"
                    AdminDebugConfig.logAdminInfo(requireContext(), TAG, "Remote logging enabled")
                    binding.sendLogsNowButton.isEnabled = true
                } else {
                    loggingManager.disable()
                    binding.systemStatusText.text = "Remote logging disabled - logs stored locally only"
                    AdminDebugConfig.logAdminInfo(requireContext(), TAG, "Remote logging disabled")
                    binding.sendLogsNowButton.isEnabled = false
                }
                
                // Save preference for next app startup
                com.waterfountainmachine.app.utils.SecurePreferences.getSystemSettings(requireContext())
                    .edit()
                    .putBoolean("remote_logging_enabled", enabled)
                    .apply()
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Error updating remote logging", e)
                binding.systemStatusText.text = "Error updating remote logging: ${e.message}"
                
                // Revert toggle on error
                binding.remoteLoggingToggle.isChecked = !enabled
            }
        }
    }
    
    private fun updateAdminLogging(enabled: Boolean) {
        lifecycleScope.launch {
            try {
                if (enabled) {
                    com.waterfountainmachine.app.utils.AdminDebugConfig.enableAdminLogging(requireContext())
                    val remainingMs = com.waterfountainmachine.app.utils.AdminDebugConfig.getRemainingLoggingTime(requireContext())
                    val remainingMin = remainingMs / 60000
                    binding.systemStatusText.text = "Admin logging enabled (auto-disables in ${remainingMin}min)"
                    // Note: Using regular AppLog here since this is about enabling/disabling admin logging itself
                    AppLog.i(TAG, "✅ Admin logging enabled for temporary debugging")
                } else {
                    com.waterfountainmachine.app.utils.AdminDebugConfig.disableAdminLogging(requireContext())
                    binding.systemStatusText.text = "Admin logging disabled"
                    AppLog.i(TAG, "❌ Admin logging disabled")
                }
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Error updating admin logging", e)
                binding.systemStatusText.text = "Error updating admin logging: ${e.message}"
            }
        }
    }
    
    private fun updateAnalyticsDebugMode(enabled: Boolean) {
        lifecycleScope.launch {
            try {
                com.waterfountainmachine.app.utils.SecurePreferences.getSystemSettings(requireContext())
                    .edit()
                    .putBoolean("analytics_debug_mode", enabled)
                    .apply()
                
                // Initialize AnalyticsManager and set debug mode
                val analyticsManager = com.waterfountainmachine.app.analytics.AnalyticsManager.getInstance(requireContext())
                analyticsManager.setDebugMode(enabled)
                
                val statusMessage = if (enabled) {
                    "Analytics Debug Mode enabled.\n\nTo see events in real-time:\n1. Run: adb shell setprop debug.firebase.analytics.app com.waterfountainmachine.app\n2. Open Firebase Console → DebugView"
                } else {
                    "Analytics Debug Mode disabled.\n\nTo disable ADB debug:\nadb shell setprop debug.firebase.analytics.app .none."
                }
                
                binding.systemStatusText.text = statusMessage
                AdminDebugConfig.logAdminInfo(requireContext(), TAG, "Analytics debug mode ${if (enabled) "enabled" else "disabled"}")
                
                // Show informational dialog
                showAnalyticsDebugDialog(enabled)
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Error updating analytics debug mode", e)
                binding.systemStatusText.text = "Error updating analytics debug mode: ${e.message}"
            }
        }
    }
    
    private fun showAnalyticsDebugDialog(enabled: Boolean) {
        val message = if (enabled) {
            """
            Analytics Debug Mode is now ENABLED.
            
            📊 To see events in real-time:
            
            1. Connect device via USB
            2. Run this ADB command:
               adb shell setprop debug.firebase.analytics.app com.waterfountainmachine.app
            
            3. Open Firebase Console → Analytics → DebugView
            
            Events will now appear immediately (instead of 24hr delay).
            
            ⚠️ Remember to disable when done debugging to avoid polluting analytics data.
            """.trimIndent()
        } else {
            """
            Analytics Debug Mode is now DISABLED.
            
            To disable ADB debug mode, run:
            adb shell setprop debug.firebase.analytics.app .none.
            
            Events will now be batched and appear in Firebase Console after ~24 hours.
            """.trimIndent()
        }
        
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(if (enabled) "Debug Mode Enabled" else "Debug Mode Disabled")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
    
    private fun updateHardwareMode(enabled: Boolean) {
        lifecycleScope.launch {
            try {
                com.waterfountainmachine.app.utils.SecurePreferences.getSystemSettings(requireContext())
                    .edit()
                    .putBoolean("use_real_serial", enabled)
                    .apply()
                
                val modeName = if (enabled) "Real Hardware" else "Mock Hardware"
                binding.systemStatusText.text = "Serial Communicator: $modeName"
                AdminDebugConfig.logAdminInfo(requireContext(), TAG, "Serial communicator mode changed to: $modeName")
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Error updating hardware mode", e)
                binding.systemStatusText.text = "Error updating hardware mode: ${e.message}"
            }
        }
    }
    
    private fun toggleMaintenanceMode(enabled: Boolean) {
        lifecycleScope.launch {
            try {
                val success = com.waterfountainmachine.app.utils.SecurePreferences.getSystemSettings(requireContext())
                    .edit()
                    .putBoolean("maintenance_mode", enabled)
                    .putLong("maintenance_mode_timestamp", System.currentTimeMillis())
                    .commit()  // Use commit() for immediate synchronous save
                
                if (success) {
                    binding.systemStatusText.text = "Maintenance mode ${if (enabled) "enabled" else "disabled"} (restart recommended)"
                    AdminDebugConfig.logAdminWarning(requireContext(), TAG, "Maintenance mode ${if (enabled) "enabled" else "disabled"}")
                    AppLog.i(TAG, "✅ Maintenance mode saved: $enabled")
                    
                    // Show restart suggestion dialog
                    showMaintenanceModeRestartDialog(enabled)
                } else {
                    binding.systemStatusText.text = "Failed to save maintenance mode"
                    AppLog.e(TAG, "Failed to save maintenance mode preference")
                }
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Error updating maintenance mode", e)
                binding.systemStatusText.text = "Error updating maintenance mode: ${e.message}"
            }
        }
    }
    
    private fun showMaintenanceModeRestartDialog(enabled: Boolean) {
        val message = if (enabled) {
            """
            ⚠️ MAINTENANCE MODE ENABLED
            
            The vending machine is now in maintenance mode.
            
            • Machine will show error screen
            • No vending operations allowed
            • Admin panel still accessible (triple-tap/enter)
            • Remote logging continues
            
            Restart now to apply the maintenance screen immediately.
            """.trimIndent()
        } else {
            """
            ✅ MAINTENANCE MODE DISABLED
            
            The vending machine is now back to normal operation.
            
            • Machine will show normal tap-to-start screen
            • Vending operations allowed
            • All features active
            
            Restart now to return to normal operation immediately.
            """.trimIndent()
        }
        
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Restart Recommended")
            .setMessage(message)
            .setPositiveButton("Restart Now") { _, _ ->
                executeRestart()
            }
            .setNegativeButton("Restart Later", null)
            .setCancelable(true)
            .show()
    }
    
    private fun restartSystem() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Restart System")
            .setMessage("This will restart the application. Continue?")
            .setPositiveButton("Restart") { _, _ ->
                executeRestart()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun executeRestart() {
        binding.systemStatusText.text = "Restarting application..."
        AdminDebugConfig.logAdminWarning(requireContext(), TAG, "System restart initiated - killing process for clean restart")
        
        lifecycleScope.launch {
            try {
                // Get the launch intent for the app
                val intent = requireContext().packageManager
                    .getLaunchIntentForPackage(requireContext().packageName)
                
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    
                    AdminDebugConfig.logAdminInfo(requireContext(), TAG, "Starting new app instance...")
                    startActivity(intent)
                    
                    // IMPORTANT: Kill the entire process to force Hilt to recreate all Singletons
                    // This ensures the new repository is injected based on the updated preference
                    AdminDebugConfig.logAdminInfo(requireContext(), TAG, "Killing process to complete restart")
                    android.os.Process.killProcess(android.os.Process.myPid())
                    System.exit(0)
                } else {
                    AppLog.e(TAG, "Could not get launch intent")
                    binding.systemStatusText.text = "Error: Could not restart"
                }
                
            } catch (e: Exception) {
                AppLog.e(TAG, "System restart failed", e)
                binding.systemStatusText.text = "Restart failed: ${e.message}"
            }
        }
    }
    
    private fun exitAdminPanel() {
        AdminDebugConfig.logAdminInfo(requireContext(), TAG, "Admin panel exit requested")
        requireActivity().finish()
    }
    
    private fun returnToMainScreen() {
        AdminDebugConfig.logAdminInfo(requireContext(), TAG, "Returning to main screen from admin panel")
        val intent = Intent(requireContext(), MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
        requireActivity().finish()
    }
    
    private fun updateSystemInfo() {
        try {
            // App version info
            val packageInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            val versionName = packageInfo.versionName ?: "Unknown"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            binding.systemVersionText.text = "Version: $versionName ($versionCode)"
            
            // System uptime (app runtime)
            val uptimeMillis = System.currentTimeMillis() - bootTime
            val hours = uptimeMillis / 3600000
            val minutes = (uptimeMillis % 3600000) / 60000
            binding.systemUptimeText.text = "Uptime: ${hours}h ${minutes}m"
            
            // Memory info
            val activityManager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            
            val usedMemoryMB = (memoryInfo.totalMem - memoryInfo.availMem) / (1024 * 1024)
            val totalMemoryMB = memoryInfo.totalMem / (1024 * 1024)
            val memoryPercent = ((usedMemoryMB.toFloat() / totalMemoryMB.toFloat()) * 100).toInt()
            
            binding.systemMemoryText.text = "Memory: ${usedMemoryMB}MB / ${totalMemoryMB}MB ($memoryPercent%)"
            
            // Storage info
            val stat = StatFs(Environment.getDataDirectory().path)
            val bytesAvailable = stat.availableBytes
            val bytesTotal = stat.totalBytes
            val storageUsedGB = (bytesTotal - bytesAvailable) / (1024 * 1024 * 1024)
            val storageTotalGB = bytesTotal / (1024 * 1024 * 1024)
            
            binding.systemStatusText.text = "Storage: ${storageUsedGB}GB / ${storageTotalGB}GB | Android ${Build.VERSION.RELEASE} | ${Build.MODEL}"
            
            AdminDebugConfig.logAdmin(requireContext(), TAG, "System info updated: Memory=$memoryPercent%, Storage=${storageUsedGB}GB/${storageTotalGB}GB")
            
        } catch (e: Exception) {
            AppLog.e(TAG, "Error updating system info", e)
            binding.systemStatusText.text = "Error loading system info: ${e.message}"
        }
    }
    
    /**
     * Simulate a crash for testing Firebase Crashlytics
     * This intentionally throws an exception to test crash reporting
     */
    private fun simulateCrashForTesting() {
        AdminDebugConfig.logAdminWarning(requireContext(), TAG, "Simulating crash for Crashlytics testing...")
        
        // Show confirmation dialog first
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Test Crash")
            .setMessage("This will intentionally crash the app to test Firebase Crashlytics.\n\nThe crash will be logged and sent to Firebase Console.\n\nContinue?")
            .setPositiveButton("Crash Now") { _, _ ->
                // Keep error log for crash testing - always log
                AppLog.e(TAG, "INTENTIONAL CRASH FOR TESTING - Throwing RuntimeException")
                
                // Log custom key-value pairs before crash
                com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().apply {
                    setCustomKey("crash_test", true)
                    setCustomKey("triggered_by", "admin_panel")
                    setCustomKey("timestamp", System.currentTimeMillis())
                    log("Admin triggered test crash")
                }
                
                // Force crash
                throw RuntimeException("TEST CRASH: Intentional crash triggered from Admin Panel for Crashlytics testing")
            }
            .setNegativeButton("Cancel", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }
    
    /**
     * Show dialog to change admin PIN
     */
    private fun showChangePinDialog() {
        val dialogView = layoutInflater.inflate(
            com.waterfountainmachine.app.R.layout.dialog_change_pin,
            null
        )
        
        val currentPinInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(
            com.waterfountainmachine.app.R.id.currentPinInput
        )
        val newPinInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(
            com.waterfountainmachine.app.R.id.newPinInput
        )
        val confirmPinInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(
            com.waterfountainmachine.app.R.id.confirmPinInput
        )
        val currentPinLayout = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(
            com.waterfountainmachine.app.R.id.currentPinLayout
        )
        val newPinLayout = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(
            com.waterfountainmachine.app.R.id.newPinLayout
        )
        val confirmPinLayout = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(
            com.waterfountainmachine.app.R.id.confirmPinLayout
        )
        
        // Apply InputFilter to limit to 8 characters (maxLength doesn't work with numberPassword)
        val maxLengthFilter = android.text.InputFilter.LengthFilter(8)
        currentPinInput.filters = arrayOf(maxLengthFilter)
        newPinInput.filters = arrayOf(maxLengthFilter)
        confirmPinInput.filters = arrayOf(maxLengthFilter)
        
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()
        
        // Cancel button
        dialogView.findViewById<android.widget.Button>(
            com.waterfountainmachine.app.R.id.cancelButton
        ).setOnClickListener {
            dialog.dismiss()
        }
        
        // Change button
        dialogView.findViewById<android.widget.Button>(
            com.waterfountainmachine.app.R.id.changeButton
        ).setOnClickListener {
            // Clear previous errors
            currentPinLayout.error = null
            newPinLayout.error = null
            confirmPinLayout.error = null
            
            val currentPin = currentPinInput.text?.toString()?.trim() ?: ""
            val newPin = newPinInput.text?.toString()?.trim() ?: ""
            val confirmPin = confirmPinInput.text?.toString()?.trim() ?: ""
            
            AdminDebugConfig.logAdmin(requireContext(), TAG, "PIN change attempt - Current PIN length: ${currentPin.length}, New PIN length: ${newPin.length}")
            
            // Validate current PIN length
            if (currentPin.isEmpty()) {
                currentPinLayout.error = "Please enter your current PIN"
                return@setOnClickListener
            }
            
            if (currentPin.length != 8) {
                currentPinLayout.error = "PIN must be exactly 8 digits (current: ${currentPin.length})"
                return@setOnClickListener
            }
            
            if (!currentPin.all { it.isDigit() }) {
                currentPinLayout.error = "PIN must contain only numbers"
                return@setOnClickListener
            }
            
            // Verify current PIN
            if (!com.waterfountainmachine.app.admin.AdminPinManager.verifyPin(requireContext(), currentPin)) {
                currentPinLayout.error = "Incorrect current PIN"
                // Keep security log - always log failed attempts
                AppLog.w(TAG, "Failed PIN change attempt - incorrect current PIN")
                return@setOnClickListener
            }
            
            // Validate new PIN
            if (newPin.isEmpty()) {
                newPinLayout.error = "Please enter a new PIN"
                return@setOnClickListener
            }
            
            if (newPin.length != 8) {
                newPinLayout.error = "New PIN must be exactly 8 digits (current: ${newPin.length})"
                return@setOnClickListener
            }
            
            if (!newPin.all { it.isDigit() }) {
                newPinLayout.error = "PIN must contain only numbers"
                return@setOnClickListener
            }
            
            // Check if new PIN is same as current
            if (newPin == currentPin) {
                newPinLayout.error = "New PIN must be different from current PIN"
                return@setOnClickListener
            }
            
            // Validate confirmation
            if (confirmPin.isEmpty()) {
                confirmPinLayout.error = "Please confirm your new PIN"
                return@setOnClickListener
            }
            
            if (confirmPin != newPin) {
                confirmPinLayout.error = "PINs do not match"
                return@setOnClickListener
            }
            
            // Change the PIN
            if (com.waterfountainmachine.app.admin.AdminPinManager.changePin(requireContext(), currentPin, newPin)) {
                // Keep security log - always log PIN changes
                AppLog.i(TAG, "✅ Admin PIN changed successfully")
                
                // Show success message
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Success")
                    .setMessage("Admin PIN has been changed successfully.\n\nPlease remember your new PIN.")
                    .setPositiveButton("OK", null)
                    .show()
                
                dialog.dismiss()
            } else {
                currentPinLayout.error = "Failed to change PIN. Please try again."
                AppLog.e(TAG, "Failed to change PIN")
            }
        }
        
        dialog.show()
    }
    
    private fun openAndroidSettings() {
        try {
            AppLog.i(TAG, "Opening Android Settings")
            AdminDebugConfig.logAdminInfo(requireContext(), TAG, "Opening Android Settings")
            
            val intent = Intent(android.provider.Settings.ACTION_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            
            startActivity(intent)
            
            binding.systemStatusText.text = "Opened Android Settings"
            
        } catch (e: Exception) {
            AppLog.e(TAG, "Error opening Android Settings", e)
            binding.systemStatusText.text = "Error opening Android Settings: ${e.message}"
            
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Error")
                .setMessage("Failed to open Android Settings: ${e.message}")
                .setPositiveButton("OK", null)
                .show()
        }
    }
    
    private fun sendLogsNow() {
        lifecycleScope.launch {
            try {
                // Check if machine is enrolled first
                if (!com.waterfountainmachine.app.security.SecurityModule.isEnrolled()) {
                    androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle("Cannot Send Logs")
                        .setMessage("Machine must be enrolled to upload logs. Certificate authentication is required.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@launch
                }
                
                binding.sendLogsNowButton.isEnabled = false
                binding.systemStatusText.text = "Sending logs..."
                AdminDebugConfig.logAdminInfo(requireContext(), TAG, "Manual log upload triggered")
                
                val loggingManager = com.yishengkj.logging.RemoteLoggingManager.getInstance(requireContext())
                loggingManager.triggerImmediateUpload()
                
                binding.systemStatusText.text = "✅ Logs queued for upload. Check WorkManager status for progress."
                
                // Re-enable button after 5 seconds
                kotlinx.coroutines.delay(5000)
                binding.sendLogsNowButton.isEnabled = true
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Error triggering log upload", e)
                binding.systemStatusText.text = "❌ Error sending logs: ${e.message}"
                binding.sendLogsNowButton.isEnabled = true
                
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Upload Error")
                    .setMessage("Failed to trigger log upload:\n${e.message}")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
