package com.waterfountainmachine.app.activities

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.lifecycleScope
import com.waterfountainmachine.app.R
import com.waterfountainmachine.app.config.WaterFountainConfig
import com.waterfountainmachine.app.databinding.ActivityErrorBinding
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.utils.FullScreenUtils
import com.waterfountainmachine.app.utils.SoundManager
import com.waterfountainmachine.app.utils.UserErrorMessages
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
class ErrorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityErrorBinding
    private lateinit var soundManager: SoundManager
    
    companion object {
        private const val TAG = "ErrorActivity"
        
        // Intent extras
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_DISPLAY_DURATION = "display_duration"
        
        // Default messages (use UserErrorMessages constants)
        const val DEFAULT_DAILY_LIMIT_MESSAGE = UserErrorMessages.DAILY_LIMIT_REACHED
        const val DEFAULT_ERROR_MESSAGE = UserErrorMessages.GENERIC_ERROR
        
        // Timing constants
        private const val FADE_IN_DURATION = 500L
        private const val DEFAULT_DISPLAY_DURATION = 15000L
        private const val FADE_OUT_DURATION = 500L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set window background to black to prevent white flash
        window.setBackgroundDrawableResource(android.R.color.black)
        
        binding = ActivityErrorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        FullScreenUtils.setupFullScreen(window, binding.root)
        
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
    }
    
    private fun returnToMainScreen() {
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
        // Clean up sound resources
        if (::soundManager.isInitialized) {
            soundManager.release()
        }
    }
}
