package com.waterfountainmachine.app.features.error
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.lifecycleScope
import com.waterfountainmachine.app.R
import com.waterfountainmachine.app.features.admin.utils.AdminGestureDetector
import com.waterfountainmachine.app.core.config.WaterFountainConfig
import com.waterfountainmachine.app.core.di.HealthMonitorModule
import com.waterfountainmachine.app.core.ui.KioskActivity
import com.waterfountainmachine.app.databinding.ActivityErrorBinding
import com.waterfountainmachine.app.core.utils.AppLog
import com.waterfountainmachine.app.core.utils.SecurePreferences
import com.waterfountainmachine.app.core.utils.SoundManager
import com.waterfountainmachine.app.core.utils.UserErrorMessages
import com.waterfountainmachine.app.features.vending.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Generic error activity for displaying error messages or daily limit reached.
 * 
 * Use cases:
 * - User has reached daily limit of 2 vends
 * - Unrecoverable errors occur
 * - Any critical error that requires user notification
 * 
 * Features:
 * - Fades in over 500ms
 * - Displays for 15 seconds (configurable)
 * - Fades out over 500ms
 * - Returns to MainActivity
 * 
 * Usage:
 * ```kotlin
 * // Daily limit reached
 * val intent = Intent(this, ErrorActivity::class.java)
 * intent.putExtra(ErrorActivity.EXTRA_MESSAGE, UserErrorMessages.DAILY_LIMIT_REACHED)
 * startActivity(intent)
 * 
 * // Custom error
 * val intent = Intent(this, ErrorActivity::class.java)
 * intent.putExtra(ErrorActivity.EXTRA_MESSAGE, UserErrorMessages.GENERIC_ERROR)
 * startActivity(intent)
 * ```
 */
class ErrorActivity : KioskActivity() {

    private lateinit var binding: ActivityErrorBinding
    private lateinit var soundManager: SoundManager
    private lateinit var adminGestureDetector: AdminGestureDetector
    private var logoBobAnimator: ObjectAnimator? = null

