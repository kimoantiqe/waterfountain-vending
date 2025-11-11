package com.waterfountainmachine.app.activities

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.waterfountainmachine.app.databinding.ActivityErrorBinding
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.utils.FullScreenUtils
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
 * intent.putExtra(ErrorActivity.EXTRA_MESSAGE, ErrorActivity.DEFAULT_DAILY_LIMIT_MESSAGE)
 * startActivity(intent)
 * 
 * // Custom error
 * val intent = Intent(this, ErrorActivity::class.java)
 * intent.putExtra(ErrorActivity.EXTRA_MESSAGE, "Custom error message")
 * startActivity(intent)
 * ```
 */
class ErrorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityErrorBinding
    
    companion object {
        private const val TAG = "ErrorActivity"
        
        // Intent extras
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_DISPLAY_DURATION = "display_duration"
        
        // Default messages
        const val DEFAULT_DAILY_LIMIT_MESSAGE = "We are very sorry,\nplease come visit us again tomorrow."
        const val DEFAULT_ERROR_MESSAGE = "An error occurred.\nPlease try again."
        
        // Timing constants
        private const val FADE_IN_DURATION = 500L
        private const val DEFAULT_DISPLAY_DURATION = 15000L
        private const val FADE_OUT_DURATION = 500L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityErrorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        FullScreenUtils.setupFullScreen(window, binding.root)
        
        // Get custom message if provided, otherwise use default
        val customMessage = intent.getStringExtra(EXTRA_MESSAGE)
        val displayDuration = intent.getLongExtra(EXTRA_DISPLAY_DURATION, DEFAULT_DISPLAY_DURATION)
        
        // Update message
        binding.messageText.text = customMessage ?: DEFAULT_DAILY_LIMIT_MESSAGE
        
        AppLog.i(TAG, "ErrorActivity shown: ${binding.messageText.text}")
        
        // Start the display -> fade out -> return sequence
        // Note: Fade in is handled by overridePendingTransition in the calling activity
        lifecycleScope.launch {
            delay(displayDuration)
            fadeOut()
            delay(FADE_OUT_DURATION) // Wait for fade out animation to complete
            returnToMainScreen()
        }
    }
    
    private fun fadeOut() {
        ObjectAnimator.ofFloat(binding.root, "alpha", 1f, 0f).apply {
            duration = FADE_OUT_DURATION
            start()
        }
    }
    
    private fun returnToMainScreen() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
    
    override fun onBackPressed() {
        // Disable back button to prevent user from navigating away
        // The screen will auto-return after the timer
    }
}
