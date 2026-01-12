package com.waterfountainmachine.app.auth

import com.google.firebase.Firebase
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.functions
import com.waterfountainmachine.app.security.CertificateManager
import com.waterfountainmachine.app.security.NonceGenerator
import com.waterfountainmachine.app.security.RequestSigner
import com.waterfountainmachine.app.utils.AppLog
import kotlinx.coroutines.tasks.await

/**
 * Real implementation of IAuthenticationRepository.
 * Uses Firebase Functions SDK with certificate-based authentication.
 * 
 * Security Features:
 * - Firebase App Check (automatic via SDK)
 * - Client certificate authentication
 * - Request signing with private key
 * - Nonce-based replay attack prevention
 * 
 * @param certificateManager Manages machine certificate and private key
 * @param requestSigner Signs requests with machine's private key
 * @param nonceGenerator Generates unique nonces for replay protection
 */
class RealAuthenticationRepository(
    private val certificateManager: CertificateManager,
    private val requestSigner: RequestSigner,
    private val nonceGenerator: NonceGenerator
) : IAuthenticationRepository {
    
    companion object {
        private const val TAG = "RealAuthRepository"
        
        // API endpoints
        private const val REQUEST_OTP_ENDPOINT = "requestOtpFn"
        private const val VERIFY_OTP_ENDPOINT = "verifyOtpFn"
        
        // Timeout configuration (30 seconds to prevent indefinite hangs)
        private const val FUNCTION_TIMEOUT_MS = 30_000L
    }
    
    private val functions: FirebaseFunctions = Firebase.functions
    
    override suspend fun requestOtp(phone: String): Result<OtpRequestResponse> {
        return try {
            AppLog.d(TAG, "Requesting OTP for phone: ${maskPhone(phone)}")
            
            // Validate certificate
            if (!certificateManager.hasCertificate()) {
                AppLog.e(TAG, "No certificate available")
                return Result.failure(
                    AuthenticationException.CertificateError("Machine not enrolled. Contact administrator.")
                )
            }
            
            if (certificateManager.isCertificateExpired()) {
                AppLog.e(TAG, "Certificate expired")
                return Result.failure(
                    AuthenticationException.CertificateError("Certificate expired. Re-enrollment required.")
                )
            }
            
            // Prepare authenticated request
            val authenticatedData = buildAuthenticatedRequest(
                endpoint = REQUEST_OTP_ENDPOINT,
                data = mapOf("phone" to phone)
            )
            
            // Call Firebase Function with 30-second timeout
            val result = functions
                .getHttpsCallable(REQUEST_OTP_ENDPOINT)
                .withTimeout(FUNCTION_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                .call(authenticatedData)
                .await()
            
            // Extract response data
            val responseData = result.data as? Map<*, *>
            val success = responseData?.get("success") as? Boolean ?: false
            val message = responseData?.get("message") as? String ?: "OTP sent"
            
            if (!success) {
                AppLog.w(TAG, "OTP request returned success=false: $message")
                return Result.failure(
                    AuthenticationException.NetworkError("Failed to send OTP: $message")
                )
            }
            
            AppLog.i(TAG, "OTP request successful: $message")
            Result.success(
                OtpRequestResponse(
                    success = true,
                    message = message
                )
            )
        } catch (e: FirebaseFunctionsException) {
            AppLog.e(TAG, "Firebase Functions error: ${e.code} - ${e.message}", e)
            Result.failure(parseFirebaseException(e))
        } catch (e: Exception) {
            AppLog.e(TAG, "Unexpected error requesting OTP", e)
            Result.failure(
                AuthenticationException.NetworkError("Network error: ${e.message}")
            )
        }
    }
    
    override suspend fun verifyOtp(phone: String, otp: String): Result<OtpVerifyResponse> {
        return try {
            AppLog.d(TAG, "Verifying OTP for phone: ${maskPhone(phone)}")
            
            // Validate certificate
            if (!certificateManager.hasCertificate()) {
                AppLog.e(TAG, "No certificate available")
                return Result.failure(
                    AuthenticationException.CertificateError("Machine not enrolled. Contact administrator.")
                )
            }
            
            if (certificateManager.isCertificateExpired()) {
                AppLog.e(TAG, "Certificate expired")
                return Result.failure(
                    AuthenticationException.CertificateError("Certificate expired. Re-enrollment required.")
                )
            }
            
            // Prepare authenticated request
            val authenticatedData = buildAuthenticatedRequest(
                endpoint = VERIFY_OTP_ENDPOINT,
                data = mapOf(
                    "phone" to phone,
                    "otp" to otp
                )
            )
            
            // Call Firebase Function with 30-second timeout
            val result = functions
                .getHttpsCallable(VERIFY_OTP_ENDPOINT)
                .withTimeout(FUNCTION_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                .call(authenticatedData)
                .await()
            
            // Extract response data
            val responseData = result.data as? Map<*, *>
            val success = responseData?.get("success") as? Boolean ?: false
            val message = responseData?.get("message") as? String ?: "OTP verified"
            val sessionToken = responseData?.get("sessionToken") as? String
            val dailyVendLimit = (responseData?.get("dailyVendLimit") as? Number)?.toInt() ?: 2
            val vendsUsedToday = (responseData?.get("vendsUsedToday") as? Number)?.toInt() ?: 0
            val vendsRemainingToday = (responseData?.get("vendsRemainingToday") as? Number)?.toInt() ?: dailyVendLimit
            
            if (!success) {
                AppLog.w(TAG, "OTP verification returned success=false: $message")
                return Result.failure(
                    AuthenticationException.InvalidOtpError("Invalid OTP: $message")
                )
            }
            
            AppLog.i(TAG, "OTP verification successful: $message (Limit: $dailyVendLimit, Used: $vendsUsedToday, Remaining: $vendsRemainingToday)")
            Result.success(
                OtpVerifyResponse(
                    success = true,
                    message = message,
                    sessionToken = sessionToken,
                    dailyVendLimit = dailyVendLimit,
                    vendsUsedToday = vendsUsedToday,
                    vendsRemainingToday = vendsRemainingToday
                )
            )
        } catch (e: FirebaseFunctionsException) {
            AppLog.e(TAG, "Firebase Functions error: ${e.code} - ${e.message}", e)
            Result.failure(parseFirebaseException(e))
        } catch (e: Exception) {
            AppLog.e(TAG, "Unexpected error verifying OTP", e)
            Result.failure(
                AuthenticationException.NetworkError("Network error: ${e.message}")
            )
        }
    }
    
    /**
     * Build authenticated request with certificate authentication fields
     */
    private fun buildAuthenticatedRequest(
        endpoint: String,
        data: Map<String, Any>
    ): Map<String, Any> {
        val timestamp = System.currentTimeMillis()
        val nonce = nonceGenerator.generate()
        
        // Safe null checks - throw specific error if certificate/key missing
        val certificate = certificateManager.getCertificatePem()
            ?: throw AuthenticationException.CertificateError("Certificate not found. Machine must be enrolled.")
        val privateKey = certificateManager.getPrivateKey()
            ?: throw AuthenticationException.CertificateError("Private key not found. Re-enrollment required.")
        
        // Create payload JSON string for signing
        val payloadJson = data.entries.joinToString(",", "{", "}") { (key, value) ->
            "\"$key\":\"$value\""
        }
        
        // Sign the request
        val signature = requestSigner.signRequest(
            endpoint = endpoint,
            timestamp = timestamp,
            nonce = nonce,
            payload = payloadJson,
            privateKey = privateKey
        )
        
        // Return map with data + authentication fields
        return data + mapOf(
            "_cert" to certificate,
            "_timestamp" to timestamp.toString(),
            "_nonce" to nonce,
            "_signature" to signature
        )
    }
    
    /**
     * Parse Firebase Functions exception to domain exception
     */
    private fun parseFirebaseException(e: FirebaseFunctionsException): AuthenticationException {
        return when (e.code) {
            FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                AuthenticationException.CertificateError("Authentication failed: ${e.message}")
            
            FirebaseFunctionsException.Code.PERMISSION_DENIED ->
                AuthenticationException.CertificateError("Permission denied: ${e.message}")
            
            FirebaseFunctionsException.Code.NOT_FOUND ->
                AuthenticationException.InvalidOtpError("OTP not found or expired: ${e.message}")
            
            FirebaseFunctionsException.Code.ALREADY_EXISTS ->
                AuthenticationException.InvalidOtpError("OTP already used: ${e.message}")
            
            FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> {
                // Check if this is daily limit vs rate limit
                if (e.message?.contains("daily", ignoreCase = true) == true) {
                    AuthenticationException.DailyLimitError(e.message ?: "Daily vending limit reached")
                } else {
                    AuthenticationException.RateLimitError(e.message ?: "Rate limit exceeded")
                }
            }
            
            FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ->
                AuthenticationException.InvalidOtpError("Request timeout: ${e.message}")
            
            FirebaseFunctionsException.Code.INVALID_ARGUMENT ->
                AuthenticationException.InvalidOtpError("Invalid request: ${e.message}")
            
            FirebaseFunctionsException.Code.INTERNAL,
            FirebaseFunctionsException.Code.UNAVAILABLE,
            FirebaseFunctionsException.Code.UNKNOWN ->
                AuthenticationException.ServerError("Server error: ${e.message}")
            
            else ->
                AuthenticationException.NetworkError("Unexpected error: ${e.message}")
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
