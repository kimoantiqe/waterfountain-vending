package com.waterfountainmachine.app.config

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Configuration manager for Water Fountain settings
 * Handles persistent storage of slot configuration and other settings
 * Uses EncryptedSharedPreferences for security
 */
class WaterFountainConfig private constructor(context: Context) {
    
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    companion object {
        private const val PREFS_NAME = "water_fountain_config"
        private const val KEY_WATER_SLOT = "water_slot"
        private const val KEY_SERIAL_BAUD_RATE = "serial_baud_rate"
        private const val KEY_STATUS_POLLING_INTERVAL = "status_polling_interval_ms"
        private const val KEY_MAX_POLLING_ATTEMPTS = "max_polling_attempts"
        
        // Default values
        private const val DEFAULT_WATER_SLOT = 1
        private const val DEFAULT_BAUD_RATE = 9600  // VMC uses 9600 baud (informational only)
        private const val DEFAULT_STATUS_POLLING_INTERVAL = 500L
        private const val DEFAULT_MAX_POLLING_ATTEMPTS = 20
        
        // ===== CENTRALIZED CONSTANTS =====
        // All magic numbers from across the app centralized here
        
        // Animation Timings (VendingAnimationActivity)
        const val ANIMATION_FADE_IN_DELAY_MS = 50L
        const val ANIMATION_PROGRESS_START_DELAY_MS = 1000L
        const val ANIMATION_PROGRESS_DURATION_MS = 14000L
        const val ANIMATION_RING_COMPLETION_DELAY_MS = 15000L
        const val ANIMATION_MORPH_TO_LOGO_DELAY_MS = 15500L
        const val ANIMATION_SHOW_COMPLETION_DELAY_MS = 16500L
        const val ANIMATION_RETURN_TO_MAIN_DELAY_MS = 21000L
        
        // Confetti Configuration
        const val CONFETTI_SPEED = 20f
        const val CONFETTI_MAX_SPEED = 40f
        const val CONFETTI_DAMPING = 0.9f
        const val CONFETTI_SPREAD = 90
        const val CONFETTI_DURATION_MS = 4500L
        const val CONFETTI_PARTICLES_PER_SECOND = 30
        
        // Inactivity Timeouts
        const val INACTIVITY_TIMEOUT_MS = 60_000L // 1 minute
        const val INACTIVITY_TIMEOUT_DURING_DISPENSING_MS = 180_000L // 3 minutes (no reset allowed)
        
        // Authentication
        const val MAX_PHONE_LENGTH = 10
        const val OTP_LENGTH = 6
        const val PIN_LENGTH = 8
        const val FIREBASE_FUNCTION_TIMEOUT_MS = 30_000L
        
        // Admin Security
        const val ADMIN_MAX_ATTEMPTS = 3
        const val ADMIN_LOCKOUT_DURATION_MS = 60 * 60 * 1000L // 1 hour
        const val ADMIN_LOCKOUT_MINUTES = 60L // 1 hour in minutes
        
        // Progress Ring Animation
        const val PROGRESS_RING_COLOR_FADE_PERCENT = 8f // First 8% of progress
        const val PROGRESS_RING_HIGHLIGHT_START_PERCENT = 2f
        const val PROGRESS_RING_HIGHLIGHT_FADE_IN_PERCENT = 18f
        const val PROGRESS_RING_HIGHLIGHT_FADE_OUT_START = 90f
        const val PROGRESS_RING_HIGHLIGHT_FADE_OUT_PERCENT = 9.5f
        
        // Progress Ring View Dimensions
        const val RING_STROKE_WIDTH = 50f
        const val INNER_GLOW_STROKE_WIDTH = 70f
        const val OUTER_GLOW_STROKE_WIDTH = 100f
        const val SOFT_EDGE_STROKE_WIDTH = 60f
        
        // Progress Ring View Blur Radii
        const val INNER_GLOW_BLUR_RADIUS = 35f
        const val OUTER_GLOW_BLUR_RADIUS = 60f
        const val SOFT_EDGE_BLUR_RADIUS = 20f
        
        // Progress Ring View Alpha Values
        const val BACKGROUND_RING_ALPHA = 30
        const val OUTER_GLOW_MAX_ALPHA = 140
        const val INNER_GLOW_MAX_ALPHA = 190
        
        // Progress Ring View Layout
        const val VIEW_PADDING = 120
        
        // Progress Ring View Animation
        const val RADIUS_SCALE_FACTOR = 0.42f
        const val GLOW_RADIUS_OFFSET = 150f
        
        @Volatile
        private var INSTANCE: WaterFountainConfig? = null
        
        fun getInstance(context: Context): WaterFountainConfig {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WaterFountainConfig(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    /**
     * Water dispensing slot (1-255)
     * This is the main slot used for water dispensing
     */
    var waterSlot: Int
        get() = prefs.getInt(KEY_WATER_SLOT, DEFAULT_WATER_SLOT)
        set(value) {
            require(value in 1..255) { "Water slot must be between 1 and 255" }
            prefs.edit().putInt(KEY_WATER_SLOT, value).apply()
        }
    
    /**
     * Serial communication baud rate (informational only - vendor SDK hardcodes this)
     */
    var serialBaudRate: Int
        get() = prefs.getInt(KEY_SERIAL_BAUD_RATE, DEFAULT_BAUD_RATE)
        set(value) = prefs.edit().putInt(KEY_SERIAL_BAUD_RATE, value).apply()
    
    /**
     * Status polling interval in milliseconds
     */
    var statusPollingIntervalMs: Long
        get() = prefs.getLong(KEY_STATUS_POLLING_INTERVAL, DEFAULT_STATUS_POLLING_INTERVAL)
        set(value) = prefs.edit().putLong(KEY_STATUS_POLLING_INTERVAL, value).apply()
    
    /**
     * Maximum status polling attempts
     */
    var maxPollingAttempts: Int
        get() = prefs.getInt(KEY_MAX_POLLING_ATTEMPTS, DEFAULT_MAX_POLLING_ATTEMPTS)
        set(value) = prefs.edit().putInt(KEY_MAX_POLLING_ATTEMPTS, value).apply()
    
    /**
     * Reset all settings to defaults
     */
    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }
    
    /**
     * Get configuration summary for debugging
     */
    fun getConfigSummary(): String {
        return """
            Water Fountain Configuration:
            - Water Slot: $waterSlot
            - Serial Baud Rate: $serialBaudRate (informational - SDK manages actual value)
            - Status Polling Interval: ${statusPollingIntervalMs}ms
            - Max Polling Attempts: $maxPollingAttempts
        """.trimIndent()
    }
}
