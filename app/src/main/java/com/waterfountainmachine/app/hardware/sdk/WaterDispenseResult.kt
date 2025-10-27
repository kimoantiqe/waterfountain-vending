package com.waterfountainmachine.app.hardware.sdk

/**
 * Result of water dispensing operation
 * 
 * This data class represents the outcome of a water dispensing request.
 * It contains success status, slot information, error details, and timing data.
 */
data class WaterDispenseResult(
    /**
     * Whether the water was dispensed successfully
     */
    val success: Boolean,
    
    /**
     * The slot/lane number that was attempted
     */
    val slot: Int,
    
    /**
     * Error code if the operation failed
     * Common error codes:
     * - 0x01: Invalid slot
     * - 0x02: Motor failure
     * - 0x03: Optical sensor failure
     * - 0x04: Timeout
     * - 0xFF: Unknown error
     */
    val errorCode: Byte? = null,
    
    /**
     * Human-readable error message if the operation failed
     */
    val errorMessage: String? = null,
    
    /**
     * Time taken for the dispensing operation in milliseconds
     */
    val dispensingTimeMs: Long = 0
)