    // Machine-status watch (only armed for the disabled/maintenance screens).
    // When the machine is no longer disabled/maintenance, return to the main
    // screen instead of waiting out the 24h timer.
    private var watchMachineStatus = false
    private var isReturning = false
    private val machineHealthPrefs by lazy { getSharedPreferences("machine_health", MODE_PRIVATE) }
    private val systemSettingsPrefs by lazy { SecurePreferences.getSystemSettings(this) }
    private val statusChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (watchMachineStatus && !machineStillUnavailable()) {
            AppLog.i(TAG, "Machine recovered (no longer disabled/maintenance) - returning to main")
            returnToMainScreen()
        }
    }

    override val fullScreenRoot: View
        get() = binding.root
    
    companion object {
        private const val TAG = "ErrorActivity"
        
        // Intent extras
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_DISPLAY_DURATION = "display_duration"
        // When true, the screen watches machine status and auto-returns to the
        // main screen as soon as the machine is no longer disabled/maintenance.
        const val EXTRA_WATCH_MACHINE_STATUS = "watch_machine_status"
        
        // Default messages (use UserErrorMessages constants)
        const val DEFAULT_DAILY_LIMIT_MESSAGE = UserErrorMessages.DAILY_LIMIT_REACHED
        const val DEFAULT_ERROR_MESSAGE = UserErrorMessages.GENERIC_ERROR
        
        // Timing constants
        private const val FADE_IN_DURATION = 500L
        private const val DEFAULT_DISPLAY_DURATION = 15000L
        private const val FADE_OUT_DURATION = 500L

        // Bob animation — mirrors MainActivity's can bob for visual continuity.
        private const val LOGO_BOB_AMPLITUDE_DP = 18f
        private const val LOGO_BOB_DURATION_MS = 2_600L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityErrorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyFullScreen()

        // Set up admin gesture detector for triple-tap/triple-enter access
        setupAdminGesture()
        
        // Initialize sound manager and play error page sound
        soundManager = SoundManager(this)
        
        // Load and play sound with proper timing
        lifecycleScope.launch {
            try {
                // Load the sound
                soundManager.loadSound(R.raw.error_page)
                AppLog.d(TAG, "Error page sound loaded")
                
                // Wait longer to ensure sound is fully loaded (500ms should be sufficient)
                delay(500)
                
                // Play the sound
                soundManager.playSound(R.raw.error_page, 1.0f)
                AppLog.i(TAG, "Playing error page sound at full volume")
            } catch (e: Exception) {
                AppLog.e(TAG, "Failed to play error page sound", e)
            }
        }
        
        // Disable back button using modern API
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Disable back button to prevent user from navigating away
                // The screen will auto-return after the timer
                AppLog.d(TAG, "Back button pressed but disabled during error display")
            }
        })
        
        // Get custom message if provided, otherwise use default
        val customMessage = intent.getStringExtra(EXTRA_MESSAGE)
        val displayDuration = intent.getLongExtra(EXTRA_DISPLAY_DURATION, DEFAULT_DISPLAY_DURATION)
        
        // Update message
        binding.messageText.text = customMessage ?: DEFAULT_DAILY_LIMIT_MESSAGE
        
        AppLog.i(TAG, "ErrorActivity shown: ${binding.messageText.text}")
        
        // Start the display -> return sequence
        lifecycleScope.launch {
            delay(displayDuration)
            returnToMainScreen()
        }

        // For the disabled/maintenance screens, also watch machine status so the
        // kiosk recovers as soon as an admin clears it (within the monitor's
        // 15-min check) instead of waiting out the 24h timer.
        watchMachineStatus = intent.getBooleanExtra(EXTRA_WATCH_MACHINE_STATUS, false)
        if (watchMachineStatus) {
            machineHealthPrefs.registerOnSharedPreferenceChangeListener(statusChangeListener)
            systemSettingsPrefs.registerOnSharedPreferenceChangeListener(statusChangeListener)
            // Guard against a race where status cleared between launch and now.
            if (!machineStillUnavailable()) returnToMainScreen()
        }

        startLogoBob()
    }

    /**
     * True while the machine should remain on the out-of-service screen: it is
     * remotely disabled, in remote maintenance, or in local maintenance mode.
     * Reuses the monitor's public accessors so the pref keys live in one place.
     */
    private fun machineStillUnavailable(): Boolean {
        val monitor = HealthMonitorModule.getMachineHealthMonitor(this)
        val localMaintenance = systemSettingsPrefs.getBoolean("maintenance_mode", false)
        return monitor.isMachineDisabled() || monitor.isMaintenanceMode() || localMaintenance
    }

    /**
     * Gentle vertical bob on the logo — same tempo/curve as the can bob
     * on [MainActivity] so the kiosk feels coherent across screens.
     */
    private fun startLogoBob() {
        stopLogoBob()
        val amplitudePx = -LOGO_BOB_AMPLITUDE_DP * resources.displayMetrics.density
        logoBobAnimator = ObjectAnimator.ofFloat(
            binding.bobLogo, "translationY", 0f, amplitudePx
        ).apply {
            duration = LOGO_BOB_DURATION_MS
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopLogoBob() {
        logoBobAnimator?.cancel()
        logoBobAnimator = null
    }
    
    private fun setupAdminGesture() {
        adminGestureDetector = AdminGestureDetector(this, binding.root)
        AppLog.d(TAG, "Admin gesture detector initialized for error screen")
    }
    
    // Handle keyboard events for admin access (USB keyboard Enter key)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Only process on ACTION_DOWN to avoid double-counting
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Let admin gesture detector handle keyboard input
            if (adminGestureDetector.onKeyEvent(event.keyCode, event)) {
                AppLog.d(TAG, "Admin keyboard trigger processed on error screen")
                return true
            }
        }
        
        return super.dispatchKeyEvent(event)
    }
    
    private fun returnToMainScreen() {
        // Guard against double-invocation (timer + status listener racing).
        if (isReturning) return
        isReturning = true
        val intent = Intent(this, MainActivity::class.java)
        // Use SINGLE_TOP to reuse existing MainActivity instance for smooth transition
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        
        // Use modern transition API with fade animation
        val options = ActivityOptionsCompat.makeCustomAnimation(
            this,
            android.R.anim.fade_in,
            android.R.anim.fade_out
        )
        
        startActivity(intent, options.toBundle())
        finish()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (watchMachineStatus) {
            machineHealthPrefs.unregisterOnSharedPreferenceChangeListener(statusChangeListener)
            systemSettingsPrefs.unregisterOnSharedPreferenceChangeListener(statusChangeListener)
        }
        stopLogoBob()
        // Clean up sound resources
        if (::soundManager.isInitialized) {
            soundManager.release()
        }
    }
}
