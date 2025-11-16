package com.waterfountainmachine.app.api

import com.google.firebase.functions.FirebaseFunctions
import com.waterfountainmachine.app.auth.AuthenticationException
import com.waterfountainmachine.app.utils.AppLog
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase API Client - Abstraction layer for all Firebase Function calls
 * 
 * Benefits:
 * - Single source of truth for API configuration
 * - Centralized error handling
 * - Easy to mock for testing
 * - Type-safe API calls
 */
@Singleton
class FirebaseApiClient @Inject constructor(
    private val functions: FirebaseFunctions
) {
    companion object {
        private const val TAG = "FirebaseApiClient"
        private const val DEFAULT_TIMEOUT_MS = 30_000L
    }
    
    /**
     * Call a Firebase Cloud Function with automatic error handling
     * 
     * @param functionName Name of the cloud function
     * @param data Request data as Map
     * @param timeoutMs Timeout in milliseconds
     * @return Result containing response data or error
     */
    suspend fun <T> call(
        functionName: String,
        data: Map<String, Any>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Result<Map<String, Any>> {
        return try {
            AppLog.d(TAG, "Calling function: $functionName")
            
            val result = functions
                .getHttpsCallable(functionName)
                .withTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .call(data)
                .await()
            
            @Suppress("UNCHECKED_CAST")
            val responseData = result.data as? Map<String, Any> ?: emptyMap()
            
            AppLog.d(TAG, "Function $functionName completed successfully")
            Result.success(responseData)
            
        } catch (e: com.google.firebase.functions.FirebaseFunctionsException) {
            AppLog.e(TAG, "Firebase Function error: ${e.code} - ${e.message}", e)
            Result.failure(parseFirebaseException(e))
        } catch (e: Exception) {
            AppLog.e(TAG, "Unexpected error calling $functionName", e)
            Result.failure(
                AuthenticationException.NetworkError("Network error: ${e.message}")
            )
        }
    }
    
    /**
     * Parse Firebase Functions exceptions into domain-specific exceptions
     */
    private fun parseFirebaseException(e: com.google.firebase.functions.FirebaseFunctionsException): Exception {
        return when (e.code) {
            com.google.firebase.functions.FirebaseFunctionsException.Code.UNAUTHENTICATED -> {
                AuthenticationException.CertificateError("Authentication failed. Check certificate.")
            }
            com.google.firebase.functions.FirebaseFunctionsException.Code.PERMISSION_DENIED -> {
                AuthenticationException.CertificateError("Access denied. Certificate may be invalid.")
            }
            com.google.firebase.functions.FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> {
                AuthenticationException.NetworkError("Request timed out. Check network connection.")
            }
            com.google.firebase.functions.FirebaseFunctionsException.Code.UNAVAILABLE -> {
                AuthenticationException.ServerError("Service temporarily unavailable.")
            }
            com.google.firebase.functions.FirebaseFunctionsException.Code.INTERNAL -> {
                AuthenticationException.ServerError("Server error occurred.")
            }
            else -> {
                AuthenticationException.ServerError("Service error: ${e.message}")
            }
        }
    }
}
