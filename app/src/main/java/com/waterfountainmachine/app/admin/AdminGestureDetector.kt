package com.waterfountainmachine.app.admin

import android.content.Context
import android.content.Intent
import android.view.MotionEvent
import android.view.View

/**
 * Utility class to detect secret admin gesture:
 * - Double-click in top left corner to access admin panel
 */
class AdminGestureDetector(
    private val context: Context,
    private val view: View
) {
    
    private val cornerThreshold = 100f // pixels from top-left corner
    private val doubleClickTimeout = 500L // milliseconds between clicks
    private var lastClickTime = 0L
    private var clickCount = 0
    
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                
                // Check if click is in top-left corner activation zone
                if (x <= cornerThreshold && y <= cornerThreshold) {
                    val currentTime = System.currentTimeMillis()
                    
                    // Check if this is within the double-click window
                    if (currentTime - lastClickTime < doubleClickTimeout) {
                        clickCount++
                        
                        // Double-click detected!
                        if (clickCount >= 2) {
                            onGestureDetected()
                            clickCount = 0 // Reset for next gesture
                            lastClickTime = 0L
                            return true
                        }
                    } else {
                        // First click or timeout expired
                        clickCount = 1
                    }
                    
                    lastClickTime = currentTime
                    return true // Consume the event in corner area
                }
            }
        }
        
        return false // Don't consume the event
    }
    
    private fun onGestureDetected() {
        // Launch admin authentication
        val intent = Intent(context, AdminAuthActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }
}
