package com.waterfountainmachine.app.auth

/**
 * Authentication repository interface for SMS OTP operations.
 * 
 * This interface allows polymorphic behavior between real API calls
 * and mock implementations for testing/development.
 * 
 * Implementations:
 * - RealAuthenticationRepository: Connects to Firebase Cloud Functions with certificate auth
 * - MockAuthenticationRepository: Simulates authentication locally for testing
 */
interface IAuthenticationRepository {
    
    /**
     * Request an OTP code to be sent to the specified phone number.
     * 
     * @param phone Phone number in E.164 format (e.g., "+12345678900")
     * @return Result.success(OtpRequestResponse) on success, Result.failure(Exception) on error
     */
    suspend fun requestOtp(phone: String): Result<OtpRequestResponse>
    
    /**
     * Verify an OTP code for the specified phone number.
     * 
     * @param phone Phone number in E.164 format (e.g., "+12345678900")
     * @param otp The OTP code to verify (typically 6 digits)
     * @return Result.success(OtpVerifyResponse) on success, Result.failure(Exception) on error
     */
    suspend fun verifyOtp(phone: String, otp: String): Result<OtpVerifyResponse>
}

/**
 * Response data class for OTP request operation
 */
data class OtpRequestResponse(
    val success: Boolean,
    val message: String? = null
)

/**
 * Response data class for OTP verification operation
 */
data class OtpVerifyResponse(
    val success: Boolean,
    val message: String? = null,
    val sessionToken: String? = null, // Optional session token from backend
    val dailyVendLimit: Int = 2, // Maximum vends per day (default 2)
    val vendsUsedToday: Int = 0, // Number of vends used today
    val vendsRemainingToday: Int = 2 // Remaining vends for today
)

/**
 * Exception types for authentication errors
 */
sealed class AuthenticationException(message: String) : Exception(message) {
    class NetworkError(message: String = "Network connection failed") : AuthenticationException(message)
    class RateLimitError(message: String = "Too many requests. Please try again later") : AuthenticationException(message)
    class DailyLimitError(message: String = "Daily vending limit reached") : AuthenticationException(message)
    class InvalidOtpError(message: String = "Invalid OTP code") : AuthenticationException(message)
    class ExpiredOtpError(message: String = "OTP code has expired") : AuthenticationException(message)
    class CertificateError(message: String = "Certificate authentication failed") : AuthenticationException(message)
    class ServerError(message: String = "Server error occurred") : AuthenticationException(message)
}
