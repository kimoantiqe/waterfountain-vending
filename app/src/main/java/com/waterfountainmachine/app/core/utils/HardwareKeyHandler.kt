package com.waterfountainmachine.app.utils

import android.view.KeyEvent

/**
 * Utility object for handling hardware key blocking in kiosk mode.
 * 
 * Centralizes the logic for blocking HOME, RECENT_APPS, and other system keys
 * to prevent users from exiting the app in kiosk/vending machine scenarios.
 */
object HardwareKeyHandler {
    
    /**
     * Handle key down events for kiosk mode.
     * Blocks HOME and RECENT_APPS keys, delegates BACK key to custom handler.
     * 
     * @param keyCode The key code from the key event
     * @param onBackPressed Optional callback for handling back button press
     * @return true if the key was handled (blocked), false to pass through to default handler
     */
    fun handleKeyDown(keyCode: Int, onBackPressed: (() -> Unit)? = null): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_RECENT_APPS -> {
                // Block these keys in kiosk mode
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                // Allow custom back button handling
                onBackPressed?.invoke()
                true
            }
            else -> false // Let other keys pass through
        }
    }
}
