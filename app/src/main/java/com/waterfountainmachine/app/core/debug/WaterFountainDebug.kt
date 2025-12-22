package com.waterfountainmachine.app.debug

import android.content.Context
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.config.WaterFountainConfig
import com.waterfountainmachine.app.hardware.WaterFountainManager
import kotlinx.coroutines.*

/**
 * Debug utilities for Water Fountain testing
 */
object WaterFountainDebug {
    private const val TAG = "WaterFountainDebug"
    
    suspend fun runSystemTest(context: Context): SystemTestResult {
        val results = mutableListOf<String>()
        var allPassed = true
        
        try {
            results.add("=== Water Fountain System Test ===")
            
            results.add("\n1. Testing Configuration...")
            val config = WaterFountainConfig.getInstance(context)
            results.add("[PASS] Config loaded successfully")
            results.add("  - Water Slot: ${config.waterSlot}")
            results.add("  - Serial Baud Rate: ${config.serialBaudRate}")
            results.add("  - Status Polling Interval: ${config.statusPollingIntervalMs}ms")
            results.add("  - Max Polling Attempts: ${config.maxPollingAttempts}")
            
            results.add("\n2. Testing Manager Initialization...")
            val manager = WaterFountainManager.getInstance(context)
            val initResult = manager.initialize()
            
            if (initResult) {
                results.add("[PASS] Water Fountain Manager initialized successfully")
                
                results.add("\n3. Running Health Check...")
                val healthCheck = manager.performHealthCheck()
                
                if (healthCheck.success) {
                    results.add("[PASS] Health check passed: ${healthCheck.message}")
                    for (detail in healthCheck.details) {
                        results.add("  $detail")
                    }
                } else {
                    results.add("[FAIL] Health check failed: ${healthCheck.message}")
                    for (detail in healthCheck.details) {
                        results.add("  $detail")
                    }
                    allPassed = false
                }
                
                results.add("\n4. Testing Water Dispensing...")
                val dispenseResult = manager.dispenseWater()
                
                if (dispenseResult.success) {
                    results.add("[PASS] Water dispensing test successful")
                    results.add("  - Slot: ${dispenseResult.slot}")
                    results.add("  - Dispensing Time: ${dispenseResult.dispensingTimeMs}ms")
                } else {
                    results.add("[FAIL] Water dispensing test failed")
                    results.add("  - Slot: ${dispenseResult.slot}")
                    results.add("  - Error: ${dispenseResult.errorMessage}")
                    results.add("  - Error Code: ${dispenseResult.errorCode}")
                    allPassed = false
                }
                
                results.add("\n5. Cleaning up...")
                manager.shutdown()
                results.add("[PASS] Manager shutdown completed")
                
            } else {
                results.add("[FAIL] Manager initialization failed")
                allPassed = false
            }
            
        } catch (e: Exception) {
            results.add("[FAIL] System test exception: ${e.message}")
            AppLog.e(TAG, "System test exception", e)
            allPassed = false
        }
        
        results.add("\n=== Test Complete ===")
        results.add(if (allPassed) "[PASS] ALL TESTS PASSED" else "[FAIL] SOME TESTS FAILED")
        
        return SystemTestResult(allPassed, if (allPassed) "All tests passed" else "Some tests failed", results)
    }
    
    fun configureForTesting(context: Context) {
        val config = WaterFountainConfig.getInstance(context)
        
        config.waterSlot = 1
        config.serialBaudRate = 9600
        config.statusPollingIntervalMs = WaterFountainConfig.DEBUG_FAST_POLLING_INTERVAL_MS
        config.maxPollingAttempts = 10
        
        AppLog.i(TAG, "Water fountain configured for testing")
        AppLog.i(TAG, config.getConfigSummary())
    }
    
    fun configureForProduction(context: Context) {
        val config = WaterFountainConfig.getInstance(context)
        
        config.waterSlot = 1
        config.serialBaudRate = 9600
        config.statusPollingIntervalMs = WaterFountainConfig.DEBUG_SLOW_POLLING_INTERVAL_MS
        config.maxPollingAttempts = 20
        
        AppLog.i(TAG, "Water fountain configured for production")
        AppLog.i(TAG, config.getConfigSummary())
    }
    fun resetConfiguration(context: Context) {
        val config = WaterFountainConfig.getInstance(context)
        config.resetToDefaults()
        AppLog.i(TAG, "Configuration reset to defaults")
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
                results.add("[PASS] Manager initialized for lane testing")
                
                results.add("\n1. Testing Lane Status Report...")
                val laneReport = manager.getLaneStatusReport()
                results.add("[PASS] Lane status report generated")
                results.add("  - Current Lane: ${laneReport.currentLane}")
                results.add("  - Total Dispenses: ${laneReport.totalDispenses}")
                results.add("  - Usable Lanes: ${laneReport.usableLanesCount}/${laneReport.lanes.size}")
                
                for (lane in laneReport.lanes) {
                    val status = if (lane.isUsable) "[OK]" else "[FAIL]"
                    results.add("  $status Lane ${lane.lane}: ${lane.getStatusText()} | Success: ${lane.successCount} | Failures: ${lane.failureCount}")
                }
                
                results.add("\n2. Testing Multiple Dispenses (Lane Switching)...")
                repeat(3) { i ->
                    val dispenseResult = manager.dispenseWater()
                    if (dispenseResult.success) {
                        results.add("[PASS] Dispense ${i + 1}: Lane ${dispenseResult.slot} (${dispenseResult.dispensingTimeMs}ms)")
                    } else {
                        results.add("[FAIL] Dispense ${i + 1} failed: ${dispenseResult.errorMessage}")
                        allPassed = false
                    }
                }
                
                results.add("\n3. Updated Lane Status...")
                val updatedReport = manager.getLaneStatusReport()
                results.add("[PASS] Current Lane: ${updatedReport.currentLane}")
                results.add("[PASS] Total Dispenses: ${updatedReport.totalDispenses}")
                
                manager.shutdown()
                results.add("\n4. Cleanup completed")
                
            } else {
                results.add("[FAIL] Manager initialization failed")
                allPassed = false
            }
            
        } catch (e: Exception) {
            results.add("[FAIL] Lane management test exception: ${e.message}")
            AppLog.e(TAG, "Lane management test exception", e)
            allPassed = false
        }
        
        results.add("\n=== Lane Test Complete ===")
        results.add(if (allPassed) "[PASS] ALL LANE TESTS PASSED" else "[FAIL] SOME LANE TESTS FAILED")
        
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
            AppLog.d("WaterFountainTest", detail)
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
            AppLog.d("WaterFountainTest", detail)
        }
    }
}
