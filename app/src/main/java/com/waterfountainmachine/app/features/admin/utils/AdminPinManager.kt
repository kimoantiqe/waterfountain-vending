package com.waterfountainmachine.app.admin

import android.content.Context
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.utils.AdminDebugConfig
import com.waterfountainmachine.app.utils.SecurePreferences
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Secure Admin PIN Manager
 * 
 * Handles admin PIN authentication using industry-standard security practices:
 * - Stores salted SHA-256 hash instead of plaintext PIN
 * - Uses cryptographically secure random salt
 * - Stored in EncryptedSharedPreferences (AES-256-GCM)
 * - Constant-time comparison to prevent timing attacks
 * - Rate limiting with lockout mechanism
 * 
 * Security Features:
 * - PIN never stored in plaintext
 * - Unique salt per device prevents rainbow table attacks
 * - Encrypted storage provides defense-in-depth
 * - Secure random number generation
 * - 3 attempts lockout for 1 hour
 */
object AdminPinManager {
    
    private const val TAG = "AdminPinManager"
    private const val PREF_PIN_HASH = "admin_pin_hash"
    private const val PREF_PIN_SALT = "admin_pin_salt"
    private const val PREF_FAILED_ATTEMPTS = "admin_failed_attempts"
    private const val PREF_LOCKOUT_UNTIL = "admin_lockout_until"
    private const val DEFAULT_PIN = "01121999" // Default PIN for initial setup
    
    /**
     * Initialize PIN system with default PIN if no PIN is set
     * Call this during app initialization
     */
    fun initialize(context: Context) {
        val prefs = SecurePreferences.getSystemSettings(context)
        
        // Check if PIN already configured
        if (!prefs.contains(PREF_PIN_HASH)) {
            AdminDebugConfig.logAdminInfo(context, TAG, "No admin PIN configured, setting default PIN")
            setPin(context, DEFAULT_PIN)
        } else {
            AdminDebugConfig.logAdmin(context, TAG, "Admin PIN already configured")
        }
    }
    
    /**
     * Verify if provided PIN matches stored hash
     * 
     * @param context Application context
     * @param pin PIN to verify
     * @return true if PIN is correct, false otherwise
     */
    fun verifyPin(context: Context, pin: String): Boolean {
        try {
            AdminDebugConfig.logAdmin(context, TAG, "Verifying PIN of length: ${pin.length}")
            
            val prefs = SecurePreferences.getSystemSettings(context)
            
            val storedHash = prefs.getString(PREF_PIN_HASH, null)
            val storedSalt = prefs.getString(PREF_PIN_SALT, null)
            
            if (storedHash == null || storedSalt == null) {
                AppLog.e(TAG, "No PIN configured in secure storage")
                return false
            }
            
            AdminDebugConfig.logAdmin(context, TAG, "Found stored hash and salt")
            
            // Decode salt
            val salt = Base64.getDecoder().decode(storedSalt)
            
            // Hash the provided PIN with stored salt
            val providedHash = hashPin(pin, salt)
            
            AdminDebugConfig.logAdmin(context, TAG, "Generated hash for comparison")
            
            // Constant-time comparison to prevent timing attacks
            val result = constantTimeCompare(providedHash, storedHash)
            
            if (result) {
                AdminDebugConfig.logAdminInfo(context, TAG, "✅ PIN verification successful")
            } else {
                AppLog.w(TAG, "❌ PIN verification failed - incorrect PIN") // SECURITY: Always logged
            }
            
            return result
        } catch (e: Exception) {
            AppLog.e(TAG, "Error verifying PIN", e)
            return false
        }
    }
    
    /**
     * Set new admin PIN (for admin panel PIN change feature)
     * 
     * @param context Application context
     * @param newPin New PIN to set
     * @return true if successful, false otherwise
     */
    fun setPin(context: Context, newPin: String): Boolean {
        try {
            // Validate PIN format (8 digits)
            if (!newPin.matches(Regex("\\d{8}"))) {
                AppLog.e(TAG, "Invalid PIN format. Must be 8 digits.")
                return false
            }
            
            // Generate cryptographically secure random salt
            val salt = generateSalt()
            
            // Hash the PIN with salt
            val hash = hashPin(newPin, salt)
            
            // Store hash and salt in encrypted preferences
            val prefs = SecurePreferences.getSystemSettings(context)
            prefs.edit()
                .putString(PREF_PIN_HASH, hash)
                .putString(PREF_PIN_SALT, Base64.getEncoder().encodeToString(salt))
                .apply()
            
            AdminDebugConfig.logAdminInfo(context, TAG, "Admin PIN updated successfully")
            return true
        } catch (e: Exception) {
            AppLog.e(TAG, "Error setting PIN", e)
            return false
        }
    }
    
