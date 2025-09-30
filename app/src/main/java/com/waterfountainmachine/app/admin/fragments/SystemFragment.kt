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
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.waterfountainmachine.app.MainActivity
import com.waterfountainmachine.app.databinding.FragmentSystemBinding
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
        setupMaintenanceControls()
        loadCurrentSettings()
    }
    
    private fun setupSystemControls() {
        // System restart
        binding.restartSystemButton.setOnClickListener {
            restartSystem()
        }
        
        binding.factoryResetButton.setOnClickListener {
            performFactoryReset()
        }
        
        binding.emergencyShutdownButton.setOnClickListener {
            emergencyShutdown()
        }
        
        // Exit admin panel
        binding.exitAdminButton.setOnClickListener {
            exitAdminPanel()
        }
        
        binding.returnToMainButton.setOnClickListener {
            returnToMainScreen()
        }
    }
    
    private fun setupKioskSettings() {
        // Timeout settings
        binding.saveTimeoutButton.setOnClickListener {
            saveTimeoutSettings()
        }
        
        // Kiosk mode toggle
        binding.kioskModeToggle.setOnCheckedChangeListener { _, isChecked ->
            updateKioskMode(isChecked)
        }
        
        // Demo mode toggle
        binding.demoModeToggle.setOnCheckedChangeListener { _, isChecked ->
            updateDemoMode(isChecked)
        }
        
        // Debug mode toggle
        binding.debugModeToggle.setOnCheckedChangeListener { _, isChecked ->
            updateDebugMode(isChecked)
        }
    }
    
    private fun setupMaintenanceControls() {
        binding.scheduledMaintenanceButton.setOnClickListener {
            scheduleMaintenanceMode()
        }
        
        binding.maintenanceModeToggle.setOnCheckedChangeListener { _, isChecked ->
            toggleMaintenanceMode(isChecked)
        }
        
        binding.updateFirmwareButton.setOnClickListener {
            checkFirmwareUpdate()
        }
        
        binding.backupSettingsButton.setOnClickListener {
            backupSystemSettings()
        }
        
        binding.restoreSettingsButton.setOnClickListener {
            restoreSystemSettings()
        }
    }
    
    private fun loadCurrentSettings() {
        // Load timeout settings
        val prefs = requireContext().getSharedPreferences("system_settings", 0)
        
        binding.inactivityTimeoutInput.setText(prefs.getInt("inactivity_timeout", 30).toString())
        binding.sessionTimeoutInput.setText(prefs.getInt("session_timeout", 300).toString())
        
        // Load toggle states
        binding.kioskModeToggle.isChecked = prefs.getBoolean("kiosk_mode", true)
        binding.demoModeToggle.isChecked = prefs.getBoolean("demo_mode", false)
        binding.debugModeToggle.isChecked = prefs.getBoolean("debug_mode", false)
        binding.maintenanceModeToggle.isChecked = prefs.getBoolean("maintenance_mode", false)
        
        // Load system info
        updateSystemInfo()
    }
    
    private fun saveTimeoutSettings() {
        try {
            val inactivityTimeout = binding.inactivityTimeoutInput.text.toString().toIntOrNull() ?: 30
            val sessionTimeout = binding.sessionTimeoutInput.text.toString().toIntOrNull() ?: 300
            
            // Validate ranges
            if (inactivityTimeout < 10 || inactivityTimeout > 300) {
                Toast.makeText(context, "Inactivity timeout must be between 10-300 seconds", Toast.LENGTH_SHORT).show()
                return
            }
            
            if (sessionTimeout < 60 || sessionTimeout > 3600) {
                Toast.makeText(context, "Session timeout must be between 60-3600 seconds", Toast.LENGTH_SHORT).show()
                return
            }
            
            requireContext().getSharedPreferences("system_settings", 0)
                .edit()
                .putInt("inactivity_timeout", inactivityTimeout)
                .putInt("session_timeout", sessionTimeout)
                .apply()
            
            binding.systemStatusText.text = "Timeout settings saved: Inactivity=${inactivityTimeout}s, Session=${sessionTimeout}s"
            Toast.makeText(context, "Timeout settings saved", Toast.LENGTH_SHORT).show()
            
            AppLog.i(TAG, "Timeout settings updated: inactivity=${inactivityTimeout}s, session=${sessionTimeout}s")
            
        } catch (e: Exception) {
            AppLog.e(TAG, "Error saving timeout settings", e)
            binding.systemStatusText.text = "Error saving settings: ${e.message}"
            Toast.makeText(context, "Error saving settings", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun updateKioskMode(enabled: Boolean) {
        lifecycleScope.launch {
            try {
                requireContext().getSharedPreferences("system_settings", 0)
                    .edit()
                    .putBoolean("kiosk_mode", enabled)
                    .apply()
                
                binding.systemStatusText.text = "Kiosk mode ${if (enabled) "enabled" else "disabled"}"
                AppLog.i(TAG, "Kiosk mode ${if (enabled) "enabled" else "disabled"}")
                
                if (enabled) {
                    Toast.makeText(context, "Kiosk mode enabled - Navigation restricted", Toast.LENGTH_SHORT).show()
                }
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Error updating kiosk mode", e)
                binding.systemStatusText.text = "Error updating kiosk mode: ${e.message}"
            }
        }
    }
    
    private fun updateDemoMode(enabled: Boolean) {
        lifecycleScope.launch {
            try {
                requireContext().getSharedPreferences("system_settings", 0)
                    .edit()
                    .putBoolean("demo_mode", enabled)
                    .apply()
                
                binding.systemStatusText.text = "Demo mode ${if (enabled) "enabled" else "disabled"}"
                AppLog.i(TAG, "Demo mode ${if (enabled) "enabled" else "disabled"}")
                
                if (enabled) {
                    Toast.makeText(context, "Demo mode enabled - No real dispensing", Toast.LENGTH_SHORT).show()
                }
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Error updating demo mode", e)
                binding.systemStatusText.text = "Error updating demo mode: ${e.message}"
            }
        }
    }
    
    private fun updateDebugMode(enabled: Boolean) {
        lifecycleScope.launch {
            try {
                requireContext().getSharedPreferences("system_settings", 0)
                    .edit()
                    .putBoolean("debug_mode", enabled)
                    .apply()
                
                binding.systemStatusText.text = "Debug mode ${if (enabled) "enabled" else "disabled"}"
                AppLog.i(TAG, "Debug mode ${if (enabled) "enabled" else "disabled"}")
                
                if (enabled) {
                    Toast.makeText(context, "Debug mode enabled - Verbose logging active", Toast.LENGTH_SHORT).show()
                }
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Error updating debug mode", e)
                binding.systemStatusText.text = "Error updating debug mode: ${e.message}"
            }
        }
    }
    
    private fun toggleMaintenanceMode(enabled: Boolean) {
        lifecycleScope.launch {
            try {
                requireContext().getSharedPreferences("system_settings", 0)
                    .edit()
                    .putBoolean("maintenance_mode", enabled)
                    .putLong("maintenance_mode_timestamp", System.currentTimeMillis())
                    .apply()
                
                binding.systemStatusText.text = "Maintenance mode ${if (enabled) "enabled" else "disabled"}"
                AppLog.w(TAG, "Maintenance mode ${if (enabled) "enabled" else "disabled"}")
                
                if (enabled) {
                    Toast.makeText(context, "⚠️ MAINTENANCE MODE ENABLED\nSystem locked for public use", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "✅ Maintenance mode disabled\nSystem operational", Toast.LENGTH_SHORT).show()
                }
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Error updating maintenance mode", e)
                binding.systemStatusText.text = "Error updating maintenance mode: ${e.message}"
            }
        }
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
        binding.systemStatusText.text = "Preparing to restart system..."
        AppLog.w(TAG, "System restart initiated")
        
        lifecycleScope.launch {
            try {
                Toast.makeText(context, "System will restart in 3 seconds", Toast.LENGTH_LONG).show()
                
                kotlinx.coroutines.delay(3000)
                
                AppLog.i(TAG, "Restarting application")
                
                // Restart the application
                val intent = Intent(requireContext(), MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                requireActivity().finish()
                
            } catch (e: Exception) {
                AppLog.e(TAG, "System restart failed", e)
                binding.systemStatusText.text = "Restart failed: ${e.message}"
                Toast.makeText(context, "Restart failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun performFactoryReset() {
        binding.systemStatusText.text = "⚠ FACTORY RESET - This will erase all data!"
        
        // Show confirmation dialog
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Factory Reset")
            .setMessage("This will erase all settings, logs, and data. Are you sure?")
            .setPositiveButton("Reset") { _, _ ->
                executeFactoryReset()
            }
            .setNegativeButton("Cancel") { _, _ ->
                binding.systemStatusText.text = "Factory reset cancelled"
            }
            .show()
    }
    
    private fun executeFactoryReset() {
        lifecycleScope.launch {
            try {
                binding.systemStatusText.text = "Performing factory reset..."
                AppLog.w(TAG, "Factory reset initiated - Erasing all data")
                
                // Clear all preferences
                val systemPrefs = requireContext().getSharedPreferences("system_settings", 0)
                val adminPrefs = requireContext().getSharedPreferences("admin_settings", 0)
                
                AppLog.i(TAG, "Clearing ${systemPrefs.all.size} system settings")
                systemPrefs.edit().clear().apply()
                
                AppLog.i(TAG, "Clearing ${adminPrefs.all.size} admin settings")
                adminPrefs.edit().clear().apply()
                
                // Clear logs
                AppLog.i(TAG, "Clearing ${LogCollector.getCount()} log entries")
                LogCollector.clear()
                
                // Clear cache and data
                val cacheCleared = requireContext().cacheDir.deleteRecursively()
                AppLog.i(TAG, "Cache cleared: $cacheCleared")
                
                Toast.makeText(context, "Factory reset complete. Restarting...", Toast.LENGTH_LONG).show()
                
                kotlinx.coroutines.delay(3000)
                
                AppLog.i(TAG, "Restarting application after factory reset")
                
                // Restart application
                val intent = Intent(requireContext(), MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                requireActivity().finish()
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Factory reset failed", e)
                binding.systemStatusText.text = "Factory reset failed: ${e.message}"
                Toast.makeText(context, "Factory reset failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun emergencyShutdown() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("⚠️ Emergency Shutdown")
            .setMessage("This will immediately stop all operations and close the application.\n\nAre you sure?")
            .setPositiveButton("SHUTDOWN") { _, _ ->
                executeEmergencyShutdown()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun executeEmergencyShutdown() {
        binding.systemStatusText.text = "⚠️ EMERGENCY SHUTDOWN INITIATED"
        AppLog.e(TAG, "EMERGENCY SHUTDOWN INITIATED BY ADMIN")
        
        lifecycleScope.launch {
            try {
                Toast.makeText(context, "⚠️ EMERGENCY SHUTDOWN IN PROGRESS", Toast.LENGTH_LONG).show()
                
                // Log shutdown reason
                AppLog.w(TAG, "Stopping all hardware operations")
                
                // TODO: Stop all hardware operations
                // val waterFountainManager = WaterFountainManager.getInstance(requireContext())
                // waterFountainManager.emergencyStop()
                
                kotlinx.coroutines.delay(2000)
                
                AppLog.i(TAG, "Emergency shutdown complete - Exiting application")
                
                // Exit application
                requireActivity().finishAffinity()
                System.exit(0)
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Emergency shutdown failed", e)
                binding.systemStatusText.text = "Shutdown failed: ${e.message}"
            }
        }
    }
    
    private fun scheduleMaintenanceMode() {
        AppLog.i(TAG, "Scheduled maintenance requested")
        
        // Show dialog to schedule maintenance
        val timeOptions = arrayOf(
            "In 1 hour",
            "In 2 hours",
            "In 6 hours",
            "In 12 hours",
            "In 24 hours",
            "Custom time"
        )
        
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Schedule Maintenance Mode")
            .setItems(timeOptions) { _, which ->
                val hours = when (which) {
                    0 -> 1
                    1 -> 2
                    2 -> 6
                    3 -> 12
                    4 -> 24
                    else -> {
                        // Custom time - would need a time picker
                        Toast.makeText(context, "Custom time picker not yet implemented", Toast.LENGTH_SHORT).show()
                        return@setItems
                    }
                }
                
                val scheduledTime = System.currentTimeMillis() + (hours * 3600000L)
                
                requireContext().getSharedPreferences("system_settings", 0)
                    .edit()
                    .putLong("scheduled_maintenance_time", scheduledTime)
                    .putBoolean("maintenance_scheduled", true)
                    .apply()
                
                val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(scheduledTime))
                binding.systemStatusText.text = "Maintenance scheduled for $timeStr"
                Toast.makeText(context, "Maintenance scheduled for $timeStr", Toast.LENGTH_LONG).show()
                
                AppLog.i(TAG, "Maintenance scheduled for $timeStr")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun checkFirmwareUpdate() {
        binding.systemStatusText.text = "Checking for firmware updates..."
        
        lifecycleScope.launch {
            try {
                AppLog.i(TAG, "Firmware update check initiated")
                
                // Get current version
                val packageInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
                val currentVersion = packageInfo.versionName ?: "Unknown"
                
                // TODO: Implement actual update check with backend API
                // For now, just report current version
                
                val message = "Current version: $currentVersion\nNo updates available"
                binding.systemStatusText.text = message
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                
                AppLog.i(TAG, "Firmware check complete: $currentVersion")
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Firmware update check failed", e)
                binding.systemStatusText.text = "Update check failed: ${e.message}"
            }
        }
    }
    
    private fun backupSystemSettings() {
        lifecycleScope.launch {
            try {
                binding.systemStatusText.text = "Creating system backup..."
                AppLog.i(TAG, "System backup initiated")
                
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "waterfountain_backup_$timestamp.txt"
                val file = File(requireContext().cacheDir, fileName)
                
                FileWriter(file).use { writer ->
                    writer.write("Water Fountain Vending Machine - System Backup\n")
                    writer.write("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
                    writer.write("=".repeat(50) + "\n\n")
                    
                    // Backup system settings
                    writer.write("SYSTEM SETTINGS:\n")
                    val systemPrefs = requireContext().getSharedPreferences("system_settings", 0)
                    systemPrefs.all.forEach { (key, value) ->
                        writer.write("$key = $value\n")
                    }
                    writer.write("\n")
                    
                    // Backup admin settings
                    writer.write("ADMIN SETTINGS:\n")
                    val adminPrefs = requireContext().getSharedPreferences("admin_settings", 0)
                    adminPrefs.all.forEach { (key, value) ->
                        writer.write("$key = $value\n")
                    }
                    writer.write("\n")
                    
                    // System info
                    writer.write("SYSTEM INFO:\n")
                    val packageInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
                    writer.write("App Version: ${packageInfo.versionName}\n")
                    writer.write("Android Version: ${Build.VERSION.RELEASE}\n")
                    writer.write("Device Model: ${Build.MODEL}\n")
                    writer.write("Device Manufacturer: ${Build.MANUFACTURER}\n")
                    writer.write("\n")
                    
                    // Logs summary
                    writer.write("LOGS SUMMARY:\n")
                    writer.write("Total logs: ${LogCollector.getCount()}\n")
                }
                
                // Share the backup file
                shareBackupFile(file)
                
                binding.systemStatusText.text = "System backup created successfully"
                Toast.makeText(context, "System backup created", Toast.LENGTH_SHORT).show()
                AppLog.i(TAG, "System backup completed: $fileName")
                
            } catch (e: Exception) {
                AppLog.e(TAG, "System backup failed", e)
                binding.systemStatusText.text = "Backup failed: ${e.message}"
                Toast.makeText(context, "Backup failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun shareBackupFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Water Fountain System Backup")
                putExtra(Intent.EXTRA_TEXT, "System backup file from Water Fountain Vending Machine")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            
            startActivity(Intent.createChooser(intent, "Export System Backup"))
            
        } catch (e: Exception) {
            AppLog.e(TAG, "Error sharing backup file", e)
            Toast.makeText(context, "Error sharing backup file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun restoreSystemSettings() {
        AppLog.i(TAG, "System restore requested")
        
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Restore System Settings")
            .setMessage("To restore settings, you need to import a backup file. This feature requires selecting a backup file from storage.\n\nNote: Manual restore is currently required. Import your backup file through Settings > Import.")
            .setPositiveButton("OK") { _, _ ->
                binding.systemStatusText.text = "Restore requires manual import"
            }
            .show()
        
        // TODO: Implement file picker for backup restoration
        // This would require:
        // 1. File picker to select backup file
        // 2. Parse and validate backup file
        // 3. Restore preferences from backup
        // 4. Restart application
    }
    
    private fun exitAdminPanel() {
        AppLog.i(TAG, "Admin panel exit requested")
        requireActivity().finish()
    }
    
    private fun returnToMainScreen() {
        AppLog.i(TAG, "Returning to main screen from admin panel")
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
            
            AppLog.d(TAG, "System info updated: Memory=$memoryPercent%, Storage=${storageUsedGB}GB/${storageTotalGB}GB")
            
        } catch (e: Exception) {
            AppLog.e(TAG, "Error updating system info", e)
            binding.systemStatusText.text = "Error loading system info: ${e.message}"
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
