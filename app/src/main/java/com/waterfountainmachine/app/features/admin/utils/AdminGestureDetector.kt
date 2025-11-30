package com.waterfountainmachine.app.admin

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.View
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.utils.AdminDebugConfig

/**
 * Utility class to detect secret admin access trigger:
 * - Press Enter key 3 times rapidly (via USB keyboard) to access admin panel
 */
class AdminGestureDetector(
    private val context: Context,
    private val view: View
) {
    
    companion object {
        private const val TAG = "AdminGestureDetector"
    }
    
    // Keyboard gesture settings
    private val keyPressTimeout = 1000L // milliseconds between key presses
    private var lastKeyPressTime = 0L
    private var keyPressCount = 0
    private val requiredKeyPresses = 3 // Number of Enter presses needed
    
    /**
     * Handle keyboard events for admin access via USB keyboard
     * Call this from dispatchKeyEvent in your activity (only for ACTION_DOWN events)
     */
    fun onKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        // Only handle Enter key presses on ACTION_DOWN
        if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastPress = currentTime - lastKeyPressTime
            
            AdminDebugConfig.logAdmin(context, TAG, "Enter key ACTION_DOWN received (time since last: ${timeSinceLastPress}ms)")
            
            // Check if this is within the rapid-press window
            if (lastKeyPressTime > 0 && timeSinceLastPress < keyPressTimeout) {
                keyPressCount++
                AdminDebugConfig.logAdmin(context, TAG, "Rapid press detected! Count: $keyPressCount/$requiredKeyPresses (within ${timeSinceLastPress}ms)")
                
                // Triple-press detected!
                if (keyPressCount >= requiredKeyPresses) {
                    AdminDebugConfig.logAdminInfo(context, TAG, "*** ADMIN ACCESS TRIGGERED via USB keyboard! ***")
                    onGestureDetected()
                    keyPressCount = 0 // Reset for next gesture
                    lastKeyPressTime = 0L
                    return true
                }
            } else {
                // First press or timeout expired
                if (lastKeyPressTime > 0) {
                    AdminDebugConfig.logAdmin(context, TAG, "Timeout expired (${timeSinceLastPress}ms > ${keyPressTimeout}ms), resetting counter")
                }
                keyPressCount = 1
                AdminDebugConfig.logAdmin(context, TAG, "Starting new sequence: 1/$requiredKeyPresses")
            }
            
            lastKeyPressTime = currentTime
            return true // ALWAYS consume Enter key events to prevent screen clicks
        }
        
        return false // Don't consume other keys
    }
    
    private fun onGestureDetected() {
        // Launch admin authentication
        val intent = Intent(context, AdminAuthActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }
}
