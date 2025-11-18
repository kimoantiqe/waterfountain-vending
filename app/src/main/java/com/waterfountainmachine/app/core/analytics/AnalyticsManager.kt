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
class AnalyticsManager private constructor(context: Context) {
    
    private val firebaseAnalytics: FirebaseAnalytics = Firebase.analytics
    
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
        private const val EVENT_PHONE_NUMBER_DIGIT_ENTERED = "phone_digit_entered"
        private const val EVENT_PHONE_NUMBER_COMPLETED = "phone_number_completed"
        private const val EVENT_PHONE_NUMBER_CLEARED = "phone_number_cleared"
        private const val EVENT_BACKSPACE_PRESSED = "backspace_pressed"
        private const val EVENT_CONSENT_VIEWED = "consent_viewed"
        private const val EVENT_CONSENT_ACCEPTED = "consent_accepted"
        private const val EVENT_PRIVACY_POLICY_CLICKED = "privacy_policy_clicked"
        private const val EVENT_SMS_SEND_REQUESTED = "sms_send_requested"
        private const val EVENT_SMS_SENT_SUCCESS = "sms_sent_success"
        private const val EVENT_SMS_SENT_FAILURE = "sms_sent_failure"
        private const val EVENT_OTP_DIGIT_ENTERED = "otp_digit_entered"
        private const val EVENT_OTP_COMPLETED = "otp_completed"
        private const val EVENT_OTP_VERIFIED_SUCCESS = "otp_verified_success"
        private const val EVENT_OTP_VERIFIED_FAILURE = "otp_verified_failure"
        private const val EVENT_OTP_RESEND_CLICKED = "otp_resend_clicked"
        private const val EVENT_FAQ_OPENED = "faq_opened"
        private const val EVENT_FAQ_CLOSED = "faq_closed"
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
    }
    
    init {
        // Enable analytics collection
        firebaseAnalytics.setAnalyticsCollectionEnabled(true)
        AppLog.i(TAG, "AnalyticsManager initialized")
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
     */
    private fun getCommonParameters(): Bundle {
        return Bundle().apply {
            putString("device_model", getDeviceModel())
            putString("time_of_day", getTimeOfDay())
            putString("day_of_week", getDayOfWeek())
            putString("app_version", BuildConfig.VERSION_NAME)
            putLong("timestamp", System.currentTimeMillis())
        }
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
     */
    fun logAppOpened() {
        val params = getCommonParameters()
        firebaseAnalytics.logEvent(EVENT_APP_OPENED, params)
        AppLog.d(TAG, "Event: app_opened | device=${params.getString("device_model")}, time=${params.getString("time_of_day")}, day=${params.getString("day_of_week")}")
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
     * Track phone number digit entry
     */
    fun logPhoneDigitEntered(digit: String, currentLength: Int) {
        val params = getCommonParameters().apply {
            putString(PARAM_DIGIT, digit)
            putLong(PARAM_PHONE_LENGTH, currentLength.toLong())
        }
        firebaseAnalytics.logEvent(EVENT_PHONE_NUMBER_DIGIT_ENTERED, params)
        AppLog.d(TAG, "Event: phone_digit_entered (digit=$digit, length=$currentLength)")
    }
    
    /**
     * Track phone number completion
     */
    fun logPhoneNumberCompleted(phoneNumber: String, timeToCompleteMs: Long) {
        firebaseAnalytics.logEvent(EVENT_PHONE_NUMBER_COMPLETED) {
            param(PARAM_PHONE_LENGTH, phoneNumber.length.toLong())
            param(PARAM_TIME_TO_COMPLETE, timeToCompleteMs)
        }
        AppLog.d(TAG, "Event: phone_number_completed (length=${phoneNumber.length}, time=${timeToCompleteMs}ms)")
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
     * Track privacy policy link clicked
     */
    fun logPrivacyPolicyClicked() {
        firebaseAnalytics.logEvent(EVENT_PRIVACY_POLICY_CLICKED, null)
        AppLog.d(TAG, "Event: privacy_policy_clicked")
    }
    
    // ==================== SMS & OTP ====================
    
    /**
     * Track SMS send requested
     */
    fun logSmsSendRequested(phoneNumber: String) {
        firebaseAnalytics.logEvent(EVENT_SMS_SEND_REQUESTED) {
            param(PARAM_PHONE_LENGTH, phoneNumber.length.toLong())
        }
        AppLog.d(TAG, "Event: sms_send_requested")
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
     * Track OTP digit entered
     */
    fun logOtpDigitEntered(currentLength: Int) {
        firebaseAnalytics.logEvent(EVENT_OTP_DIGIT_ENTERED) {
            param(PARAM_OTP_LENGTH, currentLength.toLong())
        }
        AppLog.d(TAG, "Event: otp_digit_entered (length=$currentLength)")
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
        firebaseAnalytics.logEvent(EVENT_FAQ_OPENED) {
            param(PARAM_SCREEN_NAME, screenName)
        }
        AppLog.d(TAG, "Event: faq_opened (screen=$screenName)")
    }
    
    /**
     * Track FAQ modal closed
     */
    fun logFaqClosed(screenName: String) {
        firebaseAnalytics.logEvent(EVENT_FAQ_CLOSED) {
            param(PARAM_SCREEN_NAME, screenName)
        }
        AppLog.d(TAG, "Event: faq_closed (screen=$screenName)")
    }
    
    // ==================== VENDING ====================
    
    /**
     * Track vending animation started
     */
    fun logVendingStarted(slotNumber: Int) {
        firebaseAnalytics.logEvent(EVENT_VENDING_STARTED) {
            param(PARAM_SLOT_NUMBER, slotNumber.toLong())
        }
        AppLog.d(TAG, "Event: vending_started (slot=$slotNumber)")
    }
    
    /**
     * Track vending completed successfully
     */
    fun logVendingCompleted(slotNumber: Int, durationMs: Long) {
        firebaseAnalytics.logEvent(EVENT_VENDING_COMPLETED) {
            param(PARAM_SLOT_NUMBER, slotNumber.toLong())
            param(PARAM_DURATION, durationMs)
            param(PARAM_SUCCESS, "true")
        }
        AppLog.d(TAG, "Event: vending_completed (slot=$slotNumber, duration=${durationMs}ms)")
    }
    
    /**
     * Track vending failed
     */
    fun logVendingFailed(slotNumber: Int, errorMessage: String) {
        firebaseAnalytics.logEvent(EVENT_VENDING_FAILED) {
            param(PARAM_SLOT_NUMBER, slotNumber.toLong())
            param(PARAM_ERROR_MESSAGE, errorMessage)
            param(PARAM_SUCCESS, "false")
        }
        AppLog.d(TAG, "Event: vending_failed (slot=$slotNumber, error=$errorMessage)")
    }
    
    // ==================== USER FLOW & ABANDONMENT ====================
    
    /**
     * Track user abandoned the flow
     */
    fun logUserAbandoned(screenName: String, reason: String = "unknown") {
        firebaseAnalytics.logEvent(EVENT_USER_ABANDONED) {
            param(PARAM_SCREEN_NAME, screenName)
            param(PARAM_REASON, reason)
        }
        AppLog.d(TAG, "Event: user_abandoned (screen=$screenName, reason=$reason)")
    }
    
    /**
     * Track timeout occurred
     */
    fun logTimeoutOccurred(screenName: String) {
        firebaseAnalytics.logEvent(EVENT_TIMEOUT_OCCURRED) {
            param(PARAM_SCREEN_NAME, screenName)
        }
        AppLog.d(TAG, "Event: timeout_occurred (screen=$screenName)")
    }
    
    /**
     * Track return to main screen
     */
    fun logReturnToMain(fromScreen: String) {
        firebaseAnalytics.logEvent(EVENT_RETURN_TO_MAIN) {
            param(PARAM_SCREEN_NAME, fromScreen)
        }
        AppLog.d(TAG, "Event: return_to_main (from=$fromScreen)")
    }
    
    // ==================== HARDWARE & ERRORS ====================
    
    /**
     * Track hardware error
     */
    fun logHardwareError(errorMessage: String, errorCode: String? = null) {
        firebaseAnalytics.logEvent(EVENT_HARDWARE_ERROR) {
            param(PARAM_ERROR_MESSAGE, errorMessage)
            errorCode?.let { param(PARAM_ERROR_CODE, it) }
        }
        AppLog.d(TAG, "Event: hardware_error (error=$errorMessage)")
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
