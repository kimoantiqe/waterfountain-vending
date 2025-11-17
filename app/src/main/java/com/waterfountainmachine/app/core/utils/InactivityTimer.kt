package com.waterfountainmachine.app.utils

import android.os.Handler
import android.os.Looper

/**
 * Utility class for managing inactivity timers in activities.
 * 
 * Automatically returns to a specified action after a period of user inactivity.
 * Handles timer reset, cleanup, and lifecycle management.
 * 
 * Supports critical state mode to prevent timeout during important operations
 * like water dispensing, OTP requests, or admin PIN validation.
 */
class InactivityTimer(
    private val timeoutMillis: Long,
    private val onTimeout: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null
    
    /**
     * Critical state flag - when true, timer will not trigger timeout
     * Used during operations that should not be interrupted:
     * - Water dispensing
     * - OTP/SMS verification
     * - Admin PIN validation
     * - Payment processing
     */
    private var isInCriticalState = false
    
    /**
     * Start the inactivity timer
     * Timer will only fire if not in critical state
     */
    fun start() {
        stop()
        runnable = Runnable { 
            if (!isInCriticalState) {
                onTimeout()
            } else {
                // Reschedule if in critical state
                runnable?.let { handler.postDelayed(it, timeoutMillis) }
            }
        }
        runnable?.let { handler.postDelayed(it, timeoutMillis) }
    }
    
    /**
     * Stop and clear the inactivity timer
     */
    fun stop() {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
    }
    
    /**
     * Reset the timer (stop and restart)
     */
    fun reset() {
        stop()
        start()
    }
    
    /**
     * Set critical state flag
     * When true, prevents timeout from triggering
     * 
     * @param isCritical true to enter critical state (prevent timeout), false to exit
     */
    fun setCriticalState(isCritical: Boolean) {
        isInCriticalState = isCritical
        AppLog.d("InactivityTimer", "Critical state: $isCritical")
    }
    
    /**
     * Pause the timer (enter critical state)
     * Prevents timeout from firing while paused
     */
    fun pause() {
        setCriticalState(true)
    }
    
    /**
     * Resume the timer (exit critical state)
     * Allows timeout to fire normally
     */
    fun resume() {
        setCriticalState(false)
    }
    
    /**
     * Check if currently in critical state
     */
    fun isInCriticalState(): Boolean = isInCriticalState
    
    /**
     * Cleanup resources (should be called in onDestroy/onPause)
     */
    fun cleanup() {
        stop()
        handler.removeCallbacksAndMessages(null)
        isInCriticalState = false
    }
    
    /**
     * Cancel the timer (alias for cleanup)
     * For compatibility with different coding styles
     */
    fun cancel() {
        cleanup()
    }
}
