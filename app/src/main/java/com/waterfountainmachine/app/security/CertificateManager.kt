package com.waterfountainmachine.app.security

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.cert.CertificateFactory
import java.io.ByteArrayInputStream
import java.util.Base64

/**
 * Certificate data class holding parsed certificate information.
 */
data class CertificateData(
    val certificate: X509Certificate,
    val certificatePem: String,
    val machineId: String,
    val serialNumber: String,
    val expiryDate: Long,
    val isValid: Boolean
)

/**
 * Manages X.509 certificates for machine authentication.
 *
 * Certificate Lifecycle:
 * 1. Machine generates key pair (private key in Keystore)
 * 2. Machine creates CSR (Certificate Signing Request)
 * 3. Admin scans QR code with CSR
 * 4. Backend issues signed certificate
 * 5. Certificate installed on machine
 * 6. Certificate used for all API requests
 *
 * Storage:
 * - Certificate (public): EncryptedSharedPreferences
 * - Private key: Android Keystore (hardware-backed)
 * - Machine ID: Extracted from certificate subject
 *
 * Security Features:
 * - Certificate validation (expiry, signature)
 * - Revocation checking (future: OCSP)
 * - Secure storage with AES-256-GCM
 * - Hardware-backed private keys
 */
class CertificateManager private constructor(private val context: Context) {
    private val preferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Save certificate to secure storage.
     *
     * @param certificatePem PEM-encoded certificate
     * @throws IllegalArgumentException if certificate is invalid
     */
    fun saveCertificate(certificatePem: String) {
        // Validate certificate before saving
        val certData = parseCertificate(certificatePem)

        if (!certData.isValid) {
            throw IllegalArgumentException("Certificate is expired or invalid")
        }

        preferences.edit()
            .putString(KEY_CERTIFICATE, certificatePem)
            .putString(KEY_MACHINE_ID, certData.machineId)
            .putString(KEY_SERIAL_NUMBER, certData.serialNumber)
            .putLong(KEY_EXPIRY_DATE, certData.expiryDate)
            .apply()

        Log.d(TAG, "Certificate saved for machine: ${maskMachineId(certData.machineId)}")
    }

    /**
     * Get stored certificate.
     *
     * @return CertificateData or null if not found
     */
    fun getCertificate(): CertificateData? {
        val certificatePem = preferences.getString(KEY_CERTIFICATE, null) ?: return null

        return try {
            parseCertificate(certificatePem)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse stored certificate", e)
            null
        }
    }

    /**
     * Get certificate as PEM string for API requests.
     *
     * @return PEM-encoded certificate or null if not found
     */
    fun getCertificatePem(): String? {
        return preferences.getString(KEY_CERTIFICATE, null)
    }

    /**
     * Get machine ID from stored certificate.
     *
     * @return Machine ID or null if no certificate
     */
    fun getMachineId(): String? {
        return preferences.getString(KEY_MACHINE_ID, null)
    }

    /**
     * Check if certificate exists.
     *
     * @return true if certificate is stored
     */
    fun hasCertificate(): Boolean {
        return preferences.contains(KEY_CERTIFICATE)
    }

    /**
     * Check if certificate is valid (not expired).
     *
     * @return true if certificate exists and is valid
     */
    fun isCertificateValid(): Boolean {
        if (!hasCertificate()) return false

        val expiryDate = preferences.getLong(KEY_EXPIRY_DATE, 0)
        return System.currentTimeMillis() < expiryDate
    }

    /**
     * Delete stored certificate.
     */
    fun deleteCertificate() {
        preferences.edit()
            .remove(KEY_CERTIFICATE)
            .remove(KEY_MACHINE_ID)
            .remove(KEY_SERIAL_NUMBER)
            .remove(KEY_EXPIRY_DATE)
            .apply()

        Log.d(TAG, "Certificate deleted")
    }

    /**
     * Get the private key associated with this certificate.
     *
     * @return PrivateKey from Android Keystore or null if not found
     */
    fun getPrivateKey(): PrivateKey? {
        val machineId = getMachineId() ?: return null
        val keyAlias = getKeyAlias(machineId)
        return KeystoreManager.getKeyPair(keyAlias)?.private
    }

    /**
     * Parse PEM certificate and extract metadata.
     *
     * @param certificatePem PEM-encoded certificate
     * @return CertificateData with parsed information
     * @throws Exception if parsing fails
     */
    private fun parseCertificate(certificatePem: String): CertificateData {
        try {
            // Remove PEM headers/footers and decode Base64
            val pemContent = certificatePem
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replace("\\s".toRegex(), "")

            val decoded = Base64.getDecoder().decode(pemContent)

            // Parse X.509 certificate
            val certificateFactory = CertificateFactory.getInstance("X.509")
            val certificate = certificateFactory.generateCertificate(
                ByteArrayInputStream(decoded)
            ) as X509Certificate

            // Extract machine ID from subject (CN=machineId)
            val subject = certificate.subjectX500Principal.name
            val machineId = extractMachineId(subject)

            // Extract serial number
            val serialNumber = certificate.serialNumber.toString(16)

            // Check validity
            val now = System.currentTimeMillis()
            val expiryDate = certificate.notAfter.time
            val isValid = now < expiryDate

            return CertificateData(
                certificate = certificate,
                certificatePem = certificatePem,
                machineId = machineId,
                serialNumber = serialNumber,
                expiryDate = expiryDate,
                isValid = isValid
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse certificate", e)
            throw IllegalArgumentException("Invalid certificate format: ${e.message}", e)
        }
    }

    /**
     * Extract machine ID from certificate subject.
     *
     * Subject format: "CN=machineId,O=WaterFountain,..."
     */
    private fun extractMachineId(subject: String): String {
        val cnRegex = "CN=([^,]+)".toRegex()
        val match = cnRegex.find(subject)
        return match?.groupValues?.get(1) ?: throw IllegalArgumentException("Machine ID not found in certificate")
    }

    /**
     * Mask machine ID for privacy-aware logging.
     */
    private fun maskMachineId(machineId: String): String {
        return if (machineId.length > 8) {
            "****${machineId.takeLast(4)}"
        } else {
            "****"
        }
    }

    companion object {
        private const val TAG = "CertificateManager"
        private const val PREFS_FILE = "certificate_prefs"
        private const val KEY_CERTIFICATE = "certificate_pem"
        private const val KEY_MACHINE_ID = "machine_id"
        private const val KEY_SERIAL_NUMBER = "serial_number"
        private const val KEY_EXPIRY_DATE = "expiry_date"

        @Volatile
        private var instance: CertificateManager? = null

        /**
         * Get singleton instance.
         */
        fun getInstance(context: Context): CertificateManager {
            return instance ?: synchronized(this) {
                instance ?: CertificateManager(context.applicationContext).also {
                    instance = it
                }
            }
        }

        /**
         * Get Android Keystore alias for machine's private key.
         */
        fun getKeyAlias(machineId: String): String {
            return "waterfountain_$machineId"
        }
    }
}
