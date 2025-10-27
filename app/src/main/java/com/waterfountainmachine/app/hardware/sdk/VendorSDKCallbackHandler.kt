package com.waterfountainmachine.app.hardware.sdk

import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlin.coroutines.resume

// Import WaterDispenseResult from VendingMachineSDK
// Note: This will be available after we keep VendingMachineSDK.kt or move the data class

/**
 * Utility class to handle vendor SDK callbacks and convert them to coroutine results.
 * 
 * Vendor SDK Status Codes:
 * - 0: Initialization
 * - 1: Reset in progress
 * - 2: Dispensing in progress
 * - 3: Success
 * - 4: Motor failure (error code 0x02)
 * - 5: Optical sensor failure (error code 0x03)
 * - 6: Unknown error
 */
internal object VendorSDKCallbackHandler {
    
    private const val TAG = "VendorSDKCallback"
    
    // Vendor SDK status codes
    const val STATUS_INIT = 0
    const val STATUS_RESET = 1
    const val STATUS_IN_PROGRESS = 2
    const val STATUS_SUCCESS = 3
    const val STATUS_MOTOR_FAILURE = 4
    const val STATUS_OPTICAL_FAILURE = 5
    const val STATUS_UNKNOWN_ERROR = 6
    
    /**
     * Map vendor SDK status code to Result<WaterDispenseResult>
     */
    fun mapStatusToResult(status: Int, slot: Int): Result<WaterDispenseResult> {
        Log.d(TAG, "Mapping status $status for slot $slot")
        
        return when (status) {
            STATUS_SUCCESS -> {
                Log.i(TAG, "Dispense successful at slot $slot")
                Result.success(
                    WaterDispenseResult(
                        success = true,
                        slot = slot,
                        errorCode = null,
                        errorMessage = null,
                        dispensingTimeMs = 0
                    )
                )
            }
            
            STATUS_MOTOR_FAILURE -> {
                Log.e(TAG, "Motor failure at slot $slot")
                Result.success(
                    WaterDispenseResult(
                        success = false,
                        slot = slot,
                        errorCode = 0x02,
                        errorMessage = "Motor failure during water dispensing",
                        dispensingTimeMs = 0
                    )
                )
            }
            
            STATUS_OPTICAL_FAILURE -> {
                Log.e(TAG, "Optical sensor failure at slot $slot")
                Result.success(
                    WaterDispenseResult(
                        success = false,
                        slot = slot,
                        errorCode = 0x03,
                        errorMessage = "Optical sensor failure - water not detected",
                        dispensingTimeMs = 0
                    )
                )
            }
            
            STATUS_UNKNOWN_ERROR -> {
                Log.e(TAG, "Unknown error at slot $slot")
                Result.success(
                    WaterDispenseResult(
                        success = false,
                        slot = slot,
                        errorCode = 0xFF.toByte(),
                        errorMessage = "Unknown hardware error",
                        dispensingTimeMs = 0
                    )
                )
            }
            
            STATUS_INIT, STATUS_RESET, STATUS_IN_PROGRESS -> {
                // These are intermediate states, should not be terminal
                Log.w(TAG, "Received intermediate status $status for slot $slot - ignoring")
                // Return null to indicate we should continue waiting
                throw IllegalStateException("Received non-terminal status: $status")
            }
            
            else -> {
                Log.e(TAG, "Unexpected status code $status at slot $slot")
                Result.failure(
                    VendingMachineException.UnknownError(
                        slot = slot,
                        statusCode = status
                    )
                )
            }
        }
    }
    
    /**
     * Resume continuation with mapped result, handling only terminal status codes
     */
    fun resumeWithStatus(
        continuation: CancellableContinuation<Result<WaterDispenseResult>>,
        status: Int,
        slot: Int
    ) {
        try {
            // Only resume for terminal status codes (3, 4, 5, 6)
            if (status >= STATUS_SUCCESS) {
                val result = mapStatusToResult(status, slot)
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            } else {
                // Intermediate states (0, 1, 2) - log but don't resume
                Log.d(TAG, "Intermediate status $status for slot $slot - continuing to wait")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming continuation", e)
            if (continuation.isActive) {
                continuation.resume(
                    Result.failure(
                        VendingMachineException.CommunicationError(
                            "Error processing status callback",
                            e
                        )
                    )
                )
            }
        }
    }
}
