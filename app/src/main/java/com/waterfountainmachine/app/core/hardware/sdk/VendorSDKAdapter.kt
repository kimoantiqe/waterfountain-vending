package com.waterfountainmachine.app.hardware.sdk

import com.waterfountainmachine.app.utils.AppLog
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
 * Real hardware adapter wrapping the vendor's CYVendingMachine SDK.
 * 
 * Important: The vendor SDK uses a PER-OPERATION connection model:
 * - Each CYVendingMachine() constructor opens /dev/ttyS0 at 9600 baud
 * - The serial port stays open until closeSerialPort() is called
 * - There is NO persistent connection - each dispense is independent
 * - SerialConfig parameters are IGNORED (vendor SDK hardcodes settings)
 * - The vendor SDK manages the complete operation lifecycle internally
 * 
 * This adapter provides:
 * - Coroutine-based API (suspend functions)
 * - Result<T> based error handling
 * - Slot validation (48 valid slots in 6×8 layout)
 * - Proper resource cleanup
 * 
 * Architecture:
 * WaterFountainManager → VendorSDKAdapter → CYVendingMachine → /dev/ttyS0 → VMC Hardware
 */
class VendorSDKAdapter : IVendingMachineAdapter {
    private val tag = "VendorSDKAdapter"
    
    // Track active SDK instance for cleanup
    @Volatile
    private var activeSDK: CYVendingMachine? = null
    
    // Track readiness (serial port accessible)
    @Volatile
    private var isReadyState = false
    
    /**
     * Initialize the adapter and verify serial port access.
     * 
     * Note: We don't actually open the serial port here - the vendor SDK
     * does that automatically on each dispense operation. We just verify
     * that the port exists and is accessible.
     */
    override fun initialize(): Result<Unit> {
        return try {
            // Check if /dev/ttyS0 exists and is accessible
            val serialPort = java.io.File("/dev/ttyS0")
            if (!serialPort.exists()) {
                AppLog.e(tag, "Serial port /dev/ttyS0 not found")
                isReadyState = false
                return Result.failure(
                    VendingMachineException.InitializationError(
                        "Serial port /dev/ttyS0 not found"
                    )
                )
            }
            
            if (!serialPort.canRead() || !serialPort.canWrite()) {
                AppLog.e(tag, "Serial port /dev/ttyS0 not accessible (check permissions)")
                isReadyState = false
                return Result.failure(
                    VendingMachineException.InitializationError(
                        "Serial port /dev/ttyS0 not accessible - check permissions"
                    )
                )
            }
            
            AppLog.i(tag, "Serial port /dev/ttyS0 verified and accessible")
            AppLog.i(tag, "Vendor SDK operates per-operation (opens/closes port automatically)")
            isReadyState = true
            Result.success(Unit)
        } catch (e: Exception) {
            AppLog.e(tag, "Error verifying serial port", e)
            isReadyState = false
            Result.failure(
                VendingMachineException.InitializationError(
                    "Failed to verify serial port: ${e.message}",
                    e
                )
            )
        }
    }
    
