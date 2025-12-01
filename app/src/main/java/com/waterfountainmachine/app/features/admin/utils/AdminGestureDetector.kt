package com.waterfountainmachine.app.admin

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.View
import com.waterfountainmachine.app.utils.AdminDebugConfig

/**
 * Detects admin access trigger: Press Enter key 3 times rapidly (USB keyboard)
 */
class AdminGestureDetector(
    private val context: Context,
    private val view: View
) {
    
    companion object {
        private const val TAG = "AdminGestureDetector"
    }
    
    private val keyPressTimeout = 1000L
    private var lastKeyPressTime = 0L
    private var keyPressCount = 0
    private val requiredKeyPresses = 3
    
    fun onKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastPress = currentTime - lastKeyPressTime
            
            AdminDebugConfig.logAdmin(context, TAG, "Enter key received (time since last: ${timeSinceLastPress}ms)")
            
            if (lastKeyPressTime > 0 && timeSinceLastPress < keyPressTimeout) {
                keyPressCount++
                AdminDebugConfig.logAdmin(context, TAG, "Rapid press: $keyPressCount/$requiredKeyPresses")
                
                if (keyPressCount >= requiredKeyPresses) {
                    AdminDebugConfig.logAdminInfo(context, TAG, "*** ADMIN ACCESS TRIGGERED ***")
                    onGestureDetected()
                    keyPressCount = 0
                    lastKeyPressTime = 0L
                    return true
                }
            } else {
                if (lastKeyPressTime > 0) {
                    AdminDebugConfig.logAdmin(context, TAG, "Timeout expired, resetting")
                }
                keyPressCount = 1
                AdminDebugConfig.logAdmin(context, TAG, "New sequence: 1/$requiredKeyPresses")
            }
            
            lastKeyPressTime = currentTime
            return true // Consume Enter key to prevent unintended actions
        }
        
        return false
    }
    
    private fun onGestureDetected() {
        val intent = Intent(context, AdminAuthActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }
}
