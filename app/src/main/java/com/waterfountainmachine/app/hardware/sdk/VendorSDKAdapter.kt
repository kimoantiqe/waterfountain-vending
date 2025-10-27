package com.waterfountainmachine.app.hardware.sdk

import android.util.Log
import com.yy.tools.util.CYVendingMachine
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Serial configuration (for compatibility with existing code)
 * Vendor SDK doesn't use these settings - it hardcodes /dev/ttyS0 at 9600 baud
 */
data class SerialConfig(
    val baudRate: Int = 9600,
    val dataBits: Int = 8,
    val stopBits: Int = 1,
    val parity: Int = 0 // 0 = None
)

/**
 * Adapter layer wrapping the vendor's CYVendingMachine SDK.
 * 
 * This adapter provides:
 * - Coroutine-based API (suspend functions)
 * - Result<T> based error handling
 * - Slot validation (48 valid slots in 6×8 layout)
 * - Proper resource cleanup
 * - Timeout handling
 * 
 * Architecture:
 * WaterFountainManager → VendorSDKAdapter → CYVendingMachine → Hardware
 */
class VendorSDKAdapter(
    private val timeoutMs: Long = 30_000L // 30 second timeout
) {
    private val tag = "VendorSDKAdapter"
    
    // Track active SDK instance for cleanup
    @Volatile
    private var activeSDK: CYVendingMachine? = null
    
    // Track connection state (vendor SDK doesn't maintain persistent connection)
    @Volatile
    private var isConnectedState = false
    
    /**
     * Initialize the adapter.
     * Note: Vendor SDK doesn't require explicit initialization - it's done per-operation
     */
    fun initialize(): Result<Unit> {
        Log.i(tag, "VendorSDKAdapter initialized")
        return Result.success(Unit)
    }
    
    /**
     * Initialize connection to VMC.
     * Note: Vendor SDK doesn't have persistent connection - each dispense operation
     * opens and closes the serial port. This method just marks us as "connected".
     */
    suspend fun connect(config: SerialConfig = SerialConfig()): Boolean {
        Log.i(tag, "connect() called - vendor SDK uses per-operation connections")
        isConnectedState = true
        return true
    }
    
    /**
     * Disconnect from VMC
     */
    suspend fun disconnect() {
        Log.i(tag, "disconnect() called")
        cleanup()
        isConnectedState = false
    }
    
    /**
     * Check if connected to VMC
     */
    fun isConnected(): Boolean {
        return isConnectedState
    }
    
    /**
     * Get VMC device ID.
     * Note: Vendor SDK doesn't provide device ID functionality.
     * Returns a mock value for compatibility.
     */
    suspend fun getDeviceId(): Result<String> {
        Log.d(tag, "getDeviceId() - returning mock value (vendor SDK doesn't support this)")
        return Result.success("VendorSDK-CYVendingMachine")
    }
    
    /**
     * Clear any faults in the VMC.
     * Note: Vendor SDK doesn't have explicit fault clearing.
     * Returns success for compatibility.
     */
    suspend fun clearFaults(): Result<Boolean> {
        Log.d(tag, "clearFaults() - vendor SDK handles faults automatically")
        return Result.success(true)
    }
    
    /**
     * Dispense water from the specified slot.
     * 
     * This is the main operation that:
     * 1. Validates the slot number (must be in 48-slot layout)
     * 2. Creates vendor SDK instance with callback
     * 3. Converts callback to coroutine result
     * 4. Handles timeout
     * 5. Cleans up resources
     * 
     * @param slot The slot number (1-8, 11-18, 21-28, 31-38, 41-48, 51-58)
     * @return Result with WaterDispenseResult on success or failure (always returns Result.success with WaterDispenseResult.success flag)
     */
    suspend fun dispenseWater(slot: Int): Result<WaterDispenseResult> {
        Log.i(tag, "dispenseWater: slot=$slot")
        
        // Step 1: Validate slot
        if (!SlotValidator.isValidSlot(slot)) {
            Log.e(tag, "Invalid slot: $slot")
            return Result.success(
                WaterDispenseResult(
                    success = false,
                    slot = slot,
                    errorCode = 0x01,
                    errorMessage = "Invalid slot. Must be in 6×8 layout (1-8, 11-18, 21-28, 31-38, 41-48, 51-58)",
                    dispensingTimeMs = 0
                )
            )
        }
        
        // Step 2: Dispense with timeout
        return withTimeoutOrNull(timeoutMs) {
            dispenseWaterInternal(slot)
        } ?: run {
            Log.e(tag, "Timeout after ${timeoutMs}ms for slot $slot")
            cleanup()
            Result.success(
                WaterDispenseResult(
                    success = false,
                    slot = slot,
                    errorCode = 0x04,
                    errorMessage = "Timeout after ${timeoutMs}ms",
                    dispensingTimeMs = timeoutMs
                )
            )
        }
    }
    
    /**
     * Internal dispense operation that wraps vendor SDK callback in coroutine
     */
    private suspend fun dispenseWaterInternal(slot: Int): Result<WaterDispenseResult> = suspendCoroutine { continuation ->
        try {
            Log.d(tag, "Creating CYVendingMachine instance for slot $slot")
            
            // Create vendor SDK instance with callback
            // Note: Constructor is blocking and opens serial port immediately
            val sdk = CYVendingMachine(slot, object : CYVendingMachine.ShipmentListener {
                override fun Shipped(status: Int) {
                    Log.d(tag, "Shipment callback received: status=$status, slot=$slot")
                    
                    // Only resume for terminal status codes (3, 4, 5, 6)
                    if (status >= VendorSDKCallbackHandler.STATUS_SUCCESS) {
                        val result = VendorSDKCallbackHandler.mapStatusToResult(status, slot)
                        
                        // Resume continuation
                        continuation.resume(result)
                        
                        // Cleanup after completion
                        cleanup()
                    } else {
                        // Intermediate status (0, 1, 2) - just log
                        Log.d(tag, "Intermediate status $status - waiting for terminal status")
                    }
                }
            })
            
            // Store reference for cleanup
            activeSDK = sdk
            
            Log.i(tag, "CYVendingMachine instance created and dispense started for slot $slot")
            
        } catch (e: Exception) {
            Log.e(tag, "Error creating CYVendingMachine", e)
            cleanup()
            continuation.resume(
                Result.failure(
                    VendingMachineException.InitializationError(
                        "Failed to initialize vendor SDK: ${e.message}",
                        e
                    )
                )
            )
        }
    }
    
    /**
     * Cleanup resources (close serial port)
     */
    private fun cleanup() {
        activeSDK?.let { sdk ->
            try {
                Log.d(tag, "Closing serial port")
                sdk.closeSerialPort()
                activeSDK = null
            } catch (e: Exception) {
                Log.e(tag, "Error closing serial port", e)
            }
        }
    }
    
    /**
     * Close the adapter and cleanup resources
     */
    fun close() {
        Log.i(tag, "Closing VendorSDKAdapter")
        cleanup()
    }
    
    /**
     * Get hardware info (for diagnostics)
     */
    fun getHardwareInfo(): Map<String, String> {
        return mapOf(
            "adapter" to "VendorSDKAdapter",
            "vendor_sdk" to "CYVendingMachine (SerialPortUtils)",
            "serial_port" to "/dev/ttyS0",
            "baud_rate" to "9600",
            "valid_slots" to "48 slots (6 rows × 8 columns)",
            "timeout_ms" to timeoutMs.toString()
        )
    }
}
