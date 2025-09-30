package com.waterfountainmachine.app.hardware

import android.content.Context
import android.util.Log
import com.waterfountainmachine.app.config.WaterFountainConfig
import com.waterfountainmachine.app.hardware.sdk.*
import com.waterfountainmachine.app.utils.AppLog
import kotlinx.coroutines.*

/**
 * Water Fountain Manager - High-level interface for water dispensing operations
 * Manages SDK lifecycle, configuration, and provides simple dispensing methods with smart lane management
 */
class WaterFountainManager private constructor(
    private val context: Context,
    private val config: WaterFountainConfig
) {
    
    companion object {
        private const val TAG = "WaterFountainManager"
        
        @Volatile
        private var INSTANCE: WaterFountainManager? = null
        
        fun getInstance(context: Context): WaterFountainManager {
            return INSTANCE ?: synchronized(this) {
                val config = WaterFountainConfig.getInstance(context)
                INSTANCE ?: WaterFountainManager(context.applicationContext, config)
                    .also { INSTANCE = it }
            }
        }
    }
    
    // SDK instance - initialized lazily
    private var sdk: VendingMachineSDK? = null
    private var isInitialized = false
    
    // Lane management system
    private val laneManager = LaneManager.getInstance(context)
    
    /**
     * Initialize the SDK with current configuration
     * Call this before using any dispensing functions
     */
    suspend fun initialize(): Boolean {
        return try {
            AppLog.d(TAG, "Initializing Water Fountain Manager...")
            AppLog.d(TAG, config.getConfigSummary())
            
            // Create SDK instance with current configuration
            // For now, we'll create a mock implementation directly here
            // TODO: Replace with real USB/Serial communicator when hardware is available
            val serialCommunicator = object : SerialCommunicator {
                private var connected = false
                private var lastCommand: Byte? = null
                
                /**
                 * Helper function to create properly formatted VMC protocol response
                 */
                private fun createVmcResponse(command: Byte, data: ByteArray): ByteArray {
                    val frame = ProtocolFrame(
                        header = ProtocolFrame.VMC_HEADER,
                        command = command,
                        dataLength = data.size.toByte(),
                        data = data,
                        checksum = 0x00 // Placeholder
                    )
                    
                    // Calculate proper checksum and create final frame
                    val properChecksum = frame.calculateChecksum()
                    val finalFrame = ProtocolFrame(
                        header = frame.header,
                        command = frame.command,
                        dataLength = frame.dataLength,
                        data = frame.data,
                        checksum = properChecksum
                    )
                    
                    return finalFrame.toByteArray()
                }
                
                override suspend fun connect(config: SerialConfig): Boolean {
                    AppLog.d(TAG, "Mock SerialCommunicator: connect() called with baud rate ${config.baudRate}")
                    connected = true
                    return true
                }
                
                override suspend fun disconnect() {
                    AppLog.d(TAG, "Mock SerialCommunicator: disconnect() called")
                    connected = false
                }
                
                override fun isConnected(): Boolean = connected
                
                override suspend fun sendData(data: ByteArray): Boolean {
                    AppLog.d(TAG, "Mock SerialCommunicator: sendData() called with ${data.size} bytes")
                    AppLog.d(TAG, "Data: ${data.joinToString(" ") { "0x%02X".format(it) }}")
                    
                    // Extract command from the data to return appropriate response
                    if (data.size >= 4) {
                        lastCommand = data[3] // Command is at position 3 in VMC protocol
                        AppLog.d(TAG, "Extracted command: 0x%02X".format(lastCommand))
                    }
                    return true
                }
                
                override suspend fun readData(timeoutMs: Long): ByteArray? {
                    AppLog.d(TAG, "Mock SerialCommunicator: readData() called for command 0x%02X".format(lastCommand ?: 0))
                    
                    return when (lastCommand) {
                        0x31.toByte() -> { // GET_DEVICE_ID
                            val deviceIdString = "WaterFountain001"
                            val deviceIdBytes = ByteArray(15) { 0x00 }
                            val sourceBytes = deviceIdString.toByteArray()
                            // Copy up to 15 bytes, pad with zeros if needed
                            System.arraycopy(sourceBytes, 0, deviceIdBytes, 0, minOf(sourceBytes.size, 15))
                            
                            val response = createVmcResponse(0x31, deviceIdBytes)
                            AppLog.d(TAG, "Returning GET_DEVICE_ID response: ${response.joinToString(" ") { "0x%02X".format(it) }}")
                            response
                        }
                        0x41.toByte() -> { // DELIVERY_COMMAND
                            val response = createVmcResponse(0x41, byteArrayOf(0x01, 0x01)) // slot 1, quantity 1
                            AppLog.d(TAG, "Returning DELIVERY_COMMAND response: ${response.joinToString(" ") { "0x%02X".format(it) }}")
                            response
                        }
                        0xE1.toByte() -> { // QUERY_STATUS
                            val response = createVmcResponse(0xE1.toByte(), byteArrayOf(0x01)) // success
                            AppLog.d(TAG, "Returning QUERY_STATUS response: ${response.joinToString(" ") { "0x%02X".format(it) }}")
                            response
                        }
                        0xA2.toByte() -> { // REMOVE_FAULT
                            val response = createVmcResponse(0xA2.toByte(), byteArrayOf(0x01)) // success
                            AppLog.d(TAG, "Returning REMOVE_FAULT response: ${response.joinToString(" ") { "0x%02X".format(it) }}")
                            response
                        }
                        else -> {
                            AppLog.w(TAG, "Unknown command, returning generic success response")
                            createVmcResponse(lastCommand ?: 0x00, byteArrayOf(0x01))
                        }
                    }
                }
                
                override fun getDataFlow(): kotlinx.coroutines.flow.Flow<ByteArray> {
                    return kotlinx.coroutines.flow.emptyFlow()
                }
                
                override suspend fun clearBuffers() {
                    AppLog.d(TAG, "Mock SerialCommunicator: clearBuffers() called")
                }
            }
            
            sdk = VendingMachineSDKImpl(
                serialCommunicator = serialCommunicator,
                commandTimeoutMs = config.commandTimeoutMs,
                statusPollingIntervalMs = config.statusPollingIntervalMs,
                maxStatusPollingAttempts = config.maxPollingAttempts
            )
            
            // Connect to hardware
            val serialConfig = SerialConfig(baudRate = config.serialBaudRate)
            AppLog.d(TAG, "Attempting to connect to hardware with config: baud rate ${serialConfig.baudRate}")
            val connected = sdk?.connect(serialConfig) ?: false
            
            AppLog.d(TAG, "Hardware connection result: $connected")
            if (connected) {
                AppLog.d(TAG, "Connection successful, attempting to get device ID...")
                // Test connection by getting device ID
                val deviceIdResult = sdk?.getDeviceId()
                AppLog.d(TAG, "Device ID result: success=${deviceIdResult?.isSuccess}, exception=${deviceIdResult?.exceptionOrNull()?.message}")
                
                if (deviceIdResult?.isSuccess == true) {
                    val deviceId = deviceIdResult.getOrNull()
                    AppLog.i(TAG, "Connected to Water Fountain Device: $deviceId")
                    isInitialized = true
                } else {
                    val exception = deviceIdResult?.exceptionOrNull()
                    AppLog.e(TAG, "Failed to get device ID: ${exception?.message}")
                    AppLog.e(TAG, "Exception type: ${exception?.javaClass?.simpleName}")
                    if (exception != null) {
                        AppLog.e(TAG, "Full exception:", exception)
                    }
                    return false
                }
            } else {
                AppLog.e(TAG, "Failed to connect to hardware - SDK connection returned false")
                return false
            }
            
            // Auto-clear faults if configured
            if (config.autoClearFaults) {
                clearFaults()
            }
            
            AppLog.i(TAG, "Water Fountain Manager initialized successfully")
            true
            
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to initialize Water Fountain Manager", e)
            false
        }
    }
    
    /**
     * Disconnect from hardware and cleanup resources
     */
    suspend fun shutdown() {
        try {
            AppLog.d(TAG, "Shutting down Water Fountain Manager...")
            sdk?.disconnect()
            isInitialized = false
            AppLog.i(TAG, "Water Fountain Manager shut down successfully")
        } catch (e: Exception) {
            AppLog.e(TAG, "Error during shutdown", e)
        }
    }
    
    /**
     * Check if the manager is initialized and connected
     */
    fun isReady(): Boolean = isInitialized && (sdk?.isConnected() == true)
    
    /**
     * Get current active water slot/lane
     */
    fun getCurrentSlot(): Int = laneManager.getNextLane()
    
    /**
     * Get comprehensive lane status report for diagnostics
     */
    fun getLaneStatusReport(): LaneStatusReport = laneManager.getLaneStatusReport()
    
    /**
     * Reset all lane statistics (for maintenance)
     */
    fun resetAllLanes() = laneManager.resetAllLanes()
    
    /**
     * Reset specific lane (for maintenance/refill)
     */
    fun resetLane(lane: Int) = laneManager.resetLane(lane)
    
    /**
     * Dispense water using smart lane management
     * This is the main function for water dispensing with automatic fallback
     */
    suspend fun dispenseWater(): WaterDispenseResult {
        if (!isReady()) {
            AppLog.e(TAG, "Water Fountain Manager not ready. Call initialize() first.")
            return WaterDispenseResult(
                success = false,
                slot = 1,
                errorMessage = "Water fountain not ready. Please try again."
            )
        }
        
        return try {
            // Get the best lane for dispensing
            val primaryLane = laneManager.getNextLane()
            AppLog.i(TAG, "Attempting to dispense water from lane $primaryLane...")
            
            // Try primary lane first
            var result = attemptDispenseFromLane(primaryLane)
            
            if (result.success) {
                laneManager.recordSuccess(primaryLane, result.dispensingTimeMs)
                return result
            }
            
            // Primary lane failed, try fallback lanes
            AppLog.w(TAG, "Primary lane $primaryLane failed: ${result.errorMessage}")
            laneManager.recordFailure(primaryLane, result.errorCode, result.errorMessage)
            
            val fallbackLanes = laneManager.getFallbackLanes(primaryLane)
            AppLog.i(TAG, "Trying fallback lanes: $fallbackLanes")
            
            for (fallbackLane in fallbackLanes) {
                AppLog.i(TAG, "Attempting fallback dispensing from lane $fallbackLane...")
                result = attemptDispenseFromLane(fallbackLane)
                
                if (result.success) {
                    AppLog.i(TAG, "Fallback successful from lane $fallbackLane")
                    laneManager.recordSuccess(fallbackLane, result.dispensingTimeMs)
                    return result
                } else {
                    AppLog.w(TAG, "Fallback lane $fallbackLane failed: ${result.errorMessage}")
                    laneManager.recordFailure(fallbackLane, result.errorCode, result.errorMessage)
                }
            }
            
            // All lanes failed
            AppLog.e(TAG, "All lanes failed to dispense water")
            return WaterDispenseResult(
                success = false,
                slot = primaryLane,
                errorMessage = "All water lanes are currently unavailable. Please contact support.",
                dispensingTimeMs = result.dispensingTimeMs
            )
            
        } catch (e: Exception) {
            AppLog.e(TAG, "Exception during water dispensing", e)
            return WaterDispenseResult(
                success = false,
                slot = 1,
                errorMessage = "Unexpected error: ${e.message}"
            )
        }
    }
    
    /**
     * Attempt to dispense water from a specific lane
     */
    private suspend fun attemptDispenseFromLane(lane: Int): WaterDispenseResult {
        return try {
            val result = sdk!!.dispenseWater(lane)
            
            if (result.isSuccess) {
                val dispenseResult = result.getOrThrow()
                if (dispenseResult.success) {
                    AppLog.i(TAG, "Water dispensed successfully from lane $lane in ${dispenseResult.dispensingTimeMs}ms")
                } else {
                    AppLog.w(TAG, "Water dispensing failed from lane $lane: ${dispenseResult.errorMessage}")
                }
                dispenseResult
            } else {
                AppLog.e(TAG, "Water dispensing operation failed for lane $lane: ${result.exceptionOrNull()?.message}")
                WaterDispenseResult(
                    success = false,
                    slot = lane,
                    errorMessage = "Hardware communication error: ${result.exceptionOrNull()?.message}"
                )
            }
            
        } catch (e: Exception) {
            AppLog.e(TAG, "Exception during lane $lane dispensing", e)
            WaterDispenseResult(
                success = false,
                slot = lane,
                errorMessage = "Unexpected error: ${e.message}"
            )
        }
    }
    
    /**
     * Clear any hardware faults
     */
    suspend fun clearFaults(): Boolean {
        if (!isReady()) {
            AppLog.e(TAG, "Cannot clear faults - manager not ready")
            return false
        }
        
        return try {
            AppLog.d(TAG, "Clearing hardware faults...")
            val result = sdk!!.clearFaults()
            val success = result.isSuccess && (result.getOrNull() == true)
            
            if (success) {
                AppLog.i(TAG, "Hardware faults cleared successfully")
            } else {
                AppLog.w(TAG, "Failed to clear faults: ${result.exceptionOrNull()?.message}")
            }
            
            success
        } catch (e: Exception) {
            AppLog.e(TAG, "Exception while clearing faults", e)
            false
        }
    }
    
    /**
     * Get current device status
     */
    suspend fun getDeviceStatus(): String? {
        if (!isReady()) {
            return null
        }
        
        return try {
            val deviceIdResult = sdk!!.getDeviceId()
            if (deviceIdResult.isSuccess) {
                "Connected: ${deviceIdResult.getOrNull()}"
            } else {
                "Error: ${deviceIdResult.exceptionOrNull()?.message}"
            }
        } catch (e: Exception) {
            "Exception: ${e.message}"
        }
    }
    
    /**
     * Test the connection and basic functionality
     */
    suspend fun performHealthCheck(): HealthCheckResult {
        return try {
            if (!isReady()) {
                return HealthCheckResult(
                    success = false,
                    message = "Manager not initialized",
                    details = listOf("Call initialize() first")
                )
            }
            
            val checks = mutableListOf<String>()
            var allPassed = true
            
            // Check 1: Device ID
            val deviceIdResult = sdk!!.getDeviceId()
            if (deviceIdResult.isSuccess) {
                checks.add("✓ Device ID: ${deviceIdResult.getOrNull()}")
            } else {
                checks.add("✗ Device ID failed: ${deviceIdResult.exceptionOrNull()?.message}")
                allPassed = false
            }
            
            // Check 2: Clear faults
            val clearResult = sdk!!.clearFaults()
            if (clearResult.isSuccess && clearResult.getOrNull() == true) {
                checks.add("✓ Fault clearing: OK")
            } else {
                checks.add("✗ Fault clearing failed: ${clearResult.exceptionOrNull()?.message}")
                allPassed = false
            }
            
            // Check 3: Lane status
            val laneReport = laneManager.getLaneStatusReport()
            checks.add("✓ Current Lane: ${laneReport.currentLane}")
            checks.add("✓ Usable Lanes: ${laneReport.usableLanesCount}/${laneReport.lanes.size}")
            checks.add("✓ Total Dispenses: ${laneReport.totalDispenses}")
            
            // Add lane details
            for (lane in laneReport.lanes) {
                if (lane.isUsable) {
                    checks.add("✓ Lane ${lane.lane}: ${lane.getStatusText()} (${lane.successCount} success)")
                } else {
                    checks.add("✗ Lane ${lane.lane}: ${lane.getStatusText()} (${lane.failureCount} failures)")
                    if (laneReport.usableLanesCount > 0) {
                        // Don't fail health check if other lanes are available
                        // allPassed = false
                    } else {
                        allPassed = false
                    }
                }
            }
            
            HealthCheckResult(
                success = allPassed,
                message = if (allPassed) "All systems operational" else "Some issues detected",
                details = checks
            )
            
        } catch (e: Exception) {
            HealthCheckResult(
                success = false,
                message = "Health check failed",
                details = listOf("Exception: ${e.message}")
            )
        }
    }
    
    // Admin panel methods - using real SDK functions
    /**
     * Reset a specific lane in the lane manager
     */
    suspend fun resetSlot(slot: Int): Boolean {
        AppLog.d(TAG, "resetSlot($slot)")
        return try {
            laneManager.resetLane(slot)
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "Error resetting slot $slot", e)
            false
        }
    }
    
    /**
     * Test water dispensing from a specific slot
     */
    suspend fun testDispenser(slot: Int): Boolean {
        AppLog.d(TAG, "testDispenser($slot)")
        
        // Check if system is ready
        if (!isReady()) {
            AppLog.e(TAG, "Cannot test dispenser - system not initialized")
            return false
        }
        
        return try {
            val result = attemptDispenseFromLane(slot)
            result.success
        } catch (e: Exception) {
            AppLog.e(TAG, "Error testing dispenser $slot", e)
            false
        }
    }
    
    /**
     * Clear all faults using SDK clearFaults
     */
    suspend fun clearAllErrors(): Boolean {
        AppLog.d(TAG, "clearAllErrors()")
        
        if (!isReady()) {
            AppLog.e(TAG, "Cannot clear errors - system not initialized")
            return false
        }
        
        return clearFaults()
    }
    
    /**
     * Check if system is connected
     */
    fun isConnected(): Boolean {
        return isReady()
    }
    
    /**
     * Get comprehensive diagnostics using health check
     */
    suspend fun runFullDiagnostics(): Map<String, Any> {
        AppLog.d(TAG, "runFullDiagnostics()")
        val diagnostics = mutableMapOf<String, Any>()
        
        if (!isReady()) {
            diagnostics["error"] = "System not initialized"
            diagnostics["initialized"] = false
            return diagnostics
        }
        
        try {
            val healthCheck = performHealthCheck()
            diagnostics["success"] = healthCheck.success
            diagnostics["message"] = healthCheck.message
            diagnostics["details"] = healthCheck.details.joinToString(", ")
            
            val laneReport = laneManager.getLaneStatusReport()
            diagnostics["currentLane"] = laneReport.currentLane
            diagnostics["usableLanes"] = "${laneReport.usableLanesCount}/${laneReport.lanes.size}"
            diagnostics["totalDispenses"] = laneReport.totalDispenses
            
            val deviceStatus = getDeviceStatus()
            if (deviceStatus != null) {
                diagnostics["deviceId"] = deviceStatus
            }
            diagnostics["initialized"] = true
        } catch (e: Exception) {
            diagnostics["error"] = e.message ?: "Unknown error"
            diagnostics["initialized"] = false
            AppLog.e(TAG, "Error during diagnostics", e)
        }
        return diagnostics
    }
    
    /**
     * Get lane status from lane manager
     */
    suspend fun getSlotStatus(slot: Int): String {
        AppLog.d(TAG, "getSlotStatus($slot)")
        val laneReport = laneManager.getLaneStatusReport()
        val lane = laneReport.lanes.find { it.lane == slot }
        return lane?.getStatusText() ?: "Unknown"
    }
}

/**
 * Result of health check operation
 */
data class HealthCheckResult(
    val success: Boolean,
    val message: String,
    val details: List<String>
)
