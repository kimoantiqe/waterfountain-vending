package com.waterfountainmachine.app.features.admin.utils
import android.content.Context
import com.waterfountainmachine.app.core.utils.AppLog
import com.waterfountainmachine.app.core.utils.AdminDebugConfig
import com.waterfountainmachine.app.core.utils.SecurePreferences
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Secure Admin PIN Manager
 *
 * Stores PINs as PBKDF2-HMAC-SHA256 hashes (200k iterations, 256-bit key,
 * 32-byte random salt) in EncryptedSharedPreferences. Replaces an earlier
 * single-round salted SHA-256 scheme; legacy hashes are detected on
 * [verifyPin] and **transparently upgraded** to PBKDF2 the first time the
 * operator enters the correct PIN, so no fleet-wide reset is needed.
 *
 * Security Features:
 * - PBKDF2-HMAC-SHA256 with 200,000 iterations (~100 ms on the kiosk
 *   hardware; raises offline brute-force cost to ~days per device on GPU).
 * - 32-byte cryptographically-random salt per PIN write.
 * - EncryptedSharedPreferences (AES-256-GCM) as defense-in-depth.
 * - Constant-time hash comparison to prevent timing attacks.
 * - 3 attempts lockout for 1 hour (state stored in encrypted prefs).
 */
object AdminPinManager {

    private const val TAG = "AdminPinManager"
    private const val PREF_PIN_HASH = "admin_pin_hash"
    private const val PREF_PIN_SALT = "admin_pin_salt"
    private const val PREF_PIN_ALGO = "admin_pin_algo"
    private const val PREF_FAILED_ATTEMPTS = "admin_failed_attempts"
    private const val PREF_LOCKOUT_UNTIL = "admin_lockout_until"

    /**
     * Current hash algorithm tag. Bumped when the KDF parameters change so
     * the migration path in [verifyPin] knows which scheme produced a stored
     * hash. Absence of [PREF_PIN_ALGO] => legacy salted SHA-256.
     */
    internal const val ALGO_PBKDF2_V1 = "pbkdf2-hmac-sha256-200k-v1"
    private const val PBKDF2_ITERATIONS = 200_000
    private const val PBKDF2_KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 32

    /**
     * Bootstrap PIN. This value is intentionally cleartext in source. Its only
     * job is to let an operator open the admin panel ONCE on a fresh install /
     * factory reset, after which the app forces a mandatory PIN rotation
     * (see [AdminAuthActivity]). Hashing it here would not add security since
     * the value is in the APK either way.
     *
     * If you change this, mirror the change to fleet rollout docs.
     */
    private const val DEFAULT_PIN = "01121999"

    /**
     * Initialize PIN system with default PIN if no PIN is set.
     * Call this during app initialization.
     *
     * The default PIN is only valid until an operator changes it; admin login
     * with the default PIN forces a mandatory rotation flow before any other
     * admin action is permitted.
     */
    fun initialize(context: Context) {
        val prefs = SecurePreferences.getSystemSettings(context)
        if (!prefs.contains(PREF_PIN_HASH)) {
            AdminDebugConfig.logAdminInfo(context, TAG, "No admin PIN configured, setting default PIN")
            setPin(context, DEFAULT_PIN)
        } else {
            AdminDebugConfig.logAdmin(context, TAG, "Admin PIN already configured")
        }
    }
    
    /**
     * Verify if provided PIN matches stored hash.
     *
     * If the stored hash uses the legacy salted SHA-256 scheme and the PIN
     * matches, the hash is **transparently re-written** with PBKDF2 so the
     * next verify uses the stronger KDF. Failed verifies do NOT trigger any
     * write.
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
            val storedAlgo = prefs.getString(PREF_PIN_ALGO, null)

            if (storedHash == null || storedSalt == null) {
                AppLog.e(TAG, "No PIN configured in secure storage")
                return false
            }

            AdminDebugConfig.logAdmin(context, TAG, "Found stored hash and salt (algo=${storedAlgo ?: "legacy-sha256"})")

            val salt = Base64.getDecoder().decode(storedSalt)
            val isLegacy = storedAlgo != ALGO_PBKDF2_V1
            val providedHash = if (isLegacy) hashPinLegacy(pin, salt) else hashPinPbkdf2(pin, salt)
            val result = constantTimeCompare(providedHash, storedHash)

            if (result) {
                AdminDebugConfig.logAdminInfo(context, TAG, "✅ PIN verification successful")
                if (isLegacy) {
                    // Transparent upgrade: re-hash the now-known-good PIN with
                    // PBKDF2 so future verifies use the stronger scheme.
                    AdminDebugConfig.logAdminInfo(
                        context,
                        TAG,
                        "Upgrading legacy SHA-256 hash to $ALGO_PBKDF2_V1"
                    )
                    setPin(context, pin)
                }
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

            // Hash the PIN with PBKDF2-HMAC-SHA256
            val hash = hashPinPbkdf2(newPin, salt)

            // Store hash, salt, and algo tag in encrypted preferences
            val prefs = SecurePreferences.getSystemSettings(context)
            prefs.edit()
                .putString(PREF_PIN_HASH, hash)
                .putString(PREF_PIN_SALT, Base64.getEncoder().encodeToString(salt))
                .putString(PREF_PIN_ALGO, ALGO_PBKDF2_V1)
                .apply()

            AdminDebugConfig.logAdminInfo(context, TAG, "Admin PIN updated successfully (algo=$ALGO_PBKDF2_V1)")
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
     * Check whether the currently stored PIN still matches the bootstrap
     * [DEFAULT_PIN]. Used by [AdminAuthActivity] to gate the admin panel
     * behind a mandatory PIN rotation on first login.
     */
    fun isDefaultPin(context: Context): Boolean {
        return verifyPin(context, DEFAULT_PIN)
    }

    /**
     * Check whether a candidate PIN equals the bootstrap [DEFAULT_PIN].
     * Used by the forced-rotation flow to reject "rotate to the same default".
     */
    fun isDefaultPinValue(pin: String): Boolean = pin == DEFAULT_PIN
    
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
     * Generate cryptographically secure random salt.
     */
    private fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(salt)
        return salt
    }

    /**
     * Hash PIN with PBKDF2-HMAC-SHA256, [PBKDF2_ITERATIONS] iterations, 256-bit key.
     *
     * @return Base64-encoded derived key.
     */
    internal fun hashPinPbkdf2(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH_BITS)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val derived = factory.generateSecret(spec).encoded
            return Base64.getEncoder().encodeToString(derived)
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Legacy salted single-round SHA-256 hash. Retained ONLY so [verifyPin] can
     * recognise hashes written by older builds and trigger transparent
     * migration to PBKDF2. Never used to produce new hashes.
     */
    internal fun hashPinLegacy(pin: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        val hash = digest.digest(pin.toByteArray(Charsets.UTF_8))
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
