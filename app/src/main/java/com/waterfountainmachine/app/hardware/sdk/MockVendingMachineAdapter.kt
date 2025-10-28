package com.waterfountainmachine.app.hardware.sdk

import android.util.Log
import kotlinx.coroutines.delay

/**
 * Mock implementation of vending machine adapter for testing.
 * Simulates hardware responses without actual hardware communication.
 */
class MockVendingMachineAdapter(
    private val simulateDelayMs: Long = 2000L
) : IVendingMachineAdapter {
    
    private val tag = "MockVendingAdapter"
    
    @Volatile
    private var isReadyState = false
    
    override fun initialize(): Result<Unit> {
        Log.i(tag, "Mock adapter initialized (no real hardware)")
        isReadyState = true
        return Result.success(Unit)
    }
    
    override fun isReady(): Boolean {
        return isReadyState
    }
    

    
    override suspend fun dispenseWater(slot: Int): Result<WaterDispenseResult> {
        Log.i(tag, "Mock dispenseWater: slot=$slot")
        
        // Validate slot
        if (!SlotValidator.isValidSlot(slot)) {
            Log.w(tag, "Mock mode: Invalid slot $slot")
            return Result.success(
                WaterDispenseResult(
                    success = false,
                    slot = slot,
                    errorCode = 0x01,
                    errorMessage = "Invalid slot number",
                    dispensingTimeMs = 0
                )
            )
        }
        
        // Simulate dispensing delay
        delay(simulateDelayMs)
        
        // Simulate 95% success rate (5% random failures for testing)
        val isSuccess = (0..100).random() <= 95
        
        return if (isSuccess) {
            Log.i(tag, "Mock mode: Water dispensed successfully from slot $slot")
            Result.success(
                WaterDispenseResult(
                    success = true,
                    slot = slot,
                    errorCode = null,
                    errorMessage = null,
                    dispensingTimeMs = simulateDelayMs
                )
            )
        } else {
            // Simulate random error
            val errorType = (1..3).random()
            when (errorType) {
                1 -> {
                    Log.w(tag, "Mock mode: Simulating motor failure")
                    Result.success(
                        WaterDispenseResult(
                            success = false,
                            slot = slot,
                            errorCode = 0x02,
                            errorMessage = "Mock motor failure",
                            dispensingTimeMs = simulateDelayMs
                        )
                    )
                }
                2 -> {
                    Log.w(tag, "Mock mode: Simulating optical sensor failure")
                    Result.success(
                        WaterDispenseResult(
                            success = false,
                            slot = slot,
                            errorCode = 0x03,
                            errorMessage = "Mock optical sensor failure",
                            dispensingTimeMs = simulateDelayMs
                        )
                    )
                }
                else -> {
                    Log.w(tag, "Mock mode: Simulating unknown error")
                    Result.success(
                        WaterDispenseResult(
                            success = false,
                            slot = slot,
                            errorCode = 0xFF.toByte(),
                            errorMessage = "Mock unknown error",
                            dispensingTimeMs = simulateDelayMs
                        )
                    )
                }
            }
        }
    }
    
    override fun shutdown() {
        Log.i(tag, "Mock adapter shutdown")
        isReadyState = false
    }
    
    override fun getHardwareInfo(): Map<String, String> {
        return mapOf(
            "adapter" to "MockVendingMachineAdapter",
            "mode" to "MOCK/TESTING",
            "simulated_delay_ms" to simulateDelayMs.toString(),
            "valid_slots" to "48 slots (6 rows × 8 columns)",
            "success_rate" to "95%",
            "ready" to isReadyState.toString()
        )
    }
}
