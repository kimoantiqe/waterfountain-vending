package com.waterfountainmachine.app.analytics

import android.content.Context
import android.os.Build
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.analytics.ktx.logEvent
import com.google.firebase.ktx.Firebase
import com.waterfountainmachine.app.BuildConfig
import com.waterfountainmachine.app.utils.AppLog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Centralized analytics manager for tracking user behavior and journey.
 * Wraps Firebase Analytics with type-safe event tracking.
 * adb shell setprop debug.firebase.analytics.app com.waterfountainmachine.app
 * adb shell setprop debug.firebase.analytics.app .none.
 * 
 * Features:
 * - User journey tracking (funnel analysis)
 * - Button click tracking
 * - Drop-off analysis
 * - Session duration tracking
 * - Error tracking
 */
class AnalyticsManager private constructor(private val context: Context) {
    
    private val firebaseAnalytics: FirebaseAnalytics = Firebase.analytics
    
    // Store machine context to attach to all events
    private var currentMachineId: String? = null
    
    // Store campaign context for the current vending session
    private var currentCampaignId: String? = null
    private var currentAdvertiserId: String? = null
    private var currentCanDesignId: String? = null
    
    companion object {
        private const val TAG = "AnalyticsManager"
        
        @Volatile
        private var instance: AnalyticsManager? = null
        
        fun getInstance(context: Context): AnalyticsManager {
            return instance ?: synchronized(this) {
                instance ?: AnalyticsManager(context.applicationContext).also { instance = it }
            }
        }
        
        // Event Names (following Firebase naming conventions: lowercase with underscores)
        private const val EVENT_APP_OPENED = "app_opened"
        private const val EVENT_TAP_TO_START = "tap_to_start_clicked"
        private const val EVENT_PHONE_NUMBER_COMPLETED = "phone_number_completed"
        private const val EVENT_PHONE_NUMBER_CLEARED = "phone_number_cleared"
        private const val EVENT_BACKSPACE_PRESSED = "backspace_pressed"
        private const val EVENT_CONSENT_VIEWED = "consent_viewed"
        private const val EVENT_CONSENT_ACCEPTED = "consent_accepted"
        private const val EVENT_CONSENT_REJECTED = "consent_rejected"
        private const val EVENT_SMS_SEND_REQUESTED = "sms_send_requested"
        private const val EVENT_SMS_SENT_SUCCESS = "sms_sent_success"
        private const val EVENT_SMS_SENT_FAILURE = "sms_sent_failure"
        private const val EVENT_OTP_COMPLETED = "otp_completed"
        private const val EVENT_OTP_VERIFIED_SUCCESS = "otp_verified_success"
        private const val EVENT_OTP_VERIFIED_FAILURE = "otp_verified_failure"
        private const val EVENT_OTP_RESEND_CLICKED = "otp_resend_clicked"
        private const val EVENT_FAQ_OPENED = "faq_opened"
        private const val EVENT_FAQ_CLOSED = "faq_closed"
        private const val EVENT_SCREEN_ENTERED = "screen_entered"
        private const val EVENT_SCREEN_EXITED = "screen_exited"
        private const val EVENT_JOURNEY_COMPLETED = "journey_completed"
        private const val EVENT_VENDING_STARTED = "vending_started"
        private const val EVENT_VENDING_COMPLETED = "vending_completed"
        private const val EVENT_VENDING_FAILED = "vending_failed"
        private const val EVENT_USER_ABANDONED = "user_abandoned"
        private const val EVENT_TIMEOUT_OCCURRED = "timeout_occurred"
        private const val EVENT_RETURN_TO_MAIN = "return_to_main"
        private const val EVENT_HARDWARE_ERROR = "hardware_error"
        
        // Parameter Names
        private const val PARAM_SCREEN_NAME = "screen_name"
        private const val PARAM_DIGIT = "digit"
        private const val PARAM_PHONE_LENGTH = "phone_length"
        private const val PARAM_TIME_TO_COMPLETE = "time_to_complete_ms"
        private const val PARAM_ATTEMPT_NUMBER = "attempt_number"
        private const val PARAM_SUCCESS = "success"
        private const val PARAM_ERROR_MESSAGE = "error_message"
        private const val PARAM_ERROR_CODE = "error_code"
        private const val PARAM_SLOT_NUMBER = "slot_number"
        private const val PARAM_DURATION = "duration_ms"
        private const val PARAM_OTP_LENGTH = "otp_length"
        private const val PARAM_REASON = "reason"
        private const val PARAM_SCREEN_DURATION_MS = "screen_duration_ms"
        private const val PARAM_TOTAL_JOURNEY_DURATION_MS = "total_journey_duration_ms"
        private const val PARAM_DISPENSE_DURATION_MS = "dispense_duration_ms"
        private const val PARAM_JOURNEY_START_TIME = "journey_start_time"
        private const val PARAM_MACHINE_ID = "machine_id"
        private const val PARAM_CAMPAIGN_ID = "campaign_id"
        private const val PARAM_ADVERTISER_ID = "advertiser_id"
        private const val PARAM_CAN_DESIGN_ID = "can_design_id"
    }
    
