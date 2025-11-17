package com.waterfountainmachine.app.utils

import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Crashlytics Helper with PII Redaction
 * 
 * Provides safe logging to Crashlytics with automatic PII masking.
 * Use this instead of directly calling FirebaseCrashlytics to ensure
 * sensitive data is never sent to crash reports.
 * 
 * PII Protection:
 * - Phone numbers: Only last 4 digits logged
 * - Machine IDs: Only last 4 characters logged
 * - OTP codes: Never logged
 * - Admin PINs: Never logged
 * - Certificates: Never logged
 * 
 * Usage:
 * ```kotlin
 * CrashlyticsHelper.logPhoneEvent("SMS_SENT", phone = "+1234567890")
 * CrashlyticsHelper.logMachineEvent("VENDING_START", machineId = "ABC123XYZ")
 * CrashlyticsHelper.logError("Payment failed", error)
 * ```
 */
object CrashlyticsHelper {
    
    private val crashlytics: FirebaseCrashlytics by lazy {
        FirebaseCrashlytics.getInstance()
    }
    
    /**
     * Mask phone number for Crashlytics
     * Example: +1234567890 -> ****7890
     */
    private fun maskPhone(phone: String): String {
        if (phone.length < 4) return "****"
        return "****${phone.takeLast(4)}"
    }
    
    /**
     * Mask machine ID for Crashlytics
     * Example: ABC123XYZ -> ****XYZ (last 4 chars, or 3 if shorter)
     */
    private fun maskMachineId(machineId: String): String {
        if (machineId.length < 4) return "****${machineId.takeLast(3)}"
        return "****${machineId.takeLast(4)}"
    }
    
    /**
     * Log a phone-related event with PII protection
     * 
     * @param eventName Event name (e.g., "SMS_SENT", "OTP_VERIFIED")
     * @param phone Full phone number (will be masked)
     * @param additionalKeys Optional additional context
     */
    fun logPhoneEvent(
        eventName: String, 
        phone: String, 
        additionalKeys: Map<String, String> = emptyMap()
    ) {
        crashlytics.setCustomKey("event", eventName)
        crashlytics.setCustomKey("phone_suffix", maskPhone(phone))
        
        additionalKeys.forEach { (key, value) ->
            crashlytics.setCustomKey(key, value)
        }
        
        crashlytics.log("Event: $eventName | Phone: ${maskPhone(phone)}")
    }
    
    /**
     * Log a machine-related event with PII protection
     * 
     * @param eventName Event name (e.g., "VENDING_START", "HARDWARE_ERROR")
     * @param machineId Machine ID (will be masked)
     * @param additionalKeys Optional additional context
     */
    fun logMachineEvent(
        eventName: String,
        machineId: String,
        additionalKeys: Map<String, String> = emptyMap()
    ) {
        crashlytics.setCustomKey("event", eventName)
        crashlytics.setCustomKey("machine_id_suffix", maskMachineId(machineId))
        
        additionalKeys.forEach { (key, value) ->
            crashlytics.setCustomKey(key, value)
        }
        
        crashlytics.log("Event: $eventName | Machine: ${maskMachineId(machineId)}")
    }
    
    /**
     * Log a general event without PII
     * 
     * @param eventName Event name
     * @param keys Optional context keys (ensure no PII!)
     */
    fun logEvent(eventName: String, keys: Map<String, String> = emptyMap()) {
        crashlytics.setCustomKey("event", eventName)
        
        keys.forEach { (key, value) ->
            crashlytics.setCustomKey(key, value)
        }
        
        crashlytics.log("Event: $eventName")
    }
    
    /**
     * Log an error to Crashlytics
     * 
     * @param message Error message (should not contain PII)
     * @param throwable Optional exception
     */
    fun logError(message: String, throwable: Throwable? = null) {
        crashlytics.log("ERROR: $message")
        
        if (throwable != null) {
            crashlytics.recordException(throwable)
        }
    }
    
    /**
     * Record a non-fatal exception
     * 
     * @param throwable Exception to record
     */
    fun recordException(throwable: Throwable) {
        crashlytics.recordException(throwable)
    }
    
    /**
     * Set user identifier (machine ID only, already masked)
     * 
     * @param machineId Machine ID (will be masked)
     */
    fun setMachineIdentifier(machineId: String) {
        crashlytics.setUserId(maskMachineId(machineId))
    }
    
    /**
     * Clear all user-specific data
     * Call this during logout or session end
     */
    fun clearUserData() {
        crashlytics.setUserId("")
        crashlytics.setCustomKey("machine_id_suffix", "")
        crashlytics.setCustomKey("phone_suffix", "")
    }
    
    /**
     * Record breadcrumb (should not contain PII)
     * 
     * @param message Breadcrumb message
     */
    fun recordBreadcrumb(message: String) {
        crashlytics.log(message)
    }
    
    /**
     * Set custom key (ensure value does not contain PII!)
     * 
     * @param key Key name
     * @param value Value (no PII!)
     */
    fun setCustomKey(key: String, value: String) {
        crashlytics.setCustomKey(key, value)
    }
    
    /**
     * Set custom key (boolean)
     */
    fun setCustomKey(key: String, value: Boolean) {
        crashlytics.setCustomKey(key, value)
    }
    
    /**
     * Set custom key (int)
     */
    fun setCustomKey(key: String, value: Int) {
        crashlytics.setCustomKey(key, value)
    }
    
    /**
     * Set custom key (long)
     */
    fun setCustomKey(key: String, value: Long) {
        crashlytics.setCustomKey(key, value)
    }
    
    /**
     * Set custom key (float)
     */
    fun setCustomKey(key: String, value: Float) {
        crashlytics.setCustomKey(key, value)
    }
    
    /**
     * Set custom key (double)
     */
    fun setCustomKey(key: String, value: Double) {
        crashlytics.setCustomKey(key, value)
    }
}
