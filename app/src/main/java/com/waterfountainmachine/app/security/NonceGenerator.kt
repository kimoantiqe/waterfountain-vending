package com.waterfountainmachine.app.security

import java.security.SecureRandom
import java.util.Base64

/**
 * Generates cryptographically secure nonces for replay attack prevention.
 *
 * Each nonce is a unique, random value that is used exactly once per request.
 * The backend tracks used nonces to prevent replay attacks.
 *
 * Implementation:
 * - Uses SecureRandom for cryptographic randomness
 * - Generates 32-byte (256-bit) random values
 * - Base64-encoded for network transmission
 * - Thread-safe singleton instance
 *
 * Security Notes:
 * - Nonces MUST be unique per request
 * - Backend rejects reused nonces
 * - Combined with timestamp for replay protection
 */
class NonceGenerator {
    private val secureRandom = SecureRandom()

    /**
     * Generate a new cryptographically secure nonce.
     *
     * @return URL-safe Base64-encoded random nonce (256 bits)
     */
    fun generate(): String {
        val bytes = ByteArray(NONCE_SIZE_BYTES)
        secureRandom.nextBytes(bytes)
        // Use URL-safe encoding to avoid '/' and '+' characters which are invalid in Firestore document IDs
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private const val NONCE_SIZE_BYTES = 32 // 256 bits

        /**
         * Singleton instance for global access.
         */
        val instance: NonceGenerator by lazy { NonceGenerator() }
    }
}