    init {
        // Enable analytics collection
        firebaseAnalytics.setAnalyticsCollectionEnabled(true)
        AppLog.i(TAG, "AnalyticsManager initialized")
    }
    
    // ==================== DATA VALIDATION ====================
    
    /**
     * Validate machine_id to prevent sending bogus data.
     * Returns validated machine_id or null if invalid.
     */
    private fun validateMachineId(machineId: String?): String? {
        if (machineId.isNullOrBlank()) {
            AppLog.w(TAG, "⚠️ machine_id is null or blank - analytics event will show (not set)")
            return null
        }
        
        // Additional validation: check if it looks like a valid UUID or machine ID format
        if (machineId.length < 10) {
            AppLog.w(TAG, "⚠️ machine_id seems too short: '$machineId' - may be invalid")
            return null
        }
        
        return machineId
    }
    
    /**
     * Validate slot number to prevent sending invalid slot data.
     * System supports 48 slots in 6×8 grid layout:
     * Row 1: 1-8, Row 2: 11-18, Row 3: 21-28, Row 4: 31-38, Row 5: 41-48, Row 6: 51-58
     */
    private fun validateSlotNumber(slotNumber: Int): Int? {
        val row = slotNumber / 10  // 0-5 for rows 1-6
        val col = slotNumber % 10  // 1-8 for columns
        
        if (row < 0 || row > 5 || col < 1 || col > 8) {
            AppLog.e(TAG, "❌ Invalid slot number: $slotNumber (valid: 1-8, 11-18, 21-28, 31-38, 41-48, 51-58)")
            return null
        }
        return slotNumber
    }
    
    /**
     * Validate campaign/advertiser IDs to prevent sending empty strings.
     */
    private fun validateCampaignId(campaignId: String?): String? {
        if (campaignId.isNullOrBlank()) {
            return null
        }
        return campaignId
    }
    
    private fun validatePhoneNumber(phoneNumber: String?): String? {
        if (phoneNumber.isNullOrBlank()) {
            AppLog.w(TAG, "⚠️ phone_number is null or blank")
            return null
        }
        // Check if it's at least 10 digits (US standard)
        val digitsOnly = phoneNumber.replace(Regex("[^0-9]"), "")
        if (digitsOnly.length < 10) {
            AppLog.w(TAG, "⚠️ phone_number too short: '$phoneNumber' (${digitsOnly.length} digits)")
            return null
        }
        return phoneNumber
    }
    
    private fun validateScreenName(screenName: String?): String? {
        if (screenName.isNullOrBlank()) {
            AppLog.w(TAG, "⚠️ screen_name is null or blank")
            return null
        }
        return screenName
    }
    
    // ==================== CONTEXT HELPERS ====================
    
    /**
     * Get device model name (e.g., "Google Pixel 8")
     */
    private fun getDeviceModel(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { 
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() 
        }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
    }
    
