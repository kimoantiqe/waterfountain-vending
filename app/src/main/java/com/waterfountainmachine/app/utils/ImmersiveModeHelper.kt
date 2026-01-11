package com.waterfountainmachine.app.utils

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Helper class to manage immersive mode (hiding navigation bar and status bar)
 * Supports both legacy and modern Android APIs
 */
object ImmersiveModeHelper {
    
    private const val TAG = "ImmersiveModeHelper"
    
    /**
     * Enable immersive mode - hides navigation bar and status bar
     * Uses sticky immersive mode that auto-hides after user swipes to reveal
     * Also sets up window focus listener to immediately re-hide if user swipes
     */
    fun enableImmersiveMode(activity: Activity) {
        try {
            AppLog.d(TAG, "Enabling immersive mode")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+ (API 30+) - Use WindowInsetsController
                val windowInsetsController = activity.window.insetsController
                
                if (windowInsetsController != null) {
                    // Hide both status bar and navigation bar
                    windowInsetsController.hide(
                        android.view.WindowInsets.Type.statusBars() 
                        or android.view.WindowInsets.Type.navigationBars()
                    )
                    
                    // Set behavior - bars reappear temporarily when user swipes, then auto-hide
                    windowInsetsController.systemBarsBehavior = 
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    
                    AppLog.i(TAG, "Immersive mode enabled using WindowInsetsController (API 30+)")
                } else {
                    AppLog.w(TAG, "WindowInsetsController is null, falling back to legacy method")
                    enableImmersiveModeLegacy(activity)
                }
            } else {
                // Android 10 and below - Use legacy flags
                enableImmersiveModeLegacy(activity)
            }
            
            // Set up window focus listener to immediately re-hide bars when they appear
            setupWindowFocusListener(activity)
            
        } catch (e: Exception) {
            AppLog.e(TAG, "Error enabling immersive mode", e)
        }
    }
    
    /**
     * Setup window focus change listener to immediately re-hide system bars
     * This catches when user swipes to reveal bars and re-hides them instantly
     */
    private fun setupWindowFocusListener(activity: Activity) {
        val decorView = activity.window.decorView
        
        decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            // Check if immersive mode was broken (bars became visible)
            if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                AppLog.d(TAG, "System UI visibility changed - re-applying immersive mode")
                
                // Re-apply immersive mode immediately
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    activity.window.insetsController?.let { controller ->
                        controller.hide(
                            android.view.WindowInsets.Type.statusBars() 
                            or android.view.WindowInsets.Type.navigationBars()
                        )
                    }
                } else {
                    @Suppress("DEPRECATION")
                    decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                    )
                }
            }
        }
        
        AppLog.d(TAG, "Window focus listener set up to re-hide system bars on reveal")
    }
    
    /**
     * Legacy method for Android 10 and below
     * Uses View.SYSTEM_UI_FLAG_* constants
     */
    @Suppress("DEPRECATION")
    private fun enableImmersiveModeLegacy(activity: Activity) {
        val decorView = activity.window.decorView
        
        // Set up immersive sticky mode with all flags
        decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY  // Auto-hide after user interaction
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION  // Hide navigation bar
            or View.SYSTEM_UI_FLAG_FULLSCREEN  // Hide status bar
        )
        
        AppLog.i(TAG, "Immersive mode enabled using legacy flags (API < 30)")
    }
    
    /**
     * Disable immersive mode - shows navigation bar and status bar
     */
    fun disableImmersiveMode(activity: Activity) {
        try {
            AppLog.d(TAG, "Disabling immersive mode")
            
            // Remove the focus listener when disabling
            val decorView = activity.window.decorView
            decorView.setOnSystemUiVisibilityChangeListener(null)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+ (API 30+)
                val windowInsetsController = activity.window.insetsController
                
                if (windowInsetsController != null) {
                    // Show both status bar and navigation bar
                    windowInsetsController.show(
                        android.view.WindowInsets.Type.statusBars() 
                        or android.view.WindowInsets.Type.navigationBars()
                    )
                    
                    // Reset behavior to default
                    windowInsetsController.systemBarsBehavior = 
                        WindowInsetsController.BEHAVIOR_DEFAULT
                    
                    AppLog.i(TAG, "Immersive mode disabled using WindowInsetsController (API 30+)")
                } else {
                    AppLog.w(TAG, "WindowInsetsController is null, falling back to legacy method")
                    disableImmersiveModeLegacy(activity)
                }
            } else {
                // Android 10 and below
                disableImmersiveModeLegacy(activity)
            }
            
        } catch (e: Exception) {
            AppLog.e(TAG, "Error disabling immersive mode", e)
        }
    }
    
    /**
     * Legacy method to disable immersive mode
     */
    @Suppress("DEPRECATION")
    private fun disableImmersiveModeLegacy(activity: Activity) {
        val decorView = activity.window.decorView
        
        // Clear all immersive flags
        decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
        
        AppLog.i(TAG, "Immersive mode disabled using legacy flags (API < 30)")
    }
    
    /**
     * Apply immersive mode based on saved preference
     * Call this from Activity.onResume() to ensure immersive mode is maintained
     */
    fun applyImmersiveModeFromSettings(activity: Activity) {
        try {
            val prefs = SecurePreferences.getSystemSettings(activity)
            val hideNavBar = prefs.getBoolean("hide_navigation_bar", true)
            
            if (hideNavBar) {
                enableImmersiveMode(activity)
            } else {
                // Don't disable - let system decide (normal mode)
                AppLog.d(TAG, "Navigation bar hiding disabled in settings")
            }
            
        } catch (e: Exception) {
            AppLog.e(TAG, "Error applying immersive mode from settings", e)
        }
    }
}
