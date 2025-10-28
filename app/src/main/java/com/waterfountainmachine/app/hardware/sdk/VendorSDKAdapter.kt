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
 * Real hardware adapter wrapping the vendor's CYVendingMachine SDK.
 * 
 * Important: The vendor SDK uses a PER-OPERATION connection model:
 * - Each CYVendingMachine() constructor opens /dev/ttyS0 at 9600 baud
 * - The serial port stays open until closeSerialPort() is called
 * - There is NO persistent connection - each dispense is independent
 * - SerialConfig parameters are IGNORED (vendor SDK hardcodes settings)
 * 
 * This adapter provides:
 * - Coroutine-based API (suspend functions)
 * - Result<T> based error handling
 * - Slot validation (48 valid slots in 6×8 layout)
 * - Proper resource cleanup
 * - Timeout handling (default 30 seconds)
 * 
 * Architecture:
 * WaterFountainManager → VendorSDKAdapter → CYVendingMachine → /dev/ttyS0 → VMC Hardware
 */
class VendorSDKAdapter(
    private val timeoutMs: Long = 30_000L // 30 second timeout
) : IVendingMachineAdapter {
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
                Log.e(tag, "Serial port /dev/ttyS0 not found")
                isReadyState = false
                return Result.failure(
                    VendingMachineException.InitializationError(
                        "Serial port /dev/ttyS0 not found"
                    )
                )
            }
            
            if (!serialPort.canRead() || !serialPort.canWrite()) {
                Log.e(tag, "Serial port /dev/ttyS0 not accessible (check permissions)")
                isReadyState = false
                return Result.failure(
                    VendingMachineException.InitializationError(
                        "Serial port /dev/ttyS0 not accessible - check permissions"
                    )
                )
            }
            
            Log.i(tag, "✓ Serial port /dev/ttyS0 verified and accessible")
            Log.i(tag, "✓ Vendor SDK operates per-operation (opens/closes port automatically)")
            isReadyState = true
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(tag, "Error verifying serial port", e)
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
     * Per-operation connection lifecycle:
     * 1. Validates slot number (must be in 48-slot layout)
     * 2. Creates CYVendingMachine instance (opens /dev/ttyS0 automatically)
     * 3. Waits for shipment callback (status 3, 4, 5, or 6)
     * 4. Converts callback to WaterDispenseResult
     * 5. Closes serial port (cleanup)
     * 
     * @param slot The slot number (1-8, 11-18, 21-28, 31-38, 41-48, 51-58)
     * @return Result with WaterDispenseResult (always success Result, check WaterDispenseResult.success flag)
     */
    override suspend fun dispenseWater(slot: Int): Result<WaterDispenseResult> {
        Log.i(tag, "═══ DISPENSE OPERATION START ═══")
        Log.i(tag, "Slot: $slot | Per-operation connection model")
        
        // Step 1: Validate slot
        if (!SlotValidator.isValidSlot(slot)) {
            Log.e(tag, "✗ Invalid slot: $slot")
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
            Log.e(tag, "✗ Adapter not ready - serial port not verified")
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
        
        // Step 3: Dispense with timeout
        Log.i(tag, "→ Opening serial port /dev/ttyS0 (9600 baud)")
        return withTimeoutOrNull(timeoutMs) {
            dispenseWaterInternal(slot)
        } ?: run {
            Log.e(tag, "✗ Timeout after ${timeoutMs}ms for slot $slot")
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
        }.also {
            Log.i(tag, "═══ DISPENSE OPERATION END ═══")
        }
    }
    
    /**
     * Internal dispense operation that wraps vendor SDK callback in coroutine.
     * 
     * Connection lifecycle within this operation:
     * 1. CYVendingMachine() constructor → opens /dev/ttyS0
     * 2. Sends shipment command to VMC
     * 3. Waits for callback with status
     * 4. cleanup() → closes /dev/ttyS0
     */
    private suspend fun dispenseWaterInternal(slot: Int): Result<WaterDispenseResult> = suspendCoroutine { continuation ->
        try {
            Log.d(tag, "→ Creating CYVendingMachine instance (opens serial port)")
            
            // Create vendor SDK instance with callback
            // NOTE: Constructor is BLOCKING and opens /dev/ttyS0 immediately
            val sdk = CYVendingMachine(slot, object : CYVendingMachine.ShipmentListener {
                override fun Shipped(status: Int) {
                    Log.d(tag, "← Callback received: status=$status, slot=$slot")
                    
                    // Only resume for terminal status codes (3, 4, 5, 6)
                    if (status >= VendorSDKCallbackHandler.STATUS_SUCCESS) {
                        val result = VendorSDKCallbackHandler.mapStatusToResult(status, slot)
                        
                        Log.i(tag, "→ Terminal status received, closing serial port")
                        // Resume continuation
                        continuation.resume(result)
                        
                        // Cleanup after completion (closes serial port)
                        cleanup()
                    } else {
                        // Intermediate status (0, 1, 2) - just log
                        Log.d(tag, "  Intermediate status $status - waiting for terminal status")
                    }
                }
            })
            
            // Store reference for cleanup
            activeSDK = sdk
            
            Log.i(tag, "✓ Serial port opened, dispense command sent to VMC")
            
        } catch (e: Exception) {
            Log.e(tag, "✗ Error creating CYVendingMachine (serial port issue?)", e)
            cleanup()
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
     * Cleanup resources (close serial port).
     * Called after each dispense operation or on timeout.
     */
    private fun cleanup() {
        activeSDK?.let { sdk ->
            try {
                Log.d(tag, "→ Closing serial port /dev/ttyS0")
                sdk.closeSerialPort()
                activeSDK = null
            } catch (e: Exception) {
                Log.e(tag, "✗ Error closing serial port", e)
            }
        }
    }
    
    /**
     * Shutdown the adapter and cleanup resources.
     * Called when app is closing or when switching to mock mode.
     */
    override fun shutdown() {
        Log.i(tag, "Shutting down VendorSDKAdapter")
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
            "timeout_ms" to timeoutMs.toString(),
            "ready" to isReadyState.toString()
        )
    }
}