    /**
     * Get time of day segment (morning, afternoon, evening, night)
     */
    private fun getTimeOfDay(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 0..5 -> "night"
            in 6..11 -> "morning"
            in 12..17 -> "afternoon"
            in 18..23 -> "evening"
            else -> "unknown"
        }
    }
    
    /**
     * Get day of week (monday, tuesday, etc.)
     */
    private fun getDayOfWeek(): String {
        val calendar = Calendar.getInstance()
        val dayFormat = SimpleDateFormat("EEEE", Locale.ENGLISH)
        return dayFormat.format(calendar.time).lowercase()
    }
    
    /**
     * Get common parameters to add to every event for segmentation
     * These will be available as custom dimensions in GA4
     * 
     * Important: is_mock indicates whether this event is from a test/mock environment
     * Analytics is considered "live" (not mock) when:
     * - Live SMS is enabled (not mock SMS)
     * - Live backend slot service is enabled (not mock backend)
     * - Real hardware is enabled (not simulated hardware)
     */
    private fun getCommonParameters(): Bundle {
        return Bundle().apply {
            putString("device_model", getDeviceModel())
            putString("time_of_day", getTimeOfDay())
            putString("day_of_week", getDayOfWeek())
            putString("app_version", BuildConfig.VERSION_NAME)
            putLong("timestamp", System.currentTimeMillis())
            
            // Determine if this is mock/test environment
            // Analytics is "live" only when ALL three conditions are true:
            // 1. Using live SMS (not mock)
            // 2. Using live backend (not mock)
            // 3. Using real hardware (not mock)
            val isLiveSMS = com.waterfountainmachine.app.di.AuthModule.isUsingLiveSMS(context)
            val isLiveBackend = com.waterfountainmachine.app.di.BackendModule.isUsingLiveBackend(context)
            val isRealHardware = com.waterfountainmachine.app.utils.SecurePreferences.getSystemSettings(context)
                .getBoolean("use_real_serial", false)
            
            val isMock = !(isLiveSMS && isLiveBackend && isRealHardware)
            putBoolean("is_mock", isMock)
            
            // Always attach sessionId if available (critical for journey correlation)
            com.waterfountainmachine.app.WaterFountainApplication.sessionId?.let { 
                putString("session_id", it) 
            }
            
            // Always attach machine_id if available (critical for multi-machine analytics)
            currentMachineId?.let { putString(PARAM_MACHINE_ID, it) }
            
            // Attach campaign context if in an active vending session
            currentCampaignId?.let { putString(PARAM_CAMPAIGN_ID, it) }
            currentAdvertiserId?.let { putString(PARAM_ADVERTISER_ID, it) }
            currentCanDesignId?.let { putString(PARAM_CAN_DESIGN_ID, it) }
        }
    }
    
    // ==================== CONTEXT MANAGEMENT ====================
    
    /**
     * Set machine context - call this once at app start
     * This ensures machine_id is attached to ALL events
     */
    fun setMachineContext(machineId: String?) {
        val validMachineId = validateMachineId(machineId)
        currentMachineId = validMachineId
        AppLog.i(TAG, "Machine context set: ${validMachineId?.let { "****${it.takeLast(4)}" } ?: "null"}")
    }
    
    /**
     * Set campaign context for current vending session
     * Call this when vending starts with campaign data
     */
    fun setCampaignContext(campaignId: String?, advertiserId: String?, canDesignId: String?) {
        currentCampaignId = validateCampaignId(campaignId)
        currentAdvertiserId = validateCampaignId(advertiserId)
        currentCanDesignId = validateCampaignId(canDesignId)
        AppLog.d(TAG, "Campaign context set: campaign=$currentCampaignId, advertiser=$currentAdvertiserId, design=$currentCanDesignId")
    }
    
    /**
     * Clear campaign context after vending completes or fails
     */
    fun clearCampaignContext() {
        currentCampaignId = null
        currentAdvertiserId = null
        currentCanDesignId = null
        AppLog.d(TAG, "Campaign context cleared")
    }
    
    /**
     * Get whether this is a mock/test environment
     * Returns true if ANY of the three components are mocked:
     * - Mock SMS authentication
     * - Mock backend service
     * - Mock hardware (simulated serial)
     */
    fun getIsMock(): Boolean {
        val isLiveSMS = com.waterfountainmachine.app.di.AuthModule.isUsingLiveSMS(context)
        val isLiveBackend = com.waterfountainmachine.app.di.BackendModule.isUsingLiveBackend(context)
        val isRealHardware = com.waterfountainmachine.app.utils.SecurePreferences.getSystemSettings(context)
            .getBoolean("use_real_serial", false)
        
        return !(isLiveSMS && isLiveBackend && isRealHardware)
    }
    
    /**
     * Set user properties for persistent segmentation
     * Call this once when the app starts
     */
    fun setUserProperties() {
        firebaseAnalytics.setUserProperty("device_model", getDeviceModel())
        firebaseAnalytics.setUserProperty("os_version", Build.VERSION.RELEASE)
        firebaseAnalytics.setUserProperty("app_version", BuildConfig.VERSION_NAME)
        AppLog.d(TAG, "User properties set: device=${getDeviceModel()}, os=${Build.VERSION.RELEASE}, app=${BuildConfig.VERSION_NAME}")
    }
    
    /**
     * Enable or disable analytics debug mode
     * When enabled, events appear immediately in Firebase Console DebugView
     */
    fun setDebugMode(enabled: Boolean) {
        // Note: Debug mode must also be enabled via ADB command:
        // adb shell setprop debug.firebase.analytics.app com.waterfountainmachine.app (enable)
        // adb shell setprop debug.firebase.analytics.app .none. (disable)
        AppLog.i(TAG, "Analytics debug mode: ${if (enabled) "ENABLED" else "DISABLED"} (Note: ADB command also required)")
    }
    
    // ==================== APP LIFECYCLE ====================
    
    /**
     * Track app opened event
     * Note: machine_id is now auto-attached via getCommonParameters()
     */
    fun logAppOpened() {
        val params = getCommonParameters()
        firebaseAnalytics.logEvent(EVENT_APP_OPENED, params)
        AppLog.d(TAG, "Event: app_opened | machine=$currentMachineId, device=${params.getString("device_model")}, time=${params.getString("time_of_day")}, day=${params.getString("day_of_week")}")
    }
    
    /**
     * Track when user taps to start
     */
    fun logTapToStart() {
        val params = getCommonParameters()
        firebaseAnalytics.logEvent(EVENT_TAP_TO_START, params)
        AppLog.d(TAG, "Event: tap_to_start_clicked")
    }
    
    // ==================== PHONE NUMBER ENTRY ====================
    
    /**
     * REMOVED: Track individual phone digit entry - Too noisy, not actionable
     * Kept phone_number_completed and backspace_pressed which provide better insights
     */
    // fun logPhoneDigitEntered(digit: String, currentLength: Int) {
    //     val params = getCommonParameters().apply {
    //         putString(PARAM_DIGIT, digit)
    //         putLong(PARAM_PHONE_LENGTH, currentLength.toLong())
    //     }
    //     firebaseAnalytics.logEvent(EVENT_PHONE_NUMBER_DIGIT_ENTERED, params)
    //     AppLog.d(TAG, \"Event: phone_digit_entered (digit=$digit, length=$currentLength)\")
    // }
    
    /**
     * Track phone number completion
     * Note: machine_id is now auto-attached via getCommonParameters()
     */
    fun logPhoneNumberCompleted(phoneNumber: String, timeToCompleteMs: Long) {
        val validPhoneNumber = validatePhoneNumber(phoneNumber)
        
        firebaseAnalytics.logEvent(EVENT_PHONE_NUMBER_COMPLETED) {
            param(PARAM_PHONE_LENGTH, phoneNumber.length.toLong())
            param(PARAM_TIME_TO_COMPLETE, timeToCompleteMs)
        }
        AppLog.d(TAG, "Event: phone_number_completed (machine=$currentMachineId, valid=$validPhoneNumber, length=${phoneNumber.length}, time=${timeToCompleteMs}ms)")
    }
    
    /**
     * Track phone number cleared/backspace
     */
    fun logPhoneNumberCleared() {
        firebaseAnalytics.logEvent(EVENT_PHONE_NUMBER_CLEARED, null)
        AppLog.d(TAG, "Event: phone_number_cleared")
    }
    
    /**
     * Track backspace pressed
     */
    fun logBackspacePressed(currentLength: Int) {
        firebaseAnalytics.logEvent(EVENT_BACKSPACE_PRESSED) {
            param(PARAM_PHONE_LENGTH, currentLength.toLong())
        }
        AppLog.d(TAG, "Event: backspace_pressed (length=$currentLength)")
    }
    
    // ==================== CONSENT & PRIVACY ====================
    
    /**
     * Track consent dialog viewed
     */
    fun logConsentViewed() {
        firebaseAnalytics.logEvent(EVENT_CONSENT_VIEWED, null)
        AppLog.d(TAG, "Event: consent_viewed")
    }
    
    /**
     * Track consent accepted
     */
    fun logConsentAccepted() {
        firebaseAnalytics.logEvent(EVENT_CONSENT_ACCEPTED, null)
        AppLog.d(TAG, "Event: consent_accepted")
    }
    
    /**
     * Track consent rejected
     */
    fun logConsentRejected() {
        firebaseAnalytics.logEvent(EVENT_CONSENT_REJECTED, null)
        AppLog.d(TAG, "Event: consent_rejected")
    }
    
    // ==================== SMS & OTP ====================
    
    /**
     * Track SMS send requested
     * Note: machine_id is now auto-attached via getCommonParameters()
     */
    fun logSmsSendRequested(phoneNumber: String) {
        val validPhoneNumber = validatePhoneNumber(phoneNumber)
        
        firebaseAnalytics.logEvent(EVENT_SMS_SEND_REQUESTED) {
            param(PARAM_PHONE_LENGTH, phoneNumber.length.toLong())
        }
        AppLog.d(TAG, "Event: sms_send_requested (machine=$currentMachineId, valid=$validPhoneNumber)")
    }
    
    /**
     * Track SMS sent successfully
     */
    fun logSmsSentSuccess() {
        firebaseAnalytics.logEvent(EVENT_SMS_SENT_SUCCESS, null)
        AppLog.d(TAG, "Event: sms_sent_success")
    }
    
    /**
     * Track SMS send failure
     */
    fun logSmsSentFailure(errorMessage: String, errorCode: String? = null) {
        firebaseAnalytics.logEvent(EVENT_SMS_SENT_FAILURE) {
            param(PARAM_ERROR_MESSAGE, errorMessage)
            errorCode?.let { param(PARAM_ERROR_CODE, it) }
        }
        AppLog.d(TAG, "Event: sms_sent_failure (error=$errorMessage)")
    }
    
    /**
     * Track OTP completed (all 6 digits entered)
     */
    fun logOtpCompleted(attemptNumber: Int) {
        firebaseAnalytics.logEvent(EVENT_OTP_COMPLETED) {
            param(PARAM_ATTEMPT_NUMBER, attemptNumber.toLong())
        }
        AppLog.d(TAG, "Event: otp_completed (attempt=$attemptNumber)")
    }
    
    /**
     * Track OTP verification success
     */
    fun logOtpVerifiedSuccess(attemptNumber: Int) {
        firebaseAnalytics.logEvent(EVENT_OTP_VERIFIED_SUCCESS) {
            param(PARAM_ATTEMPT_NUMBER, attemptNumber.toLong())
            param(PARAM_SUCCESS, "true")
        }
        AppLog.d(TAG, "Event: otp_verified_success (attempt=$attemptNumber)")
    }
    
    /**
     * Track OTP verification failure
     */
    fun logOtpVerifiedFailure(attemptNumber: Int, errorMessage: String) {
        firebaseAnalytics.logEvent(EVENT_OTP_VERIFIED_FAILURE) {
            param(PARAM_ATTEMPT_NUMBER, attemptNumber.toLong())
            param(PARAM_SUCCESS, "false")
            param(PARAM_ERROR_MESSAGE, errorMessage)
        }
        AppLog.d(TAG, "Event: otp_verified_failure (attempt=$attemptNumber, error=$errorMessage)")
    }
    
    /**
     * Track resend code clicked
     */
    fun logOtpResendClicked() {
        firebaseAnalytics.logEvent(EVENT_OTP_RESEND_CLICKED, null)
        AppLog.d(TAG, "Event: otp_resend_clicked")
    }
    
    // ==================== FAQ ====================
    
    /**
     * Track FAQ modal opened
     */
    fun logFaqOpened(screenName: String) {
        val validScreenName = validateScreenName(screenName) ?: return
        
        firebaseAnalytics.logEvent(EVENT_FAQ_OPENED) {
            param(PARAM_SCREEN_NAME, validScreenName)
        }
        AppLog.d(TAG, "Event: faq_opened (screen=$validScreenName)")
    }
    
    /**
     * Track FAQ modal closed
     */
    fun logFaqClosed(screenName: String, durationMs: Long) {
        val validScreenName = validateScreenName(screenName) ?: return
        
        firebaseAnalytics.logEvent(EVENT_FAQ_CLOSED) {
            param(PARAM_SCREEN_NAME, validScreenName)
            param(PARAM_DURATION, durationMs)
        }
        AppLog.d(TAG, "Event: faq_closed (screen=$validScreenName, duration=${durationMs}ms)")
    }
    
    // ==================== SCREEN DURATION TRACKING ====================
    
    /**
     * Track screen entered
     */
    fun logScreenEntered(screenName: String) {
        val validScreenName = validateScreenName(screenName) ?: return
        
        firebaseAnalytics.logEvent(EVENT_SCREEN_ENTERED) {
            param(PARAM_SCREEN_NAME, validScreenName)
        }
        AppLog.d(TAG, "Event: screen_entered (screen=$validScreenName)")
    }
    
    /**
     * Track screen exited with duration
     */
    fun logScreenExited(screenName: String, durationMs: Long) {
        val validScreenName = validateScreenName(screenName) ?: return
        
        firebaseAnalytics.logEvent(EVENT_SCREEN_EXITED) {
            param(PARAM_SCREEN_NAME, validScreenName)
            param(PARAM_SCREEN_DURATION_MS, durationMs)
        }
        AppLog.d(TAG, "Event: screen_exited (screen=$validScreenName, duration=${durationMs}ms)")
    }
    
    // ==================== JOURNEY COMPLETION TRACKING ====================
    
    /**
     * Track complete user journey with timing metrics
     * Now includes campaign context for per-campaign journey time analysis
     * @param success true if vending succeeded, false if failed
     */
    fun logJourneyCompleted(
        totalJourneyDurationMs: Long,
        dispenseDurationMs: Long,
        journeyStartTime: Long,
        success: Boolean = true
    ) {
        val params = getCommonParameters()
        params.putLong(PARAM_TOTAL_JOURNEY_DURATION_MS, totalJourneyDurationMs)
        params.putLong(PARAM_DISPENSE_DURATION_MS, dispenseDurationMs)
        params.putLong(PARAM_JOURNEY_START_TIME, journeyStartTime)
        params.putBoolean("success", success)
        
        firebaseAnalytics.logEvent(EVENT_JOURNEY_COMPLETED, params)
        AppLog.d(TAG, "Event: journey_completed (machine=$currentMachineId, campaign=$currentCampaignId, advertiser=$currentAdvertiserId, design=$currentCanDesignId, success=$success, total=${totalJourneyDurationMs}ms, dispense=${dispenseDurationMs}ms)")
    }
    
    // ==================== VENDING ====================
    
    /**
     * Track vending animation started
     * Now includes campaign context for campaign conversion rate tracking
     * Note: machine_id is auto-attached via getCommonParameters()
     * Note: slot_number is not included here as it's not yet determined by hardware
     */
    fun logVendingStarted() {
        val params = getCommonParameters()
        
        firebaseAnalytics.logEvent(EVENT_VENDING_STARTED, params)
        AppLog.d(TAG, "Event: vending_started (machine=$currentMachineId, campaign=$currentCampaignId, advertiser=$currentAdvertiserId, design=$currentCanDesignId)")
    }
    
    /**
     * Track vending completed
     * Campaign context is auto-attached via getCommonParameters()
     */
    fun logVendingCompleted(slotNumber: Int, durationMs: Long) {
        val validSlot = validateSlotNumber(slotNumber) ?: return
        
        val params = getCommonParameters()
        params.putLong(PARAM_SLOT_NUMBER, validSlot.toLong())
        params.putLong(PARAM_DURATION, durationMs)
        params.putString(PARAM_SUCCESS, "true")
        
        firebaseAnalytics.logEvent(EVENT_VENDING_COMPLETED, params)
        AppLog.d(TAG, "Event: vending_completed (machine=$currentMachineId, slot=$validSlot, duration=${durationMs}ms, campaign=$currentCampaignId, advertiser=$currentAdvertiserId, design=$currentCanDesignId)")
    }
    
    /**
     * Track vending failed
     * Campaign context is auto-attached via getCommonParameters() for accurate failure attribution
     */
    fun logVendingFailed(slotNumber: Int, errorMessage: String) {
        val validSlot = validateSlotNumber(slotNumber) ?: return
        
        val params = getCommonParameters()
        params.putLong(PARAM_SLOT_NUMBER, validSlot.toLong())
        params.putString(PARAM_ERROR_MESSAGE, errorMessage)
        params.putString(PARAM_SUCCESS, "false")
        
        firebaseAnalytics.logEvent(EVENT_VENDING_FAILED, params)
        AppLog.d(TAG, "Event: vending_failed (machine=$currentMachineId, slot=$validSlot, campaign=$currentCampaignId, advertiser=$currentAdvertiserId, design=$currentCanDesignId, error=$errorMessage)")
    }
    
    /**
     * Track slot empty event (inventory reached 0)
     * Note: machine_id is auto-attached via getCommonParameters()
     */
    fun logSlotEmpty(slotNumber: Int) {
        val validSlot = validateSlotNumber(slotNumber) ?: return
        
        val params = getCommonParameters()
        params.putLong(PARAM_SLOT_NUMBER, validSlot.toLong())
        
        firebaseAnalytics.logEvent("slot_empty", params)
        AppLog.d(TAG, "Event: slot_empty (machine=$currentMachineId, slot=$validSlot)")
    }
    
    /**
     * Track slot low inventory event
     * Note: machine_id is auto-attached via getCommonParameters()
     */
    fun logSlotInventoryLow(slotNumber: Int, remainingBottles: Int) {
        val validSlot = validateSlotNumber(slotNumber) ?: return
        
        val params = getCommonParameters()
        params.putLong(PARAM_SLOT_NUMBER, validSlot.toLong())
        params.putLong("remaining_bottles", remainingBottles.toLong())
        
        firebaseAnalytics.logEvent("slot_inventory_low", params)
        AppLog.d(TAG, "Event: slot_inventory_low (machine=$currentMachineId, slot=$validSlot, bottles=$remainingBottles)")
    }
    
    // ==================== USER FLOW & ABANDONMENT ====================
    
    /**
     * Track user abandoned the flow with screen duration
     * Note: machine_id is auto-attached via getCommonParameters()
     */
    fun logUserAbandoned(
        screenName: String, 
        screenDurationMs: Long, 
        reason: String = "unknown"
    ) {
        val params = getCommonParameters()
        params.putString(PARAM_SCREEN_NAME, screenName)
        params.putLong(PARAM_SCREEN_DURATION_MS, screenDurationMs)
        params.putString(PARAM_REASON, reason)
        
        firebaseAnalytics.logEvent(EVENT_USER_ABANDONED, params)
        AppLog.d(TAG, "Event: user_abandoned (machine=$currentMachineId, campaign=$currentCampaignId, screen=$screenName, duration=${screenDurationMs}ms, reason=$reason)")
    }
    
    /**
     * Track timeout occurred with screen duration
     * Note: machine_id is auto-attached via getCommonParameters()
     */
    fun logTimeoutOccurred(screenName: String, screenDurationMs: Long) {
        val params = getCommonParameters()
        params.putString(PARAM_SCREEN_NAME, screenName)
        params.putLong(PARAM_SCREEN_DURATION_MS, screenDurationMs)
        
        firebaseAnalytics.logEvent(EVENT_TIMEOUT_OCCURRED, params)
        AppLog.d(TAG, "Event: timeout_occurred (machine=$currentMachineId, campaign=$currentCampaignId, screen=$screenName, duration=${screenDurationMs}ms)")
    }
    
    /**
     * Track return to main screen with screen duration
     */
    fun logReturnToMain(fromScreen: String, screenDurationMs: Long) {
        firebaseAnalytics.logEvent(EVENT_RETURN_TO_MAIN) {
            param(PARAM_SCREEN_NAME, fromScreen)
            param(PARAM_SCREEN_DURATION_MS, screenDurationMs)
        }
        AppLog.d(TAG, "Event: return_to_main (from=$fromScreen, duration=${screenDurationMs}ms)")
    }
    
    // ==================== HARDWARE & ERRORS ====================
    
    /**
     * Track hardware error
     * Note: machine_id is auto-attached via getCommonParameters()
     */
    fun logHardwareError(
        errorMessage: String, 
        errorCode: String? = null,
        slotNumber: Int? = null
    ) {
        val validSlot = slotNumber?.let { validateSlotNumber(it) }
        
        val params = getCommonParameters()
        params.putString(PARAM_ERROR_MESSAGE, errorMessage)
        errorCode?.let { params.putString(PARAM_ERROR_CODE, it) }
        validSlot?.let { params.putLong(PARAM_SLOT_NUMBER, it.toLong()) }
        
        firebaseAnalytics.logEvent(EVENT_HARDWARE_ERROR, params)
        AppLog.d(TAG, "Event: hardware_error (machine=$currentMachineId, slot=$validSlot, error=$errorMessage, code=$errorCode)")
    }
    
    // ==================== SCREEN TRACKING ====================
    
    /**
     * Track screen view (automatic with Firebase, but can be called manually)
     */
    fun logScreenView(screenName: String, screenClass: String) {
        firebaseAnalytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW) {
            param(FirebaseAnalytics.Param.SCREEN_NAME, screenName)
            param(FirebaseAnalytics.Param.SCREEN_CLASS, screenClass)
        }
        AppLog.d(TAG, "Screen: $screenName ($screenClass)")
    }
    
    // ==================== USER PROPERTIES ====================
    
    /**
     * Set user property for segmentation
     */
    fun setUserProperty(propertyName: String, value: String) {
        firebaseAnalytics.setUserProperty(propertyName, value)
        AppLog.d(TAG, "User Property: $propertyName = $value")
    }
    
    /**
     * Set machine ID as user property
     */
    fun setMachineId(machineId: String) {
        setUserProperty("machine_id", machineId)
    }
    
    // ==================== SESSION MANAGEMENT ====================
    
    /**
     * Start a new session
     */
    fun startSession() {
        firebaseAnalytics.logEvent("session_start", null)
        AppLog.d(TAG, "Session started")
    }
    
    /**
     * End current session
     */
    fun endSession() {
        firebaseAnalytics.logEvent("session_end", null)
        AppLog.d(TAG, "Session ended")
    }
}
