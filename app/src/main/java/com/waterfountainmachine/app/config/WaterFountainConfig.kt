package com.waterfountainmachine.app.config

import android.content.Context
import android.content.SharedPreferences

/**
 * Configuration manager for Water Fountain settings
 * Handles persistent storage of slot configuration and other settings
 */
class WaterFountainConfig private constructor(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )
    
    companion object {
        private const val PREFS_NAME = "water_fountain_config"
        private const val KEY_WATER_SLOT = "water_slot"
        private const val KEY_SERIAL_BAUD_RATE = "serial_baud_rate"
        private const val KEY_COMMAND_TIMEOUT = "command_timeout_ms"
        private const val KEY_STATUS_POLLING_INTERVAL = "status_polling_interval_ms"
        private const val KEY_MAX_POLLING_ATTEMPTS = "max_polling_attempts"
        private const val KEY_AUTO_CLEAR_FAULTS = "auto_clear_faults"
        
        // Default values
        private const val DEFAULT_WATER_SLOT = 1
        private const val DEFAULT_BAUD_RATE = 9600  // VMC uses 9600 baud (CORRECTED from 115200)
        private const val DEFAULT_COMMAND_TIMEOUT = 5000L
        private const val DEFAULT_STATUS_POLLING_INTERVAL = 500L
        private const val DEFAULT_MAX_POLLING_ATTEMPTS = 20
        private const val DEFAULT_AUTO_CLEAR_FAULTS = true
        
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
     * Serial communication baud rate
     */
    var serialBaudRate: Int
        get() = prefs.getInt(KEY_SERIAL_BAUD_RATE, DEFAULT_BAUD_RATE)
        set(value) = prefs.edit().putInt(KEY_SERIAL_BAUD_RATE, value).apply()
    
    /**
     * Command timeout in milliseconds
     */
    var commandTimeoutMs: Long
        get() = prefs.getLong(KEY_COMMAND_TIMEOUT, DEFAULT_COMMAND_TIMEOUT)
        set(value) = prefs.edit().putLong(KEY_COMMAND_TIMEOUT, value).apply()
    
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
     * Whether to automatically clear faults before dispensing
     */
    var autoClearFaults: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CLEAR_FAULTS, DEFAULT_AUTO_CLEAR_FAULTS)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CLEAR_FAULTS, value).apply()
    
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
            - Serial Baud Rate: $serialBaudRate
            - Command Timeout: ${commandTimeoutMs}ms
            - Status Polling Interval: ${statusPollingIntervalMs}ms
            - Max Polling Attempts: $maxPollingAttempts
            - Auto Clear Faults: $autoClearFaults
        """.trimIndent()
    }
}
