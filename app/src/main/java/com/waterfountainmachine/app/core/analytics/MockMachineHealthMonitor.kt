package com.waterfountainmachine.app.analytics

import android.content.Context
import com.waterfountainmachine.app.utils.AppLog

/**
 * Mock Machine Health Monitor
 * 
 * Does not send any health data to backend.
 * Logs all operations locally for debugging.
 */
class MockMachineHealthMonitor(private val context: Context) : IMachineHealthMonitor {
    
    private var machineId: String? = null
    private var isRunning = false
    
    companion object {
        private const val TAG = "MockHealthMonitor"
    }
    
    /**
     * Start the mock health monitor (no-op)
     */
    override fun start(machineId: String) {
        this.machineId = machineId
        this.isRunning = true
        AppLog.i(TAG, "[MOCK] Health monitor started for machine: $machineId (no heartbeats sent)")
    }
    
    /**
     * Stop the mock health monitor (no-op)
     */
    override fun stop() {
        if (!isRunning) {
            return
        }
        
        isRunning = false
        AppLog.i(TAG, "[MOCK] Health monitor stopped")
    }
    
    /**
     * Record dispense event (mock - just logs)
     */
    override fun recordDispense(slotNumber: Int, success: Boolean, errorCode: String?) {
        if (!isRunning) {
            AppLog.w(TAG, "[MOCK] Cannot record dispense - monitor not running")
            return
        }
        
        val status = if (success) "SUCCESS" else "FAILED"
        val error = if (errorCode != null) " (error: $errorCode)" else ""
        AppLog.i(TAG, "[MOCK] Dispense recorded: slot=$slotNumber, status=$status$error")
    }
}
