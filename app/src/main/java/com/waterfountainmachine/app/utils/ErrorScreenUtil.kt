package com.waterfountainmachine.app.utils

import android.content.Context
import android.content.Intent
import com.waterfountainmachine.app.activities.ErrorActivity

/**
 * Utility object for displaying error screens throughout the app.
 * 
 * This provides a centralized way to show the ErrorActivity for various error scenarios:
 * - Daily limit reached
 * - Unrecoverable errors
 * - Network failures
 * - Hardware failures
 * 
 * Usage examples:
 * ```kotlin
 * // Show daily limit reached
 * ErrorScreenUtil.showDailyLimitReached(context)
 * 
 * // Show custom error with custom duration
 * ErrorScreenUtil.showError(
 *     context,
 *     "Unable to connect to server.\nPlease try again later.",
 *     displayDuration = 10000L // 10 seconds
 * )
 * 
 * // Show generic error
 * ErrorScreenUtil.showGenericError(context)
 * ```
 */
object ErrorScreenUtil {
    
    /**
     * Show error screen for daily limit reached (2 vends per day)
     */
    fun showDailyLimitReached(context: Context) {
        val intent = Intent(context, ErrorActivity::class.java)
        intent.putExtra(ErrorActivity.EXTRA_MESSAGE, ErrorActivity.DEFAULT_DAILY_LIMIT_MESSAGE)
        // Use SINGLE_TOP to reuse existing MainActivity instance for smooth transition
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        context.startActivity(intent)
        AppLog.i("ErrorScreenUtil", "Showing daily limit reached screen")
    }
    
    /**
     * Show custom error screen with optional custom display duration
     * 
     * @param context The context to start the activity from
     * @param message The error message to display
     * @param displayDuration How long to display the message in milliseconds (default: 15 seconds)
     */
    fun showError(context: Context, message: String, displayDuration: Long = 15000L) {
        val intent = Intent(context, ErrorActivity::class.java)
        intent.putExtra(ErrorActivity.EXTRA_MESSAGE, message)
        intent.putExtra(ErrorActivity.EXTRA_DISPLAY_DURATION, displayDuration)
        // Use SINGLE_TOP to reuse existing MainActivity instance for smooth transition
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        context.startActivity(intent)
        AppLog.i("ErrorScreenUtil", "Showing error screen: $message")
    }
    
    /**
     * Show generic error screen with default message
     */
    fun showGenericError(context: Context) {
        val intent = Intent(context, ErrorActivity::class.java)
        intent.putExtra(ErrorActivity.EXTRA_MESSAGE, ErrorActivity.DEFAULT_ERROR_MESSAGE)
        // Use SINGLE_TOP to reuse existing MainActivity instance for smooth transition
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        context.startActivity(intent)
        AppLog.i("ErrorScreenUtil", "Showing generic error screen")
    }
    
    /**
     * Show error screen for network failures
     */
    fun showNetworkError(context: Context) {
        showError(
            context,
            "Network connection lost.\nPlease check your connection and try again.",
            displayDuration = 12000L
        )
    }
    
    /**
     * Show error screen for hardware failures
     */
    fun showHardwareError(context: Context) {
        showError(
            context,
            "Hardware error detected.\nPlease contact support.",
            displayDuration = 12000L
        )
    }
    
    /**
     * Show error screen for authentication failures
     */
    fun showAuthError(context: Context) {
        showError(
            context,
            "Authentication failed.\nPlease try again.",
            displayDuration = 10000L
        )
    }
}
