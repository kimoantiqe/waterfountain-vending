package com.waterfountainmachine.app.utils

/**
 * Phone Number Utilities
 * 
 * Ensures consistent phone number formatting across the app for:
 * - Backend API calls
 * - Phone number hashing
 * - Display and logging
 * 
 * All phone numbers are normalized to E.164 format (+12345678900)
 * before being sent to the backend or hashed.
 */
object PhoneNumberUtils {
    
    /**
     * Normalize phone number to E.164 format
     * 
     * Ensures consistent format for backend communication and hashing:
     * - Removes all whitespace, dashes, parentheses, dots
     * - Adds '+1' prefix for US numbers if not present
     * - Returns clean E.164 format (e.g., "+12345678900")
     * 
     * Examples:
     * - "2345678900" -> "+12345678900"
     * - "+1 234 567 8900" -> "+12345678900"
     * - "234-567-8900" -> "+12345678900"
     * - "+12345678900" -> "+12345678900"
     * 
     * @param phone Phone number in any format (10-digit US or already formatted)
     * @return Normalized phone number in E.164 format
     */
    fun normalizePhoneNumber(phone: String): String {
        // Remove all whitespace, dashes, parentheses, dots
        var normalized = phone.replace(Regex("[\\s\\-().]"), "")
        
        // Ensure it starts with '+'
        if (!normalized.startsWith("+")) {
            // If it's a 10-digit US number, add +1
            if (normalized.length == 10 && normalized.matches(Regex("^[2-9]\\d{9}$"))) {
                normalized = "+1$normalized"
            } else {
                // Otherwise add + to the beginning
                normalized = "+$normalized"
            }
        }
        
        return normalized
    }
    
    /**
     * Validate if phone number is in valid E.164 format
     * 
     * E.164 format:
     * - Starts with '+'
     * - Followed by country code (1-3 digits)
     * - Followed by subscriber number
     * - Total length: 2-15 characters (including '+')
     * 
     * @param phone Phone number to validate
     * @return true if valid E.164 format
     */
    fun isValidE164(phone: String): Boolean {
        // E.164 regex: + followed by 1-15 digits
        return phone.matches(Regex("^\\+[1-9]\\d{1,14}$"))
    }
    
    /**
     * Format 10-digit US phone for display
     * 
     * @param phone 10-digit phone number (without country code)
     * @return Formatted phone like "(234) 567-8900"
     */
    fun formatUsPhoneForDisplay(phone: String): String {
        if (phone.length != 10) return phone
        
        return "(${phone.substring(0, 3)}) ${phone.substring(3, 6)}-${phone.substring(6)}"
    }
    
    /**
     * Mask phone number for logging (privacy)
     * 
     * Examples:
     * - "+12345678900" -> "+1***-***-8900"
     * - "2345678900" -> "***-***-8900"
     * 
     * @param phone Phone number in any format
     * @return Masked phone number for safe logging
     */
    fun maskPhoneForLogging(phone: String): String {
        val normalized = normalizePhoneNumber(phone)
        
        if (normalized.length < 4) return "***"
        
        val countryCode = if (normalized.startsWith("+1")) "+1" else ""
        val last4 = normalized.takeLast(4)
        
        return "$countryCode***-***-$last4"
    }
}