    /**
     * Check if the adapter is ready to perform operations.
     * Returns true if serial port was verified during initialization.
     */
    override fun isReady(): Boolean {
        return isReadyState
    }
    

    
    /**
     * Dispense water from the specified slot.
     * 
     * SINGLE DISPENSE OPERATION:
     * - Send dispense command (0x41) once
     * - VMC handles product dispense AND door unlock automatically
     * - Any errors are propagated to the caller
     * 
     * Per-operation connection lifecycle:
     * 1. Validates slot number (must be in 48-slot layout)
     * 2. Creates CYVendingMachine instance (opens /dev/ttyS0 automatically)
     * 3. Waits for shipment callback (status 3, 4, 5, or 6)
     * 4. Vendor SDK auto-closes port via CancleQuery() on terminal status
     * 5. Returns WaterDispenseResult
     * 
     * The vendor SDK manages the complete operation lifecycle internally,
     * including timeouts and error handling. We trust the SDK to complete
     * naturally without imposing artificial timeouts.
     * 
     * @param slot The slot number (1-8, 11-18, 21-28, 31-38, 41-48, 51-58)
     * @return Result with WaterDispenseResult (always success Result, check WaterDispenseResult.success flag)
     */
    override suspend fun dispenseWater(slot: Int): Result<WaterDispenseResult> {
        AppLog.i(tag, "========================================")
        AppLog.i(tag, "DISPENSE OPERATION START")
        AppLog.i(tag, "========================================")
        AppLog.i(tag, "Slot: $slot")
        
        // Step 1: Validate slot
        if (!SlotValidator.isValidSlot(slot)) {
            AppLog.e(tag, "Invalid slot: $slot")
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
        
        // Step 2: Check readiness
        if (!isReadyState) {
            AppLog.e(tag, "Adapter not ready - serial port not verified")
            return Result.success(
                WaterDispenseResult(
                    success = false,
                    slot = slot,
                    errorCode = 0x02,
                    errorMessage = "Adapter not ready - serial port not accessible",
                    dispensingTimeMs = 0
                )
            )
        }
        
        val startTime = System.currentTimeMillis()
        
        // Single dispense operation
        AppLog.i(tag, "Sending dispense command (0x41)")
        AppLog.i(tag, "Opening serial port /dev/ttyS0 (9600 baud)")
        
        val result = dispenseWaterInternal(slot)
        val totalTime = System.currentTimeMillis() - startTime
        
        // Propagate any errors directly
        if (result.isFailure) {
            AppLog.e(tag, "❌ DISPENSE OPERATION FAILED")
            AppLog.i(tag, "========================================")
            return result
        }
        
        val dispenseResult = result.getOrNull()!!
        
        if (!dispenseResult.success) {
            val errorMsg = dispenseResult.errorMessage
            val errorCode = dispenseResult.errorCode
            val errorCodeHex = errorCode?.toString(16) ?: "00"
            AppLog.e(tag, "❌ Dispense failed: $errorMsg (code: 0x$errorCodeHex)")
            AppLog.i(tag, "========================================")
            return result
        }
        
        AppLog.i(tag, "✅ Dispense complete")
        AppLog.i(tag, "========================================")
        AppLog.i(tag, "✅ DISPENSE OPERATION SUCCESS")
        AppLog.i(tag, "========================================")
        AppLog.i(tag, "Total time: ${totalTime}ms")
        
        return Result.success(
            WaterDispenseResult(
                success = true,
                slot = slot,
                errorCode = 0x00,
                errorMessage = "",
                dispensingTimeMs = totalTime
            )
        )
    }
    
    /**
     * Internal dispense operation that wraps vendor SDK callback in coroutine.
     * 
     * Connection lifecycle within this operation:
     * 1. CYVendingMachine() constructor → opens /dev/ttyS0
     * 2. Sends shipment command to VMC
     * 3. Waits for callback with status
     * 4. Vendor SDK's CancleQuery() automatically closes port on terminal status
     * 
     * IMPORTANT: The vendor SDK closes the port automatically when it receives
     * a terminal status (E1 response). We should NOT call cleanup() in the callback.
     * cleanup() is only for exception scenarios where initialization fails.
     */
    private suspend fun dispenseWaterInternal(slot: Int): Result<WaterDispenseResult> = suspendCoroutine { continuation ->
        try {
            AppLog.d(tag, "Creating CYVendingMachine instance (opens serial port)")
            
            // Create vendor SDK instance with callback
            // NOTE: Constructor is BLOCKING and opens /dev/ttyS0 immediately
            val sdk = CYVendingMachine(slot, object : CYVendingMachine.ShipmentListener {
                override fun Shipped(status: Int) {
                    AppLog.d(tag, "Callback received: status=$status, slot=$slot")
                    
                    // Only resume for terminal status codes (3, 4, 5, 6)
                    if (status >= VendorSDKCallbackHandler.STATUS_SUCCESS) {
                        val result = VendorSDKCallbackHandler.mapStatusToResult(status, slot)
                        
                        AppLog.i(tag, "Terminal status received (port auto-closed by vendor SDK)")
                        
                        // Resume continuation
                        continuation.resume(result)
                        
                        // Clear SDK reference (port already closed by vendor SDK's CancleQuery())
                        // The vendor SDK automatically closes the port when it receives E1 response
                        // Calling cleanup() here would attempt to close an already-closed port
                        activeSDK = null
                    } else {
                        // Intermediate status (0, 1, 2) - just log
                        AppLog.d(tag, "Intermediate status $status - waiting for terminal status")
                    }
                }
            })
            
            // Store reference for cleanup
            activeSDK = sdk
            
            AppLog.i(tag, "Serial port opened, dispense command sent to VMC")
            
        } catch (e: Exception) {
            AppLog.e(tag, "Error creating CYVendingMachine (serial port issue?)", e)
            cleanup() // Only cleanup on exception (initialization failed)
            continuation.resume(
                Result.failure(
                    VendingMachineException.InitializationError(
                        "Failed to open serial port: ${e.message}",
                        e
                    )
                )
            )
        }
    }
    
    /**
     * Open the retrieval door/compartment.
     * 
     * This uses the undocumented 0x11 command discovered in SerialPortUtils:
     * - onOfflineOpen() sends: FF 00 55 11 01 01 CHK
     * - This unlocks the access door so customer can retrieve product
     * 
     * CRITICAL: This must be called AFTER successful product dispense.
     * The vending machine has a two-stage retrieval system:
     * 1. Dispense product into internal chamber (0x41 command)
     * 2. Unlock access door for customer retrieval (0x11 command)
     * 
     * @return Result with Unit on success, or VendingMachineException on failure
     */
    suspend fun openDoor(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            AppLog.i(tag, "========================================")
            AppLog.i(tag, "DOOR UNLOCK OPERATION")
            AppLog.i(tag, "========================================")
            
            if (!isReadyState) {
                AppLog.e(tag, "Adapter not ready - serial port not verified")
                return@withContext Result.failure(
                    VendingMachineException.InitializationError(
                        "Adapter not ready - cannot open door"
                    )
                )
            }
            
            val serialPort = java.io.File("/dev/ttyS0")
            if (!serialPort.exists() || !serialPort.canRead() || !serialPort.canWrite()) {
                AppLog.e(tag, "Serial port /dev/ttyS0 not accessible")
                return@withContext Result.failure(
                    VendingMachineException.InitializationError(
                        "Serial port not accessible"
                    )
                )
            }
            
            AppLog.i(tag, "Opening serial port /dev/ttyS0")
            
            // Use SerialPortUtils directly for door control
            val serialPortUtils = com.yy.tools.util.SerialPortUtils.getInstance()
            serialPortUtils.setPort("/dev/ttyS0")
            serialPortUtils.setBaudrate(9600)
            
            serialPortUtils.openSerialPort()
            AppLog.i(tag, "Serial port opened, sending door unlock command")
            
            // Send the undocumented door unlock command
            // This sends: FF 00 55 11 01 01 CHK
            serialPortUtils.onOfflineOpen()
            AppLog.i(tag, "✅ Door unlock command sent (0x11 01)")
            
            // Give hardware time to process
            delay(500)
            
            // Close serial port
            serialPortUtils.closeSerialPort()
            AppLog.i(tag, "Serial port closed")
            AppLog.i(tag, "========================================")
            AppLog.i(tag, "✅ DOOR UNLOCK COMPLETE")
            AppLog.i(tag, "========================================")
            
            Result.success(Unit)
            
        } catch (e: Exception) {
            AppLog.e(tag, "========================================")
            AppLog.e(tag, "❌ DOOR UNLOCK FAILED")
            AppLog.e(tag, "========================================")
            AppLog.e(tag, "Exception: ${e.javaClass.simpleName}")
            AppLog.e(tag, "Message: ${e.message}")
            AppLog.e(tag, "Error opening door", e)
            Result.failure(
                VendingMachineException.CommunicationError(
                    "Failed to open door: ${e.message}",
                    e
                )
            )
        }
    }
    
