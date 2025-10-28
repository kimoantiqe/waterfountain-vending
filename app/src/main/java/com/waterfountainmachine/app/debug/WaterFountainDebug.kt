package com.waterfountainmachine.app.debug

import android.content.Context
import android.util.Log
import com.waterfountainmachine.app.config.WaterFountainConfig
import com.waterfountainmachine.app.hardware.WaterFountainManager
import kotlinx.coroutines.*

/**
 * Debug utilities for Water Fountain testing and configuration
 */
object WaterFountainDebug {
    private const val TAG = "WaterFountainDebug"
    
    /**
     * Test the complete water fountain system
     */
    suspend fun runSystemTest(context: Context): SystemTestResult {
        val results = mutableListOf<String>()
        var allPassed = true
        
        try {
            results.add("=== Water Fountain System Test ===")
            
            // Test 1: Configuration
            results.add("\n1. Testing Configuration...")
            val config = WaterFountainConfig.getInstance(context)
            results.add("✓ Config loaded successfully")
            results.add("  - Water Slot: ${config.waterSlot}")
            results.add("  - Serial Baud Rate: ${config.serialBaudRate}")
            results.add("  - Command Timeout: ${config.commandTimeoutMs}ms")
            results.add("  - Status Polling Interval: ${config.statusPollingIntervalMs}ms")
            results.add("  - Max Polling Attempts: ${config.maxPollingAttempts}")
            
            // Test 2: Manager Initialization
            results.add("\n2. Testing Manager Initialization...")
            val manager = WaterFountainManager.getInstance(context)
            val initResult = manager.initialize()
            
            if (initResult) {
                results.add("✓ Water Fountain Manager initialized successfully")
                
                // Test 3: Health Check
                results.add("\n3. Running Health Check...")
                val healthCheck = manager.performHealthCheck()
                
                if (healthCheck.success) {
                    results.add("✓ Health check passed: ${healthCheck.message}")
                    for (detail in healthCheck.details) {
                        results.add("  $detail")
                    }
                } else {
                    results.add("✗ Health check failed: ${healthCheck.message}")
                    for (detail in healthCheck.details) {
                        results.add("  $detail")
                    }
                    allPassed = false
                }
                
                // Test 4: Water Dispensing Test
                results.add("\n4. Testing Water Dispensing...")
                val dispenseResult = manager.dispenseWater()
                
                if (dispenseResult.success) {
                    results.add("✓ Water dispensing test successful")
                    results.add("  - Slot: ${dispenseResult.slot}")
                    results.add("  - Dispensing Time: ${dispenseResult.dispensingTimeMs}ms")
                } else {
                    results.add("✗ Water dispensing test failed")
                    results.add("  - Slot: ${dispenseResult.slot}")
                    results.add("  - Error: ${dispenseResult.errorMessage}")
                    results.add("  - Error Code: ${dispenseResult.errorCode}")
                    allPassed = false
                }
                
                // Test 5: Cleanup
                results.add("\n5. Cleaning up...")
                manager.shutdown()
                results.add("✓ Manager shutdown completed")
                
            } else {
                results.add("✗ Manager initialization failed")
                allPassed = false
            }
            
        } catch (e: Exception) {
            results.add("✗ System test exception: ${e.message}")
            Log.e(TAG, "System test exception", e)
            allPassed = false
        }
        
        results.add("\n=== Test Complete ===")
        results.add(if (allPassed) "✓ ALL TESTS PASSED" else "✗ SOME TESTS FAILED")
        
        return SystemTestResult(allPassed, if (allPassed) "All tests passed" else "Some tests failed", results)
    }
    
    /**
     * Configure the water fountain with optimal settings for testing
     */
    fun configureForTesting(context: Context) {
        val config = WaterFountainConfig.getInstance(context)
        
        // Set testing-friendly values
        config.waterSlot = 1
        config.serialBaudRate = 9600
        config.commandTimeoutMs = 3000L  // Shorter for testing
        config.statusPollingIntervalMs = 200L
        config.maxPollingAttempts = 10  // Fewer for faster testing
        
        Log.i(TAG, "Water fountain configured for testing")
        Log.i(TAG, config.getConfigSummary())
    }
    
