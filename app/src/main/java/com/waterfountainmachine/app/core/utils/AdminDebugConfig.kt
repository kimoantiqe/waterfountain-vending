package com.waterfountainmachine.app.utils

import android.content.Context
import com.waterfountainmachine.app.BuildConfig

/**
 * Admin Debug Configuration
 * 
 * Controls debug logging behavior in admin panels.
 * Can be toggled via SharedPreferences for temporary debugging.
 * 
 * Usage:
 * 1. Default: Logging disabled in admin panels (privacy/security)
 * 2. Enable temporarily: Via admin panel toggle
 * 3. Auto-disable: On app restart or after timeout
 * 
 * Security Note:
 * - Admin logs may contain sensitive system information
 * - Should only be enabled for active debugging
 * - Automatically disabled by default
 */
object AdminDebugConfig {
    
    private const val TAG = "AdminDebugConfig"
    private const val PREF_KEY_ADMIN_LOGGING = "admin_debug_logging_enabled"
    private const val PREF_KEY_LOGGING_ENABLED_AT = "admin_debug_logging_enabled_at"
    
    // Auto-disable admin logging after 1 hour
    private const val AUTO_DISABLE_TIMEOUT_MS = 60 * 60 * 1000L // 1 hour
    
    /**
     * Check if admin logging is currently enabled
     * 
     * @param context Application context
     * @return true if admin logging is enabled and not expired
     */
    fun isAdminLoggingEnabled(context: Context): Boolean {
        val prefs = SecurePreferences.getSystemSettings(context)
        val enabled = prefs.getBoolean(PREF_KEY_ADMIN_LOGGING, false)
        
        if (!enabled) {
            return false
        }
        
        // Check if logging session has expired
        val enabledAt = prefs.getLong(PREF_KEY_LOGGING_ENABLED_AT, 0)
        val now = System.currentTimeMillis()
        
        if (now - enabledAt > AUTO_DISABLE_TIMEOUT_MS) {
            // Auto-disable after timeout
            disableAdminLogging(context)
            AppLog.i(TAG, "Admin logging auto-disabled after timeout")
            return false
        }
        
        return true
    }
    
    /**
     * Enable admin logging for debugging
     * Automatically expires after 1 hour
     * 
     * @param context Application context
     */
    fun enableAdminLogging(context: Context) {
        val prefs = SecurePreferences.getSystemSettings(context)
        prefs.edit()
            .putBoolean(PREF_KEY_ADMIN_LOGGING, true)
            .putLong(PREF_KEY_LOGGING_ENABLED_AT, System.currentTimeMillis())
            .apply()
        
        AppLog.i(TAG, "✅ Admin logging enabled (auto-disables in 1 hour)")
    }
    
    /**
     * Disable admin logging
     * 
     * @param context Application context
     */
    fun disableAdminLogging(context: Context) {
        val prefs = SecurePreferences.getSystemSettings(context)
        prefs.edit()
            .putBoolean(PREF_KEY_ADMIN_LOGGING, false)
            .remove(PREF_KEY_LOGGING_ENABLED_AT)
            .apply()
        
        AppLog.i(TAG, "❌ Admin logging disabled")
    }
    
    /**
     * Toggle admin logging state
     * 
     * @param context Application context
     * @return new state (true if now enabled)
     */
    fun toggleAdminLogging(context: Context): Boolean {
        val currentState = isAdminLoggingEnabled(context)
        
        if (currentState) {
            disableAdminLogging(context)
        } else {
            enableAdminLogging(context)
        }
        
        return !currentState
    }
    
    /**
     * Get remaining time before admin logging auto-disables
     * 
     * @param context Application context
     * @return remaining milliseconds, or 0 if not enabled
     */
    fun getRemainingLoggingTime(context: Context): Long {
        if (!isAdminLoggingEnabled(context)) {
            return 0
        }
        
        val prefs = SecurePreferences.getSystemSettings(context)
        val enabledAt = prefs.getLong(PREF_KEY_LOGGING_ENABLED_AT, 0)
        val now = System.currentTimeMillis()
        val elapsed = now - enabledAt
        
        return maxOf(0, AUTO_DISABLE_TIMEOUT_MS - elapsed)
    }
    
    /**
     * Conditional logging for admin panels
     * Only logs if admin logging is enabled
     * 
     * @param context Application context
     * @param tag Log tag
     * @param message Log message
     */
    fun logAdmin(context: Context, tag: String, message: String) {
        if (isAdminLoggingEnabled(context)) {
            AppLog.d(tag, "[ADMIN] $message")
        }
    }
    
    /**
     * Conditional info logging for admin panels
     */
    fun logAdminInfo(context: Context, tag: String, message: String) {
        if (isAdminLoggingEnabled(context)) {
            AppLog.i(tag, "[ADMIN] $message")
        }
    }
    
    /**
     * Conditional warning logging for admin panels
     */
    fun logAdminWarning(context: Context, tag: String, message: String) {
        if (isAdminLoggingEnabled(context)) {
            AppLog.w(tag, "[ADMIN] $message")
        }
    }
    
    /**
     * Always log errors (security critical)
     * Even if admin logging is disabled
     * 
     * Note: context parameter removed as errors are always logged
     */
    fun logAdminError(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            AppLog.e(tag, "[ADMIN] $message", throwable)
        } else {
            AppLog.e(tag, "[ADMIN] $message")
        }
    }
}
