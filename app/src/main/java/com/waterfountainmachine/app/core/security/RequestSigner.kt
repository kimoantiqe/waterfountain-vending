package com.waterfountainmachine.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.*
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Signs requests using RSA-SHA256 digital signatures.
 *
 * Request Signing Flow:
 * 1. Create canonical string: "endpoint:timestamp:nonce:payload"
 * 2. Sign with private key (RSA-SHA256)
 * 3. Base64-encode signature
 * 4. Backend verifies with certificate's public key
 *
 * Key Management:
 * - Private key stored in Android Keystore (hardware-backed when available)
 * - Public key embedded in X.509 certificate
 * - Keys generated during certificate enrollment
 *
 * Security Features:
 * - Hardware-backed keys prevent extraction
 * - RSA 2048-bit minimum
 * - SHA-256 hashing
 * - Signature covers all request data
 */
class RequestSigner {
    /**
     * Sign request data for certificate-based authentication.
     *
     * Creates a signature over the canonical request string:
     * endpoint:timestamp:nonce:payload
     *
     * @param endpoint API endpoint name (e.g., "requestOtp", "verifyOtp")
     * @param timestamp Unix timestamp in milliseconds
     * @param nonce Unique nonce from NonceGenerator
     * @param payload JSON string of request payload
     * @param privateKey RSA private key for signing
     * @return Base64-encoded signature
     * @throws SignatureException if signing fails
     */
    fun signRequest(
        endpoint: String,
        timestamp: Long,
        nonce: String,
        payload: String,
        privateKey: PrivateKey
    ): String {
        try {
            // Create canonical request string (matches backend format)
            val dataToSign = "$endpoint:$timestamp:$nonce:$payload"

            Log.d(TAG, "Signing request for endpoint: $endpoint")

            // Sign with RSA-SHA256
            val signature = Signature.getInstance(SIGNATURE_ALGORITHM).apply {
                initSign(privateKey)
                update(dataToSign.toByteArray(Charsets.UTF_8))
            }

            val signatureBytes = signature.sign()

            // Base64-encode for network transmission
            return Base64.getEncoder().encodeToString(signatureBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign request", e)
            throw SignatureException("Request signing failed: ${e.message}", e)
        }
    }

    /**
     * Verify a signature (used for testing).
     *
     * @param data Original data that was signed
     * @param signatureBase64 Base64-encoded signature
     * @param publicKey Public key for verification
     * @return true if signature is valid
     */
    fun verifySignature(data: String, signatureBase64: String, publicKey: PublicKey): Boolean {
        return try {
            val signatureBytes = Base64.getDecoder().decode(signatureBase64)

            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM).apply {
                initVerify(publicKey)
                update(data.toByteArray(Charsets.UTF_8))
            }

            verifier.verify(signatureBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Signature verification failed", e)
            false
        }
    }

    companion object {
        private const val TAG = "RequestSigner"
        private const val SIGNATURE_ALGORITHM = "SHA256withRSA"

        /**
         * Singleton instance for global access.
         */
        val instance: RequestSigner by lazy { RequestSigner() }
    }
}

/**
 * Manages Android Keystore operations for secure key storage.
 */
object KeystoreManager {
    private const val TAG = "KeystoreManager"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALGORITHM = KeyProperties.KEY_ALGORITHM_RSA
    private const val KEY_SIZE = 2048
    private const val SIGNATURE_ALGORITHM = "SHA256withRSA"

    /**
     * Generate a new RSA key pair in the Android Keystore.
     *
     * @param alias Unique identifier for the key pair
     * @return Generated KeyPair
     * @throws Exception if key generation fails
     */
    fun generateKeyPair(alias: String): KeyPair {
        try {
            val keyPairGenerator = KeyPairGenerator.getInstance(
                KEY_ALGORITHM,
                ANDROID_KEYSTORE
            )

            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            ).apply {
                setKeySize(KEY_SIZE)
                setDigests(KeyProperties.DIGEST_SHA256)
                setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                // Enable hardware-backed storage if available
                setUserAuthenticationRequired(false) // No biometric required for signing
            }.build()

            keyPairGenerator.initialize(spec)
            val keyPair = keyPairGenerator.generateKeyPair()

            Log.d(TAG, "Generated key pair with alias: $alias")
            return keyPair
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate key pair", e)
            throw Exception("Key pair generation failed: ${e.message}", e)
        }
    }

    /**
     * Get an existing key pair from the Android Keystore.
     *
     * @param alias Key pair identifier
     * @return KeyPair or null if not found
     */
    fun getKeyPair(alias: String): KeyPair? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
            }

            val privateKey = keyStore.getKey(alias, null) as? PrivateKey
            val certificate = keyStore.getCertificate(alias)

            if (privateKey != null && certificate != null) {
                KeyPair(certificate.publicKey, privateKey)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get key pair", e)
            null
        }
    }

    /**
     * Delete a key pair from the Android Keystore.
     *
     * @param alias Key pair identifier
     */
    fun deleteKeyPair(alias: String) {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
            }
            keyStore.deleteEntry(alias)
            Log.d(TAG, "Deleted key pair: $alias")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete key pair", e)
        }
    }

    /**
     * Check if a key pair exists in the Android Keystore.
     *
     * @param alias Key pair identifier
     * @return true if key pair exists
     */
    fun keyPairExists(alias: String): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
            }
            keyStore.containsAlias(alias)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check key pair existence", e)
            false
        }
    }
}