    /**
     * Close the retrieval door/compartment (optional).
     * 
     * Uses undocumented 0x11 command with 0x00 parameter:
     * - onOfflineClose() sends: FF 00 55 11 01 00 CHK
     * 
     * Note: Most vending machines auto-close after timeout.
     * This is provided for manual control if needed.
     * 
     * @return Result with Unit on success, or VendingMachineException on failure
     */
    suspend fun closeDoor(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            AppLog.i(tag, "Sending door close command...")
            
            val serialPortUtils = com.yy.tools.util.SerialPortUtils.getInstance()
            serialPortUtils.setPort("/dev/ttyS0")
            serialPortUtils.setBaudrate(9600)
            
            serialPortUtils.openSerialPort()
            serialPortUtils.onOfflineClose()
            delay(500)
            serialPortUtils.closeSerialPort()
            
            AppLog.i(tag, "✅ Door close command sent")
            Result.success(Unit)
            
        } catch (e: Exception) {
            AppLog.e(tag, "Error closing door", e)
            Result.failure(
                VendingMachineException.CommunicationError(
                    "Failed to close door: ${e.message}",
                    e
                )
            )
        }
    }

    /**
     * Cleanup resources (close serial port).
     * Called only on timeout or exception - NOT on normal completion.
     * 
     * NOTE: The vendor SDK automatically closes the port via CancleQuery() 
     * when it receives E1 response with terminal status. This method is only
     * for abnormal termination (timeout, exception).
     */
    private fun cleanup() {
        activeSDK?.let { sdk ->
            try {
                AppLog.d(tag, "Closing serial port /dev/ttyS0")
                sdk.closeSerialPort()
                activeSDK = null
            } catch (e: Exception) {
                AppLog.e(tag, "Error closing serial port", e)
            }
        }
    }
    
    /**
     * Shutdown the adapter and cleanup resources.
     * Called when app is closing or when switching to mock mode.
     */
    override fun shutdown() {
        AppLog.i(tag, "Shutting down VendorSDKAdapter")
        cleanup()
        isReadyState = false
    }
    
    /**
     * Get hardware info (for diagnostics)
     */
    override fun getHardwareInfo(): Map<String, String> {
        return mapOf(
            "adapter" to "VendorSDKAdapter",
            "vendor_sdk" to "CYVendingMachine (SerialPortUtils)",
            "connection_model" to "Per-operation (opens/closes port each dispense)",
            "serial_port" to "/dev/ttyS0",
            "baud_rate" to "9600 (hardcoded by vendor SDK)",
            "data_bits" to "8 (hardcoded by vendor SDK)",
            "stop_bits" to "1 (hardcoded by vendor SDK)",
            "parity" to "None (hardcoded by vendor SDK)",
            "valid_slots" to "48 slots (6 rows × 8 columns)",
            "timeout_handling" to "Managed internally by vendor SDK",
            "ready" to isReadyState.toString()
        )
    }
}
