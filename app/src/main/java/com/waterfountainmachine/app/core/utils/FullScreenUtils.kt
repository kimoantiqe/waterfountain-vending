package com.waterfountainmachine.app.utils

import android.view.View
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Utility object for managing full-screen immersive mode across the app.
 * 
 * Centralizes full-screen logic to avoid duplication and uses modern
 * WindowInsetsController API (Android 11+) instead of deprecated systemUiVisibility.
 */
object FullScreenUtils {
    
    /**
     * Setup full-screen immersive mode for a window.
     * Uses modern WindowInsetsController API without deprecated flags.
     * 
     * @param window The window to make full-screen
     * @param rootView The root view of the activity
     */
    fun setupFullScreen(window: Window, rootView: View) {
        // Modern approach using WindowInsetsController
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        val controller = WindowInsetsControllerCompat(window, rootView)
        
        // Hide system bars (status bar and navigation bar)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        
        // Set behavior for swipe gestures
        controller.systemBarsBehavior = 
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    
    /**
     * Re-apply full-screen mode (useful in onWindowFocusChanged)
     * 
     * @param window The window to make full-screen
     * @param rootView The root view of the activity
     */
    fun reapplyFullScreen(window: Window, rootView: View) {
        setupFullScreen(window, rootView)
    }
}
