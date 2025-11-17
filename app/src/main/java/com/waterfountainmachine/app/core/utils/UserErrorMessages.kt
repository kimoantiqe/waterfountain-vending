package com.waterfountainmachine.app.utils

/**
 * Unified User-Friendly Error Messages
 * 
 * This object contains all user-facing error messages used throughout the vending flow.
 * Technical errors are logged via AppLog but users see friendly, generic messages.
 * 
 * Design Philosophy:
 * - Never expose technical details to users
 * - Keep messages simple and apologetic
 * - Provide clear next steps when possible
 * - Consistent tone across all error scenarios
 */
object UserErrorMessages {
    
    /**
     * Generic error message for unexpected failures
     * Use this when technical error details shouldn't be shown to users
     */
    const val GENERIC_ERROR = "We're sorry!\nPlease try again later."
    
    /**
     * Daily vending limit reached
     * Shown when user has exceeded their daily bottle limit
     */
    const val DAILY_LIMIT_REACHED = "We are very sorry,\nPlease visit us tomorrow."
    
    /**
     * Invalid phone number format
     * Shown when user enters an incorrectly formatted phone number
     */
    const val INVALID_PHONE_NUMBER = "Please enter a valid 10-digit phone number"
    
    /**
     * Invalid OTP code
     * Shown when user enters an incorrect verification code
     */
    const val INVALID_OTP_CODE = "Incorrect code.\nPlease try again."
    
    /**
     * Too many OTP attempts
     * Shown when user has failed OTP verification too many times
     */
    const val TOO_MANY_OTP_ATTEMPTS = "Too many incorrect attempts.\nPlease try again later."
    
    /**
     * Hardware not ready
     * Shown when vending machine hardware is not available
     */
    const val HARDWARE_NOT_READY = "We're sorry!\nMachine is temporarily unavailable."
    
    /**
     * Dispensing failed
     * Shown when bottle dispensing fails
     */
    const val DISPENSING_FAILED = "We're sorry!\nPlease try again or contact staff."
    
    /**
     * Network connection error
     * Shown when there's no internet connection
     */
    const val NETWORK_ERROR = "We're sorry!\nPlease check your connection and try again."
    
    /**
     * Service temporarily unavailable
     * Shown during maintenance or service outages
     */
    const val SERVICE_UNAVAILABLE = "We're sorry!\nService is temporarily unavailable."
    
    /**
     * Session expired
     * Shown when user's session times out
     */
    const val SESSION_EXPIRED = "Session expired.\nPlease start over."
    
    /**
     * QR code scanning failed
     * Shown when QR code cannot be read
     */
    const val QR_SCAN_FAILED = "We're sorry!\nPlease try scanning again."
}
