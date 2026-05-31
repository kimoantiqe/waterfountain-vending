package com.waterfountainmachine.app.core.config
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
        
        // Animation Timings (VendingAnimationActivity) — Phase A choreography:
        //   Phase 1 (scan reminder): t=0..PHASE1_DURATION_MS  — WF logo (bobbing) + centered "Scan QR" reminder
        //   Phase 2 (advertiser):    t=PHASE1_DURATION_MS..PHASE3_DROP_OFFSET_MS  — disc reveal + ripple cadence build
        //   Phase 3 (drop):          t=PHASE3_DROP_OFFSET_MS..end  — mega-crest + confetti + completion text
        const val ANIMATION_FADE_IN_DELAY_MS = 50L
        const val ANIMATION_PHASE1_DURATION_MS = 5_000L            // WF logo + scan QR reminder dwell (then Phase 2 reveal begins)
        const val ANIMATION_LOGO_REVEAL_MS = 1_300L                // logo fade-in (synced to ripple #3)
        // Phase 2 reveal cadence — each delay is measured from the moment
        // the ripple cadence begins (= Phase 2 start). Ripple gaps are
        // 1800/1400/1100/... so reveals land WITH each successive ripple:
        //   ripple #1 (t=0)    → white platform circle appears
        //   ripple #2 (t=1800) → advertiser/default text appears
        //   ripple #3 (t=3200) → logo appears
        const val ANIMATION_PHASE2_PLATFORM_REVEAL_MS = 1_600L      // platform fade-in + bloom duration (with ripple #1)
        const val ANIMATION_PHASE2_TEXT_REVEAL_DELAY_MS = 1_800L     // cadence start → text reveal (ripple #2)
        const val ANIMATION_PHASE2_LOGO_REVEAL_DELAY_MS = 3_200L     // cadence start → logo reveal (ripple #3)
        const val ANIMATION_PHASE2_CADENCE_START_OFFSET_MS = 0L     // legacy; cadence starts immediately at Phase 1→2 transition
        const val ANIMATION_MORPH_TO_LOGO_DELAY_MS = 1_500L        // legacy; retained for older callers (revealLogo uses ANIMATION_LOGO_REVEAL_MS)
        const val ANIMATION_PHASE2_MESSAGE_FADE_IN_MS = 2_200L       // centerMessage alpha fade-in duration (slow, deliberate rise)
        const val ANIMATION_PHASE3_DROP_OFFSET_MS = 15_000L        // confetti + mega-crest + "Your water is ready!" — synced to can-drop
        const val ANIMATION_PHASE3_PICKUP_DELAY_MS = 2_500L        // delay from drop to pickup reminder panel
        
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
        const val ADMIN_LOCKOUT_MINUTES = 60 // 1 hour in minutes (for UI display)

        // (Progress Ring constants removed — replaced by RipplePondView
        // whose tunables live on the view itself as private companion
        // constants. The animation surface no longer renders a literal
        // progress ring; see RipplePondView for the new cadence + crest
        // visuals.)

        // Pickup Reminder Animation (VendingAnimationActivity)
        const val PICKUP_REMINDER_DISPLAY_DURATION_MS = 10_000L  // 10 seconds
        const val PICKUP_REMINDER_FADE_OUT_DURATION_MS = 500L
        const val CHEVRON_PULSE_REPEAT_INTERVAL_MS = 1500L
        const val SHIMMER_REPEAT_INTERVAL_MS = 3000L
        
        // Animation Colors (as Int for setColorFilter)
        const val COLOR_PURPLE_ACCENT = 0xFF3A4250.toInt()
        const val COLOR_DARK_GRAY = 0xFF555555.toInt()
        
        // Slot Configuration
        const val MAX_CANS_PER_SLOT = 4
        const val DEFAULT_SLOT_CAPACITY = 4
        const val TOTAL_SLOTS = 48 // 6 rows × 8 columns
        const val TOTAL_ROWS = 6
        const val TOTAL_COLUMNS = 8
        const val LOW_INVENTORY_THRESHOLD_PERCENT = 20 // Alert when below 20% capacity
        
        // Lane Manager Configuration
        const val MAX_CONSECUTIVE_SLOT_FAILURES = 3
        const val LOAD_BALANCE_THRESHOLD = 10 // Switch slots every N successful dispenses
        const val MAX_FALLBACK_ATTEMPTS = 3
        
        // OTP & Retry Configuration  
        const val MAX_OTP_RETRY_ATTEMPTS = 2 // Allow 2 retries (3 total attempts)
        const val MIN_LOADING_DISPLAY_TIME_MS = 800L
        const val OTP_TIMEOUT_SECONDS = 300 // 5 minutes
        
        // Phone Number Formatting
        const val PHONE_SHORT_LENGTH = 3
        const val PHONE_MEDIUM_LENGTH = 6
        const val PHONE_MASK_MIN_LENGTH = 7
        
        // Certificate Warning Thresholds
        const val CERT_DAYS_CRITICAL = 7 // Red warning
        const val CERT_DAYS_WARNING = 30 // Orange warning
        
        // Machine Health Monitoring
        const val HEALTH_HEARTBEAT_INTERVAL_MS = 60 * 60 * 1000L // 1 hour
        
        // Admin Gesture Detection
        const val ADMIN_GESTURE_KEY_PRESS_TIMEOUT_MS = 1000L
        const val ADMIN_GESTURE_REQUIRED_PRESSES = 7
        
        // Debug Mode Configuration
        const val DEBUG_FAST_POLLING_INTERVAL_MS = 200L
        const val DEBUG_SLOW_POLLING_INTERVAL_MS = 500L
        
        // Inactivity Timeouts (per screen)
        const val SMS_VERIFY_INACTIVITY_TIMEOUT_MS = 300_000L // 5 minutes
        const val ADMIN_AUTH_TIMEOUT_MS = 60_000L // 60 seconds
        
        // Error Display Configuration
        const val ERROR_DEFAULT_DISPLAY_DURATION_MS = 15_000L // 15 seconds
        
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
