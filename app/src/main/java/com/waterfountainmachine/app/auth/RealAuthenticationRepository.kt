package com.waterfountainmachine.app.auth

import com.waterfountainmachine.app.security.CertificateManager
import com.waterfountainmachine.app.security.NonceGenerator
import com.waterfountainmachine.app.security.RequestSigner
import com.waterfountainmachine.app.utils.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Real implementation of IAuthenticationRepository.
 * Connects to Firebase Cloud Functions with certificate-based authentication.
 * 
 * Security Features:
 * - Client certificate authentication
 * - Request signing with private key
 * - Nonce-based replay attack prevention
 * - TLS 1.3 encryption
 * 
 * @param baseUrl Base URL for Firebase Cloud Functions (e.g., "https://us-central1-waterfountain-dev.cloudfunctions.net")
 * @param certificateManager Manages machine certificate and private key
 * @param requestSigner Signs requests with machine's private key
 * @param nonceGenerator Generates unique nonces for replay protection
 */
class RealAuthenticationRepository(
    private val baseUrl: String,
    private val certificateManager: CertificateManager,
    private val requestSigner: RequestSigner,
    private val nonceGenerator: NonceGenerator
) : IAuthenticationRepository {
    
    companion object {
        private const val TAG = "RealAuthRepository"
        
        // API endpoints
        private const val REQUEST_OTP_ENDPOINT = "requestOtpFn"
        private const val VERIFY_OTP_ENDPOINT = "verifyOtpFn"
        
        // Timeouts
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }
    
    override suspend fun requestOtp(phone: String): Result<OtpRequestResponse> = withContext(Dispatchers.IO) {
        try {
            AppLog.d(TAG, "Requesting OTP for phone: ${maskPhone(phone)}")
            
            // Validate certificate
            if (!certificateManager.hasCertificate()) {
                AppLog.e(TAG, "No certificate available")
                return@withContext Result.failure(
                    AuthenticationException.CertificateError("Machine not enrolled. Contact administrator.")
                )
            }
            
            if (certificateManager.isCertificateExpired()) {
                AppLog.e(TAG, "Certificate expired")
                return@withContext Result.failure(
                    AuthenticationException.CertificateError("Certificate expired. Re-enrollment required.")
                )
            }
            
            // Prepare request payload
            val timestamp = System.currentTimeMillis().toString()
            val nonce = nonceGenerator.generate()
            val certificate = certificateManager.getCertificate()!!
            
            val payload = JSONObject().apply {
                put("phone", phone)
            }
            
            // Sign the request
            val signature = requestSigner.signRequest(
                endpoint = REQUEST_OTP_ENDPOINT,
                timestamp = timestamp,
                nonce = nonce,
                payload = payload.toString()
            )
            
            // Add authentication fields
            val requestBody = JSONObject().apply {
                put("phone", phone)
                put("_cert", certificate)
                put("_timestamp", timestamp)
                put("_nonce", nonce)
                put("_signature", signature)
            }
            
            // Make HTTP request
            val response = makeHttpRequest(REQUEST_OTP_ENDPOINT, requestBody)
            
            AppLog.i(TAG, "OTP request successful")
            Result.success(
                OtpRequestResponse(
                    success = true,
                    message = response.optString("message", "OTP sent successfully")
                )
            )
        } catch (e: AuthenticationException) {
            AppLog.e(TAG, "Authentication error: ${e.message}", e)
            Result.failure(e)
        } catch (e: Exception) {
            AppLog.e(TAG, "Unexpected error requesting OTP", e)
            Result.failure(
                AuthenticationException.NetworkError("Network error: ${e.message}")
            )
        }
    }
    
    override suspend fun verifyOtp(phone: String, otp: String): Result<OtpVerifyResponse> = withContext(Dispatchers.IO) {
        try {
            AppLog.d(TAG, "Verifying OTP for phone: ${maskPhone(phone)}")
            
            // Validate certificate
            if (!certificateManager.hasCertificate()) {
                AppLog.e(TAG, "No certificate available")
                return@withContext Result.failure(
                    AuthenticationException.CertificateError("Machine not enrolled. Contact administrator.")
                )
            }
            
            if (certificateManager.isCertificateExpired()) {
                AppLog.e(TAG, "Certificate expired")
                return@withContext Result.failure(
                    AuthenticationException.CertificateError("Certificate expired. Re-enrollment required.")
                )
            }
            
            // Prepare request payload
            val timestamp = System.currentTimeMillis().toString()
            val nonce = nonceGenerator.generate()
            val certificate = certificateManager.getCertificate()!!
            
            val payload = JSONObject().apply {
                put("phone", phone)
                put("otp", otp)
            }
            
            // Sign the request
            val signature = requestSigner.signRequest(
                endpoint = VERIFY_OTP_ENDPOINT,
                timestamp = timestamp,
                nonce = nonce,
                payload = payload.toString()
            )
            
            // Add authentication fields
            val requestBody = JSONObject().apply {
                put("phone", phone)
                put("otp", otp)
                put("_cert", certificate)
                put("_timestamp", timestamp)
                put("_nonce", nonce)
                put("_signature", signature)
            }
            
            // Make HTTP request
            val response = makeHttpRequest(VERIFY_OTP_ENDPOINT, requestBody)
            
            AppLog.i(TAG, "OTP verification successful")
            Result.success(
                OtpVerifyResponse(
                    success = true,
                    message = response.optString("message", "OTP verified successfully")
                )
            )
        } catch (e: AuthenticationException) {
            AppLog.e(TAG, "Authentication error: ${e.message}", e)
            Result.failure(e)
        } catch (e: Exception) {
            AppLog.e(TAG, "Unexpected error verifying OTP", e)
            Result.failure(
                AuthenticationException.NetworkError("Network error: ${e.message}")
            )
        }
    }
    
    /**
     * Make HTTPS request to Firebase Cloud Function
     */
    private fun makeHttpRequest(endpoint: String, body: JSONObject): JSONObject {
        val url = URL("$baseUrl/$endpoint")
        val connection = url.openConnection() as HttpsURLConnection
        
        try {
            // Configure connection
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.doInput = true
            
            // Write request body
            val writer = OutputStreamWriter(connection.outputStream, "UTF-8")
            writer.write(body.toString())
            writer.flush()
            writer.close()
            
            // Read response
            val responseCode = connection.responseCode
            AppLog.d(TAG, "Response code: $responseCode")
            
            if (responseCode in 200..299) {
                // Success
                val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
                val response = reader.readText()
                reader.close()
                
                AppLog.d(TAG, "Response received: ${response.take(100)}...")
                return JSONObject(response)
            } else {
                // Error
                val errorReader = BufferedReader(InputStreamReader(connection.errorStream ?: connection.inputStream, "UTF-8"))
                val errorResponse = errorReader.readText()
                errorReader.close()
                
                AppLog.e(TAG, "Error response: $errorResponse")
                
                // Parse error
                throw parseErrorResponse(responseCode, errorResponse)
            }
        } finally {
            connection.disconnect()
        }
    }
    
    /**
     * Parse error response and throw appropriate exception
     */
    private fun parseErrorResponse(code: Int, body: String): AuthenticationException {
        return try {
            val json = JSONObject(body)
            val errorMessage = json.optJSONObject("error")?.optString("message") 
                ?: json.optString("message", "Unknown error")
            
            when {
                code == 403 -> AuthenticationException.CertificateError(errorMessage)
                code == 429 -> AuthenticationException.RateLimitError(errorMessage)
                code in 400..499 -> AuthenticationException.InvalidOtpError(errorMessage)
                code in 500..599 -> AuthenticationException.ServerError(errorMessage)
                else -> AuthenticationException.NetworkError("HTTP $code: $errorMessage")
            }
        } catch (e: Exception) {
            AuthenticationException.ServerError("HTTP $code: Failed to parse error response")
        }
    }
    
    /**
     * Mask phone number for logging (privacy)
     */
    private fun maskPhone(phone: String): String {
        if (phone.length < 4) return "***"
        val country = phone.substring(0, 2)
        val last4 = phone.takeLast(4)
        return "$country***-***-$last4"
    }
}
