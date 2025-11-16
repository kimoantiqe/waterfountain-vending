package com.waterfountainmachine.app.hardware.sdk

/**
 * Base exception for all vending machine errors
 */
sealed class VendingMachineException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    
    /**
     * Motor failure during water dispensing
     * Maps to vendor SDK status code 4 and error code 0x02
     */
    class MotorFailure(
        val slot: Int,
        val errorCode: Int = 0x02
    ) : VendingMachineException("Motor failure at slot $slot (error code: 0x${errorCode.toString(16).uppercase()})")
    
    /**
     * Optical sensor failure (water not detected)
     * Maps to vendor SDK status code 5 and error code 0x03
     */
    class OpticalSensorFailure(
        val slot: Int,
        val errorCode: Int = 0x03
    ) : VendingMachineException("Optical sensor failure at slot $slot (error code: 0x${errorCode.toString(16).uppercase()})")
    
    /**
     * Unknown hardware error
     * Maps to vendor SDK status code 6
     */
    class UnknownError(
        val slot: Int,
        val statusCode: Int
    ) : VendingMachineException("Unknown error at slot $slot (status code: $statusCode)")
    
    /**
     * Invalid slot number (not in valid 48-slot layout)
     */
    class InvalidSlot(
        val slot: Int,
        val reason: String
    ) : VendingMachineException("Invalid slot $slot: $reason")
    
    /**
     * Communication timeout with hardware
     */
    class Timeout(
        val slot: Int,
        val timeoutMs: Long
    ) : VendingMachineException("Timeout after ${timeoutMs}ms at slot $slot")
    
    /**
     * Serial port communication error
     */
    class CommunicationError(
        message: String,
        cause: Throwable? = null
    ) : VendingMachineException("Communication error: $message", cause)
    
    /**
     * SDK initialization error
     */
    class InitializationError(
        message: String,
        cause: Throwable? = null
    ) : VendingMachineException("Initialization error: $message", cause)
}
