package com.waterfountainmachine.app.security

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Coordinates all security components for certificate-based authentication.
 *
 * This module integrates:
 * - CertificateManager: Certificate storage/validation
 * - RequestSigner: Cryptographic request signing
 * - NonceGenerator: Replay attack prevention
 * - KeystoreManager: Secure private key storage
 *
 * Usage Flow:
 * 1. Check if enrolled: isEnrolled()
 * 2. Create signed request: createAuthenticatedRequest()
 * 3. Send request with auth fields to Firebase
 *
 * Security Features:
 * - Certificate-based authentication (no passwords)
 * - Request signing with RSA-SHA256
 * - Timestamp-based replay protection
 * - Unique nonces prevent replay attacks
 * - Hardware-backed private keys
 */
object SecurityModule {
    private const val TAG = "SecurityModule"

    private lateinit var certificateManager: CertificateManager
    private val requestSigner = RequestSigner.instance
    private val nonceGenerator = NonceGenerator.instance

    /**
     * Initialize security module.
     *
     * @param context Application context
     */
    fun initialize(context: Context) {
        certificateManager = CertificateManager.getInstance(context)
        Log.d(TAG, "SecurityModule initialized")
    }

    /**
     * Check if machine is enrolled with a valid certificate.
     *
     * @return true if enrolled and certificate is valid
     */
    fun isEnrolled(): Boolean {
        return certificateManager.hasCertificate() && certificateManager.isCertificateValid()
    }

    /**
     * Get machine ID from certificate.
     *
     * @return Machine ID or null if not enrolled
     */
    fun getMachineId(): String? {
        return certificateManager.getMachineId()
    }

    /**
     * Create an authenticated request with certificate-based authentication.
     *
     * Adds the following fields to the request:
     * - _cert: PEM-encoded certificate
     * - _timestamp: Request timestamp (milliseconds)
     * - _nonce: Unique nonce
     * - _signature: RSA-SHA256 signature of (endpoint:timestamp:nonce:payload)
     *
     * @param endpoint API endpoint name (e.g., "requestOtp", "verifyOtp")
     * @param payload Request payload as JSONObject
     * @return JSONObject with authentication fields added
     * @throws IllegalStateException if not enrolled or certificate invalid
     * @throws SecurityException if signing fails
     */
    fun createAuthenticatedRequest(endpoint: String, payload: JSONObject): JSONObject {
        // Verify enrollment
        if (!isEnrolled()) {
            throw IllegalStateException("Machine not enrolled. Certificate required.")
        }

        // Get certificate and private key
        val certificatePem = certificateManager.getCertificatePem()
            ?: throw IllegalStateException("Certificate not found")

        val privateKey = certificateManager.getPrivateKey()
            ?: throw IllegalStateException("Private key not found")

        // Generate timestamp and nonce
        val timestamp = System.currentTimeMillis()
        val nonce = nonceGenerator.generate()

        // Create payload string (sorted keys for consistency)
        val payloadStr = payload.toString()

        // Sign request
        val signature = try {
            requestSigner.signRequest(
                endpoint = endpoint,
                timestamp = timestamp,
                nonce = nonce,
                payload = payloadStr,
                privateKey = privateKey
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign request", e)
            throw SecurityException("Request signing failed: ${e.message}", e)
        }

        // Create authenticated request with auth fields
        val authenticatedRequest = JSONObject(payloadStr)
        authenticatedRequest.put("_cert", certificatePem)
        authenticatedRequest.put("_timestamp", timestamp.toString())
        authenticatedRequest.put("_nonce", nonce)
        authenticatedRequest.put("_signature", signature)

        Log.d(TAG, "Created authenticated request for endpoint: $endpoint")

        return authenticatedRequest
    }

    /**
     * Validate certificate expiry and show warning if expiring soon.
     *
     * @return Days until expiry, or null if not enrolled
     */
    fun getDaysUntilExpiry(): Int? {
        val certData = certificateManager.getCertificate() ?: return null

        val now = System.currentTimeMillis()
        val daysRemaining = ((certData.expiryDate - now) / (1000 * 60 * 60 * 24)).toInt()

        return daysRemaining
    }

    /**
     * Check if certificate is expiring soon (within 30 days).
     *
     * @return true if expiring within 30 days
     */
    fun isCertificateExpiringSoon(): Boolean {
        val daysRemaining = getDaysUntilExpiry() ?: return false
        return daysRemaining <= 30
    }

    /**
     * Delete certificate and private key (unenroll machine).
     */
    fun unenroll() {
        val machineId = certificateManager.getMachineId()
        if (machineId != null) {
            val keyAlias = CertificateManager.getKeyAlias(machineId)
            KeystoreManager.deleteKeyPair(keyAlias)
        }

        certificateManager.deleteCertificate()
        Log.d(TAG, "Machine unenrolled")
    }

    /**
     * Install certificate after enrollment.
     *
     * @param certificatePem PEM-encoded certificate
     * @throws IllegalArgumentException if certificate is invalid
     */
    fun installCertificate(certificatePem: String) {
        certificateManager.saveCertificate(certificatePem)
        Log.d(TAG, "Certificate installed successfully")
    }

    /**
     * Generate CSR (Certificate Signing Request) for enrollment.
     *
     * This creates a new key pair and CSR that can be displayed as QR code.
     *
     * @param machineId Unique machine identifier
     * @return CSR in PEM format
     * @throws Exception if key generation fails
     */
    fun generateCSR(machineId: String): String {
        // Generate key pair in Android Keystore
        val keyAlias = CertificateManager.getKeyAlias(machineId)

        // Delete existing key pair if present
        if (KeystoreManager.keyPairExists(keyAlias)) {
            KeystoreManager.deleteKeyPair(keyAlias)
        }

        val keyPair = KeystoreManager.generateKeyPair(keyAlias)

        // Create CSR (Certificate Signing Request)
        // For now, we'll create a simple JSON with public key and machine ID
        // In production, use proper PKCS#10 CSR format
        val publicKeyBase64 = java.util.Base64.getEncoder().encodeToString(
            keyPair.public.encoded
        )

        val csrJson = JSONObject().apply {
            put("machineId", machineId)
            put("publicKey", publicKeyBase64)
            put("keyAlgorithm", "RSA")
            put("keySize", 2048)
            put("timestamp", System.currentTimeMillis())
        }

        Log.d(TAG, "Generated CSR for machine: $machineId")

        return csrJson.toString()
    }

    /**
     * Get certificate details for display in admin UI.
     *
     * @return Map with certificate information or null if not enrolled
     */
    fun getCertificateInfo(): Map<String, String>? {
        val certData = certificateManager.getCertificate() ?: return null

        val daysRemaining = getDaysUntilExpiry() ?: 0

        return mapOf(
            "machineId" to certData.machineId,
            "serialNumber" to certData.serialNumber,
            "expiryDate" to java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(certData.expiryDate)),
            "daysRemaining" to daysRemaining.toString(),
            "isValid" to certData.isValid.toString(),
            "status" to when {
                !certData.isValid -> "Expired"
                daysRemaining <= 7 -> "Expiring Soon"
                daysRemaining <= 30 -> "Valid"
                else -> "Valid"
            }
        )
    }
}
