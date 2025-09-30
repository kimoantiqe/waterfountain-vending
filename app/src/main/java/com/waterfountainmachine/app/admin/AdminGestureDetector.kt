package com.waterfountainmachine.app.admin

import android.content.Context
import android.content.Intent
import android.view.MotionEvent
import android.view.View

/**
 * Utility class to detect secret admin gesture:
 * - Click in top left corner to access admin panel
 */
class AdminGestureDetector(
    private val context: Context,
    private val view: View
) {
    
    private val cornerThreshold = 100f // pixels from top-left corner
    
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                
                // Check if click is in top-left corner activation zone
                if (x <= cornerThreshold && y <= cornerThreshold) {
                    // Admin gesture detected - launch admin auth
                    onGestureDetected()
                    return true
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