    /**
     * Configure the water fountain for production use
     */
    fun configureForProduction(context: Context) {
        val config = WaterFountainConfig.getInstance(context)
        
        // Set production values
        config.waterSlot = 1
        config.serialBaudRate = 9600
        config.commandTimeoutMs = 5000L
        config.statusPollingIntervalMs = 500L
        config.maxPollingAttempts = 20
        
        Log.i(TAG, "Water fountain configured for production")
        Log.i(TAG, config.getConfigSummary())
    }
    
    /**
     * Reset configuration to defaults
     */
    fun resetConfiguration(context: Context) {
        val config = WaterFountainConfig.getInstance(context)
        config.resetToDefaults()
        Log.i(TAG, "Configuration reset to defaults")
    }
    
    /**
     * Test lane management system specifically
     */
    suspend fun testLaneManagement(context: Context): TestResult {
        val results = mutableListOf<String>()
        var allPassed = true
        
        try {
            results.add("=== Lane Management System Test ===")
            
            val manager = WaterFountainManager.getInstance(context)
            val initResult = manager.initialize()
            
            if (initResult) {
                results.add("✓ Manager initialized for lane testing")
                
                // Test 1: Lane Status Report
                results.add("\n1. Testing Lane Status Report...")
                val laneReport = manager.getLaneStatusReport()
                results.add("✓ Lane status report generated")
                results.add("  - Current Lane: ${laneReport.currentLane}")
                results.add("  - Total Dispenses: ${laneReport.totalDispenses}")
                results.add("  - Usable Lanes: ${laneReport.usableLanesCount}/${laneReport.lanes.size}")
                
                // Show details for each lane
                for (lane in laneReport.lanes) {
                    val status = if (lane.isUsable) "✓" else "✗"
                    results.add("  $status Lane ${lane.lane}: ${lane.getStatusText()} | Success: ${lane.successCount} | Failures: ${lane.failureCount}")
                }
                
                // Test 2: Multiple Dispensing Test
                results.add("\n2. Testing Multiple Dispenses (Lane Switching)...")
                repeat(3) { i ->
                    val dispenseResult = manager.dispenseWater()
                    if (dispenseResult.success) {
                        results.add("✓ Dispense ${i + 1}: Lane ${dispenseResult.slot} (${dispenseResult.dispensingTimeMs}ms)")
                    } else {
                        results.add("✗ Dispense ${i + 1} failed: ${dispenseResult.errorMessage}")
                        allPassed = false
                    }
                }
                
                // Test 3: Updated Lane Report
                results.add("\n3. Updated Lane Status...")
                val updatedReport = manager.getLaneStatusReport()
                results.add("✓ Current Lane: ${updatedReport.currentLane}")
                results.add("✓ Total Dispenses: ${updatedReport.totalDispenses}")
                
                manager.shutdown()
                results.add("\n4. Cleanup completed")
                
            } else {
                results.add("✗ Manager initialization failed")
                allPassed = false
            }
            
        } catch (e: Exception) {
            results.add("✗ Lane management test exception: ${e.message}")
            Log.e(TAG, "Lane management test exception", e)
            allPassed = false
        }
        
        results.add("\n=== Lane Test Complete ===")
        results.add(if (allPassed) "✓ ALL LANE TESTS PASSED" else "✗ SOME LANE TESTS FAILED")
        
        return TestResult(allPassed, results)
    }
}

/**
 * Result of system test
 */
data class SystemTestResult(
    val success: Boolean,
    val message: String,
    val details: List<String>
) {
    fun printToLog() {
        for (detail in details) {
            Log.d("WaterFountainTest", detail)
        }
    }
}

/**
 * Result of test
 */
data class TestResult(
    val success: Boolean,
    val details: List<String>
) {
    fun printToLog() {
        for (detail in details) {
            Log.d("WaterFountainTest", detail)
        }
    }
}
