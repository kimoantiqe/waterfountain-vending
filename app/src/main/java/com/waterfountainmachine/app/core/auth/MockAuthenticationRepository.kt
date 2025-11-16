package com.waterfountainmachine.app.auth

import com.waterfountainmachine.app.utils.AppLog
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Mock implementation of IAuthenticationRepository for testing and development.
 * 
 * This implementation simulates SMS OTP authentication without making real API calls.
 * No actual SMS messages are sent, and the OTP code is hardcoded for testing.
 * 
 * Features:
 * - Simulates network latency
 * - Configurable success/failure rate for testing error scenarios
 * - No SMS costs
 * - No certificate required
 * - Offline testing capability
 * 
 * @param simulateNetworkDelay If true, adds artificial delay to simulate network latency
 * @param successRate Probability of success (0.0 to 1.0) for testing error scenarios
 */
class MockAuthenticationRepository(
    private val simulateNetworkDelay: Boolean = true,
    private val successRate: Float = 1.0f
) : IAuthenticationRepository {
    
    companion object {
        private const val TAG = "MockAuthRepository"
        
        // Hardcoded OTP for testing - always use this code in mock mode
        const val MOCK_OTP_CODE = "123456"
        
        // Simulated delays
        private const val REQUEST_DELAY_MS = 800L
        private const val VERIFY_DELAY_MS = 600L
    }
    
    // Track phone numbers that have requested OTPs
    private val requestedPhones = mutableSetOf<String>()
    
    override suspend fun requestOtp(phone: String): Result<OtpRequestResponse> {
        AppLog.d(TAG, "Mock: Requesting OTP for phone: ${maskPhone(phone)}")
        
        // Simulate network latency
        if (simulateNetworkDelay) {
            delay(REQUEST_DELAY_MS)
        }
        
        // Simulate random failures for testing
        if (Random.nextFloat() > successRate) {
            val errorMsg = "Mock: Network error (simulated failure)"
            AppLog.e(TAG, errorMsg)
            return Result.failure(AuthenticationException.NetworkError(errorMsg))
        }
        
        // Store phone number as "requested"
        requestedPhones.add(phone)
        
        val message = "Mock OTP sent successfully. Use code: $MOCK_OTP_CODE"
        AppLog.i(TAG, "Mock: OTP requested for ${maskPhone(phone)}. Use code: $MOCK_OTP_CODE")
        
        return Result.success(
            OtpRequestResponse(
                success = true,
                message = message
            )
        )
    }
    
    override suspend fun verifyOtp(phone: String, otp: String): Result<OtpVerifyResponse> {
        AppLog.d(TAG, "Mock: Verifying OTP for phone: ${maskPhone(phone)}, code: $otp")
        
        // Simulate network latency
        if (simulateNetworkDelay) {
            delay(VERIFY_DELAY_MS)
        }
        
        // Check if OTP was requested for this phone
        if (!requestedPhones.contains(phone)) {
            val errorMsg = "No OTP requested for this phone number"
            AppLog.w(TAG, "Mock: Verification failed - $errorMsg")
            return Result.failure(AuthenticationException.InvalidOtpError(errorMsg))
        }
        
        // Verify OTP code
        if (otp == MOCK_OTP_CODE) {
            // Success!
            requestedPhones.remove(phone) // Clear the request
            AppLog.i(TAG, "Mock: OTP verified successfully for ${maskPhone(phone)}")
            
            return Result.success(
                OtpVerifyResponse(
                    success = true,
                    message = "Mock verification successful"
                )
            )
        } else {
            // Invalid OTP
            val errorMsg = "Invalid OTP code. Expected: $MOCK_OTP_CODE"
            AppLog.w(TAG, "Mock: Invalid OTP - received: $otp, expected: $MOCK_OTP_CODE")
            
            return Result.failure(
                AuthenticationException.InvalidOtpError(errorMsg)
            )
        }
    }
    
    /**
     * Mask phone number for logging (privacy)
     * Example: +12345678900 -> +1***-***-8900
     */
    private fun maskPhone(phone: String): String {
        if (phone.length < 4) return "***"
        val country = phone.substring(0, 2) // +1
        val last4 = phone.takeLast(4)
        return "$country***-***-$last4"
    }
    
    /**
     * Reset mock state - useful for testing
     */
    fun reset() {
        requestedPhones.clear()
        AppLog.d(TAG, "Mock: State reset")
    }
}
