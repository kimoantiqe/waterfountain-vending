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
    private var sdk: IVendingMachineAdapter? = null
    private var isInitialized = false
    
    // Lane management system
    private val laneManager = LaneManager.getInstance(context)
    
    /**
     * Initialize the SDK with current configuration
     * Call this before using any dispensing functions
     */
    suspend fun initialize(): Boolean {
        return try {
            
            AppLog.i(TAG, "========================================")
            AppLog.i(TAG, "Water Fountain Manager Initialization")
            AppLog.i(TAG, "========================================")
            AppLog.d(TAG, config.getConfigSummary())
            
            // Check if we should use real or mock serial communicator
            val prefs = context.getSharedPreferences("system_settings", Context.MODE_PRIVATE)
            val useRealSerial = prefs.getBoolean("use_real_serial", false)
            
            AppLog.i(TAG, "Serial Communicator Configuration:")
            AppLog.i(TAG, "  Mode: ${if (useRealSerial) "REAL HARDWARE (Vendor SDK)" else "MOCK (Testing)"}")
            AppLog.i(TAG, "  Setting Key: use_real_serial = $useRealSerial")
            
            if (useRealSerial) {
                AppLog.i(TAG, "Initializing Vendor SDK Adapter (Real Hardware)...")
                AppLog.i(TAG, "This will use CYVendingMachine SDK with /dev/ttyS0 serial port")
            } else {
                AppLog.i(TAG, "Initializing Mock Adapter (Testing Mode)...")
                AppLog.i(TAG, "This will simulate hardware responses for testing")
            }
            
            // Create SDK adapter (real or mock) using polymorphism
            AppLog.i(TAG, "Creating Vending Machine Adapter...")
            sdk = if (useRealSerial) {
                VendorSDKAdapter(timeoutMs = config.commandTimeoutMs)
            } else {
                MockVendingMachineAdapter(simulateDelayMs = 2000L)
            }
            
            val adapterName = sdk?.javaClass?.simpleName ?: "Unknown"
            AppLog.d(TAG, "✓ Adapter created: $adapterName")
            
            // Initialize adapter
            val initResult = sdk?.initialize()
            if (initResult?.isFailure == true) {
                AppLog.e(TAG, "❌ SDK initialization FAILED: ${initResult.exceptionOrNull()?.message}")
                return false
            }
            
            AppLog.i(TAG, "✅ SDK initialized successfully")
            AppLog.i(TAG, "Note: Vendor SDK uses per-operation connections (opens/closes port per dispense)")
            
            // Verify SDK is ready
            val ready = sdk?.isReady() ?: false
            
            if (ready) {
                AppLog.i(TAG, "✅ Hardware ready for operations")
                isInitialized = true
            } else {
                AppLog.e(TAG, "❌ Hardware not ready - SDK returned false")
                return false
            }
            
            AppLog.i(TAG, "========================================")
            AppLog.i(TAG, "✅ INITIALIZATION COMPLETE")
            AppLog.i(TAG, "========================================")
            AppLog.i(TAG, "Status: Ready for water dispensing")
            AppLog.i(TAG, "Connection: Per-operation (opens/closes serial port each dispense)")
            AppLog.i(TAG, "========================================")
            
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "========================================")
            AppLog.e(TAG, "❌ INITIALIZATION FAILED - EXCEPTION")
            AppLog.e(TAG, "========================================")
            AppLog.e(TAG, "Exception: ${e.javaClass.simpleName}")
            AppLog.e(TAG, "Message: ${e.message}")
            AppLog.e(TAG, "Failed to initialize Water Fountain Manager", e)
            AppLog.e(TAG, "========================================")
            false
        }
    }
    
    /**
     * Shutdown the manager and cleanup resources
     */
    suspend fun shutdown() {
        try {
            AppLog.d(TAG, "Shutting down Water Fountain Manager...")
            sdk?.shutdown()
            isInitialized = false
            AppLog.i(TAG, "Water Fountain Manager shut down successfully")
        } catch (e: Exception) {
            AppLog.e(TAG, "Error during shutdown", e)
        }
    }
    
    /**
     * Check if the manager is initialized and ready for operations
     */
    fun isReady(): Boolean = isInitialized && (sdk?.isReady() == true)
    
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
        
        try {
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
            val sdkInstance = sdk
            if (sdkInstance == null) {
                AppLog.e(TAG, "SDK is not initialized")
                return WaterDispenseResult(
                    success = false,
                    slot = lane,
                    errorMessage = "Hardware not initialized"
                )
            }
            
            // Use polymorphic SDK - no conditionals needed!
            val result = sdkInstance.dispenseWater(lane)
            
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
            
            // Check: Lane status
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
    

    
    /**
     * Dispense water from specific slot with quantity (for testing/debugging)
     */
    suspend fun dispenseWater(slot: Int, quantity: Int): Boolean {
        if (!isReady()) {
            AppLog.e(TAG, "Cannot dispense water - system not initialized")
            return false
        }
        
        return try {
            val sdkInstance = sdk
            if (sdkInstance == null) {
                AppLog.e(TAG, "SDK is not initialized")
                return false
            }
            
            AppLog.d(TAG, "Manual dispense: slot=$slot, quantity=$quantity (quantity not supported by SDK, using 1)")
            val result = sdkInstance.dispenseWater(slot)
            if (result.isSuccess) {
                val dispenseResult = result.getOrThrow()
                dispenseResult.success
            } else {
                AppLog.e(TAG, "Dispense failed: ${result.exceptionOrNull()?.message}")
                false
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Exception during manual dispense", e)
            false
        }
    }
    
    /**
     * Query VMC status directly (for debugging)
     */
    suspend fun queryVmcStatus(): Boolean {
        if (!isReady()) {
            AppLog.e(TAG, "Cannot query status - system not initialized")
            return false
        }
        
        return try {
            val sdkInstance = sdk
            if (sdkInstance == null) {
                AppLog.e(TAG, "SDK is not initialized")
                return false
            }
            
            AppLog.d(TAG, "Querying VMC readiness...")
            // Check if SDK is ready for operations
            val ready = sdkInstance.isReady()
            AppLog.d(TAG, "VMC status: ready=$ready")
            ready
        } catch (e: Exception) {
            AppLog.e(TAG, "Exception querying VMC status", e)
            false
        }
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
