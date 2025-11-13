package com.waterfountainmachine.app.admin

import android.content.Context
import com.waterfountainmachine.app.utils.AppLog
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
 * 
 * Security Features:
 * - PIN never stored in plaintext
 * - Unique salt per device prevents rainbow table attacks
 * - Encrypted storage provides defense-in-depth
 * - Secure random number generation
 */
object AdminPinManager {
    
    private const val TAG = "AdminPinManager"
    private const val PREF_PIN_HASH = "admin_pin_hash"
    private const val PREF_PIN_SALT = "admin_pin_salt"
    private const val DEFAULT_PIN = "01121999" // Default PIN for initial setup
    
    /**
     * Initialize PIN system with default PIN if no PIN is set
     * Call this during app initialization
     */
    fun initialize(context: Context) {
        val prefs = SecurePreferences.getSystemSettings(context)
        
        // Check if PIN already configured
        if (!prefs.contains(PREF_PIN_HASH)) {
            AppLog.i(TAG, "No admin PIN configured, setting default PIN")
            setPin(context, DEFAULT_PIN)
        } else {
            AppLog.d(TAG, "Admin PIN already configured")
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
            AppLog.d(TAG, "Verifying PIN of length: ${pin.length}")
            
            val prefs = SecurePreferences.getSystemSettings(context)
            
            val storedHash = prefs.getString(PREF_PIN_HASH, null)
            val storedSalt = prefs.getString(PREF_PIN_SALT, null)
            
            if (storedHash == null || storedSalt == null) {
                AppLog.e(TAG, "No PIN configured in secure storage")
                return false
            }
            
            AppLog.d(TAG, "Found stored hash and salt")
            
            // Decode salt
            val salt = Base64.getDecoder().decode(storedSalt)
            
            // Hash the provided PIN with stored salt
            val providedHash = hashPin(pin, salt)
            
            AppLog.d(TAG, "Generated hash for comparison")
            
            // Constant-time comparison to prevent timing attacks
            val result = constantTimeCompare(providedHash, storedHash)
            
            if (result) {
                AppLog.i(TAG, "✅ PIN verification successful")
            } else {
                AppLog.w(TAG, "❌ PIN verification failed - incorrect PIN")
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
            
            AppLog.i(TAG, "Admin PIN updated successfully")
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
                AppLog.w(TAG, "PIN change failed: Current PIN incorrect")
                return false
            }
            
            // Prevent setting PIN to same value
            if (currentPin == newPin) {
                AppLog.w(TAG, "PIN change failed: New PIN same as current")
                return false
            }
            
            // Set new PIN
            val success = setPin(context, newPin)
            
            if (success) {
                AppLog.i(TAG, "✅ Admin PIN changed successfully")
                
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
