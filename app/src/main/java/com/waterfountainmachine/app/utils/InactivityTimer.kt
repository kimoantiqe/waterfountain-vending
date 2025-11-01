package com.waterfountainmachine.app.utils

import android.os.Handler
import android.os.Looper

/**
 * Utility class for managing inactivity timers in activities.
 * 
 * Automatically returns to a specified action after a period of user inactivity.
 * Handles timer reset, cleanup, and lifecycle management.
 */
class InactivityTimer(
    private val timeoutMillis: Long,
    private val onTimeout: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null
    
    /**
     * Start the inactivity timer
     */
    fun start() {
        stop()
        runnable = Runnable { onTimeout() }
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
     * Cleanup resources (should be called in onDestroy/onPause)
     */
    fun cleanup() {
        stop()
        handler.removeCallbacksAndMessages(null)
    }
}