    /**
     * Change admin PIN after verifying current PIN
     * 
     * @param context Application context
     * @param currentPin Current PIN for verification
     * @param newPin New PIN to set
     * @return true if successful, false otherwise
     */
    fun changePin(context: Context, currentPin: String, newPin: String): Boolean {
        try {
            // Verify current PIN first
            if (!verifyPin(context, currentPin)) {
                AppLog.w(TAG, "PIN change failed: Current PIN incorrect") // SECURITY: Always logged
                return false
            }
            
            // Prevent setting PIN to same value
            if (currentPin == newPin) {
                AppLog.w(TAG, "PIN change failed: New PIN same as current") // SECURITY: Always logged
                return false
            }
            
            // Set new PIN
            val success = setPin(context, newPin)
            
            if (success) {
                AdminDebugConfig.logAdminInfo(context, TAG, "✅ Admin PIN changed successfully")
                
                // Log to Crashlytics for audit trail (without actual PIN values)
                com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().log(
                    "Admin PIN changed at ${System.currentTimeMillis()}"
                )
            }
            
            return success
        } catch (e: Exception) {
            AppLog.e(TAG, "Error changing PIN", e)
            return false
        }
    }
    
    /**
     * Check if PIN has been changed from default
     */
    fun isDefaultPin(context: Context): Boolean {
        return verifyPin(context, DEFAULT_PIN)
    }
    
    /**
     * Get current rate limit state (failed attempts and lockout time)
     * 
     * @param context Application context
     * @return Pair of (failedAttempts, lockoutUntilTimestamp)
     */
    fun getRateLimitState(context: Context): Pair<Int, Long> {
        val prefs = SecurePreferences.getSystemSettings(context)
        val attempts = prefs.getInt(PREF_FAILED_ATTEMPTS, 0)
        val lockout = prefs.getLong(PREF_LOCKOUT_UNTIL, 0L)
        return Pair(attempts, lockout)
    }
    
    /**
     * Save rate limit state
     * 
     * @param context Application context
     * @param failedAttempts Number of failed attempts
     * @param lockoutUntilTimestamp Timestamp when lockout expires (0 if not locked out)
     */
    fun saveRateLimitState(context: Context, failedAttempts: Int, lockoutUntilTimestamp: Long) {
        val prefs = SecurePreferences.getSystemSettings(context)
        prefs.edit()
            .putInt(PREF_FAILED_ATTEMPTS, failedAttempts)
            .putLong(PREF_LOCKOUT_UNTIL, lockoutUntilTimestamp)
            .apply()
        AdminDebugConfig.logAdmin(context, TAG, "Rate limit state saved: attempts=$failedAttempts, lockout=$lockoutUntilTimestamp")
    }
    
    /**
     * Validate PIN (for ViewModel use - returns only boolean, no context side effects)
     * 
     * @param context Application context
     * @param pin PIN to validate
     * @return true if PIN is correct, false otherwise
     */
    fun validatePin(context: Context, pin: String): Boolean {
        return verifyPin(context, pin)
    }
    
    /**
     * Generate cryptographically secure random salt (32 bytes)
     */
    private fun generateSalt(): ByteArray {
        val salt = ByteArray(32)
        SecureRandom().nextBytes(salt)
        return salt
    }
    
    /**
     * Hash PIN with salt using SHA-256
     * 
     * @param pin PIN to hash
     * @param salt Cryptographic salt
     * @return Base64-encoded hash
     */
    private fun hashPin(pin: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        
        // Add salt to digest
        digest.update(salt)
        
        // Hash PIN
        val hash = digest.digest(pin.toByteArray(Charsets.UTF_8))
        
        // Return Base64-encoded hash
        return Base64.getEncoder().encodeToString(hash)
    }
    
    /**
     * Constant-time string comparison to prevent timing attacks
     * 
     * @param a First string
     * @param b Second string
     * @return true if strings are equal
     */
    private fun constantTimeCompare(a: String, b: String): Boolean {
        if (a.length != b.length) {
            return false
        }
        
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        
        return result == 0
    }
}
