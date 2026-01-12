package com.waterfountainmachine.app.hardware

import android.content.Context
import android.content.SharedPreferences
import com.waterfountainmachine.app.hardware.sdk.SlotValidator
import com.waterfountainmachine.app.hardware.sdk.WaterDispenseResult
import com.waterfountainmachine.app.core.slot.SlotInventoryManager
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.config.WaterFountainConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Smart Lane Management System for Water Fountain Vending Machine
 * 
 * Features:
 * - Tracks current active slot (48 total slots in 6 rows)
 * - Automatic fallback to other slots when current slot is empty/failed
 * - Load balancing across multiple water slots
 * - Persistent storage of slot status
 * - Smart slot rotation to prevent premature emptying
 * 
 * Valid slots: 1-8, 11-18, 21-28, 31-38, 41-48, 51-58
 */
class LaneManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "LaneManager"
        private const val PREFS_NAME = "lane_manager_prefs"
        private const val PREF_CURRENT_LANE = "current_lane"
        private const val PREF_LANE_PREFIX = "lane_"
        private const val PREF_LANE_STATUS_SUFFIX = "_status"
        private const val PREF_LANE_FAILURES_SUFFIX = "_failures"
        private const val PREF_LANE_SUCCESS_COUNT_SUFFIX = "_success_count"
        private const val PREF_TOTAL_DISPENSES = "total_dispenses"
        
        // Lane status constants
        const val LANE_STATUS_ACTIVE = 0
        const val LANE_STATUS_EMPTY = 1
        const val LANE_STATUS_FAILED = 2
        const val LANE_STATUS_DISABLED = 3
        
        // Configuration
        const val MAX_CONSECUTIVE_FAILURES = WaterFountainConfig.MAX_CONSECUTIVE_SLOT_FAILURES
        const val LOAD_BALANCE_THRESHOLD = WaterFountainConfig.LOAD_BALANCE_THRESHOLD
        
        @Volatile
        private var INSTANCE: LaneManager? = null
        
        fun getInstance(context: Context): LaneManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LaneManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val slotInventoryManager: SlotInventoryManager by lazy {
        SlotInventoryManager.getInstance(context)
    }
    
    // Configuration - Column-first rotation pattern
    // Pattern: Row1-Slot1, Row2-Slot1, Row3-Slot1, Row4-Slot1, Row5-Slot1, Row6-Slot1,
    //          then Row1-Slot2, Row2-Slot2, Row3-Slot2... etc.
    // This distributes cans evenly across all rows before moving to next column
    private val enabledLanes = listOf(
        // Column 1 (all rows)
        1, 11, 21, 31, 41, 51,
        // Column 2 (all rows)
        2, 12, 22, 32, 42, 52,
        // Column 3 (all rows)
        3, 13, 23, 33, 43, 53,
        // Column 4 (all rows)
        4, 14, 24, 34, 44, 54,
        // Column 5 (all rows)
        5, 15, 25, 35, 45, 55,
        // Column 6 (all rows)
        6, 16, 26, 36, 46, 56,
        // Column 7 (all rows)
        7, 17, 27, 37, 47, 57,
        // Column 8 (all rows)
        8, 18, 28, 38, 48, 58
    )
    
    /**
     * Get the next best lane for water dispensing
     * Uses smart algorithm considering:
     * - Current lane status
     * - Failure rates
     * - Load balancing
     * - Inventory availability
     */
    fun getNextLane(): Int {
        return getNextLaneWithInventoryCheck()
    }
    
    /**
     * Get the next best lane with inventory availability check
     * Private implementation that considers both hardware status and inventory
     */
    private fun getNextLaneWithInventoryCheck(): Int {
        val currentLane = getCurrentLane()
        
        // Check if current lane is still good
        if (isLaneUsable(currentLane)) {
            // Check if we should switch for load balancing
            if (shouldSwitchForLoadBalancing(currentLane)) {
                val nextLane = findNextAvailableLane(currentLane)
                if (nextLane != currentLane) {
                    AppLog.i(TAG, "Switching from lane $currentLane to lane $nextLane for load balancing")
                    setCurrentLane(nextLane)
                    return nextLane
                }
            }
            return currentLane
        }
        
        // Current lane not usable, find next available
        val nextLane = findNextAvailableLane(currentLane)
        if (nextLane != currentLane) {
            AppLog.i(TAG, "Lane $currentLane not usable, switching to lane $nextLane")
            setCurrentLane(nextLane)
        }
        
        return nextLane
    }
    
    /**
     * Get list of fallback lanes in priority order
     * Filters by inventory availability and hardware status
     */
    fun getFallbackLanes(excludeLane: Int): List<Int> {
        return enabledLanes
            .filter { it != excludeLane }
            .filter { slotInventoryManager.isSlotAvailable(it) } // Check inventory
            .filter { isLaneUsableHardwareOnly(it) } // Check hardware status
            .sortedBy { getLaneFailureCount(it) } // Prefer lanes with fewer failures
            .take(WaterFountainConfig.MAX_FALLBACK_ATTEMPTS)
    }
    
    /**
     * Record successful dispensing from a lane
     */
    fun recordSuccess(lane: Int, dispensingTimeMs: Long) {
        AppLog.d(TAG, "Recording success for lane $lane (${dispensingTimeMs}ms)")
        
        prefs.edit().apply {
            // Reset failure count on success
            putInt(getLaneFailuresKey(lane), 0)
            
            // Increment success count
            val successCount = getLaneSuccessCount(lane) + 1
            putInt(getLaneSuccessCountKey(lane), successCount)
            
            // Update total dispenses
            val totalDispenses = prefs.getInt(PREF_TOTAL_DISPENSES, 0) + 1
            putInt(PREF_TOTAL_DISPENSES, totalDispenses)
            
            // Keep lane active
            putInt(getLaneStatusKey(lane), LANE_STATUS_ACTIVE)
            
            apply()
        }
        
        AppLog.i(TAG, "Lane $lane success count: ${getLaneSuccessCount(lane)}")
        
        // Move to next lane in rotation after successful dispense
        val nextLane = findNextAvailableLane(lane)
        if (nextLane != lane) {
            AppLog.i(TAG, "Advancing to next lane: $nextLane")
            setCurrentLane(nextLane)
        } else {
            AppLog.w(TAG, "No other lanes available, staying on lane $lane")
        }
    }
    
    /**
     * Record failure for a lane
     * After 3 failures, updates backend to disable the slot
     */
    fun recordFailure(lane: Int, errorCode: Byte?, errorMessage: String?) {
        AppLog.w(TAG, "Recording failure for lane $lane: $errorMessage (code: $errorCode)")
        
        val currentFailures = getLaneFailureCount(lane) + 1
        var shouldUpdateBackend = false
        
        prefs.edit().apply {
            putInt(getLaneFailuresKey(lane), currentFailures)
            
            // Check if lane should be marked as empty or failed
            // Error codes from vendor SDK:
            // 0x02 = Motor failure
            // 0x03 = Optical sensor failure
            when (errorCode) {
                null -> {
                    // Unknown error - mark as failed after threshold
                    if (currentFailures >= MAX_CONSECUTIVE_FAILURES) {
                        AppLog.w(TAG, "Lane $lane disabled due to unknown repeated failures")
                        putInt(getLaneStatusKey(lane), LANE_STATUS_FAILED)
                        shouldUpdateBackend = true
                    }
                }
                0x02.toByte() -> { // MOTOR_FAILURE
                    if (currentFailures >= MAX_CONSECUTIVE_FAILURES) {
                        AppLog.w(TAG, "Lane $lane disabled due to motor failures")
                        putInt(getLaneStatusKey(lane), LANE_STATUS_FAILED)
                        shouldUpdateBackend = true
                    }
                }
                0x03.toByte() -> { // OPTICAL_EYE_FAILURE
                    AppLog.w(TAG, "Lane $lane marked as empty due to optical sensor")
                    putInt(getLaneStatusKey(lane), LANE_STATUS_EMPTY)
                    shouldUpdateBackend = true // Mark as empty in backend too
                }
                else -> {
                    if (currentFailures >= MAX_CONSECUTIVE_FAILURES) {
                        AppLog.w(TAG, "Lane $lane disabled due to repeated failures (code: $errorCode)")
                        putInt(getLaneStatusKey(lane), LANE_STATUS_FAILED)
                        shouldUpdateBackend = true
                    }
                }
            }
            
            apply()
        }
        
        // Update backend slot status if slot was disabled
        if (shouldUpdateBackend) {
            updateBackendSlotStatus(lane, errorCode)
        }
    }
    
    /**
     * Update backend slot status after failures
     */
    private fun updateBackendSlotStatus(lane: Int, errorCode: Byte?) {
        val backendSlotService = com.waterfountainmachine.app.di.BackendModule.getBackendSlotService(context)
        val machineId = com.waterfountainmachine.app.security.SecurityModule.getMachineId()
        
        if (machineId == null) {
            AppLog.w(TAG, "Cannot update backend - machine ID not found")
            return
        }
        
        // Determine status based on error code
        val status = if (errorCode == 0x03.toByte()) "empty" else "disabled"
        
        // Use coroutine to update backend asynchronously
        CoroutineScope(Dispatchers.IO).launch {
            backendSlotService.updateSlotStatus(machineId, lane, status).fold(
                onSuccess = {
                    AppLog.i(TAG, "Backend slot $lane status updated to $status")
                },
                onFailure = { error ->
                    AppLog.e(TAG, "Failed to update backend slot status", error)
                }
            )
        }
    }
    
    /**
     * Check if a lane is usable for dispensing
     * Now includes inventory availability check
     */
    private fun isLaneUsable(lane: Int): Boolean {
        val status = getLaneStatus(lane)
        val failureCount = getLaneFailureCount(lane)
        val hardwareUsable = status == LANE_STATUS_ACTIVE && failureCount < MAX_CONSECUTIVE_FAILURES
        
        // Also check inventory availability
        val inventoryAvailable = slotInventoryManager.isSlotAvailable(lane)
        
        return hardwareUsable && inventoryAvailable
    }
    
    /**
     * Check if a lane is usable (hardware only, without inventory check)
     * Used for fallback scenarios
     */
    private fun isLaneUsableHardwareOnly(lane: Int): Boolean {
        val status = getLaneStatus(lane)
        val failureCount = getLaneFailureCount(lane)
        return status == LANE_STATUS_ACTIVE && failureCount < MAX_CONSECUTIVE_FAILURES
    }
    
    /**
     * Find the next available lane starting from current lane
     */
    private fun findNextAvailableLane(startLane: Int): Int {
        // Try slots in order starting from the next valid one
        val startIndex = enabledLanes.indexOf(startLane)
        if (startIndex == -1) return enabledLanes.first()
        
        // Check slots after the current one
        for (i in (startIndex + 1) until enabledLanes.size) {
            if (isLaneUsable(enabledLanes[i])) {
                return enabledLanes[i]
            }
        }
        
        // Wrap around to check slots before the current one
        for (i in 0 until startIndex) {
            if (isLaneUsable(enabledLanes[i])) {
                return enabledLanes[i]
            }
        }
        
        // If no slots are usable, return the original slot (will fail but logged)
        AppLog.e(TAG, "No usable slots available! Returning slot $startLane")
        return startLane
    }
    
    /**
     * Check if we should switch lanes for load balancing
     */
    private fun shouldSwitchForLoadBalancing(currentLane: Int): Boolean {
        val successCount = getLaneSuccessCount(currentLane)
        return successCount > 0 && successCount % LOAD_BALANCE_THRESHOLD == 0
    }
    
    /**
     * Get current active lane
     */
    private fun getCurrentLane(): Int {
        return prefs.getInt(PREF_CURRENT_LANE, 1) // Default to lane 1
    }
    
    /**
     * Set current active lane
     */
    private fun setCurrentLane(lane: Int) {
        prefs.edit().putInt(PREF_CURRENT_LANE, lane).apply()
    }
    
    /**
     * Get lane status
     */
    private fun getLaneStatus(lane: Int): Int {
        return prefs.getInt(getLaneStatusKey(lane), LANE_STATUS_ACTIVE)
    }
    
    /**
     * Get lane failure count
     */
    private fun getLaneFailureCount(lane: Int): Int {
        return prefs.getInt(getLaneFailuresKey(lane), 0)
    }
    
    /**
     * Get lane success count
     */
    private fun getLaneSuccessCount(lane: Int): Int {
        return prefs.getInt(getLaneSuccessCountKey(lane), 0)
    }
    
    // Preference key helpers
    private fun getLaneStatusKey(lane: Int) = "$PREF_LANE_PREFIX$lane$PREF_LANE_STATUS_SUFFIX"
    private fun getLaneFailuresKey(lane: Int) = "$PREF_LANE_PREFIX$lane$PREF_LANE_FAILURES_SUFFIX"
    private fun getLaneSuccessCountKey(lane: Int) = "$PREF_LANE_PREFIX$lane$PREF_LANE_SUCCESS_COUNT_SUFFIX"
    
    /**
     * Get comprehensive lane status report
     */
    fun getLaneStatusReport(): LaneStatusReport {
        val laneStatuses = enabledLanes.map { lane ->
            LaneInfo(
                lane = lane,
                status = getLaneStatus(lane),
                failureCount = getLaneFailureCount(lane),
                successCount = getLaneSuccessCount(lane),
                isUsable = isLaneUsable(lane)
            )
        }
        
        return LaneStatusReport(
            currentLane = getCurrentLane(),
            totalDispenses = prefs.getInt(PREF_TOTAL_DISPENSES, 0),
            lanes = laneStatuses,
            usableLanesCount = laneStatuses.count { it.isUsable }
        )
    }
    
    /**
     * Reset all lane statistics (for maintenance)
     */
    fun resetAllLanes() {
        AppLog.i(TAG, "Resetting all lane statistics")
        
        prefs.edit().apply {
            enabledLanes.forEach { lane ->
                putInt(getLaneStatusKey(lane), LANE_STATUS_ACTIVE)
                putInt(getLaneFailuresKey(lane), 0)
                putInt(getLaneSuccessCountKey(lane), 0)
            }
            putInt(PREF_CURRENT_LANE, 1)
            putInt(PREF_TOTAL_DISPENSES, 0)
            apply()
        }
    }
    
    /**
     * Reset specific lane (for maintenance/refill)
     */
    fun resetLane(lane: Int) {
        AppLog.i(TAG, "Resetting lane $lane")
        
        prefs.edit().apply {
            putInt(getLaneStatusKey(lane), LANE_STATUS_ACTIVE)
            putInt(getLaneFailuresKey(lane), 0)
            apply()
        }
    }
}

/**
 * Data classes for lane management
 */
data class LaneInfo(
    val lane: Int,
    val status: Int,
    val failureCount: Int,
    val successCount: Int,
    val isUsable: Boolean
) {
    fun getStatusText(): String = when (status) {
        LaneManager.LANE_STATUS_ACTIVE -> "Active"
        LaneManager.LANE_STATUS_EMPTY -> "Empty"
        LaneManager.LANE_STATUS_FAILED -> "Failed"
        LaneManager.LANE_STATUS_DISABLED -> "Disabled"
        else -> "Unknown"
    }
}

data class LaneStatusReport(
    val currentLane: Int,
    val totalDispenses: Int,
    val lanes: List<LaneInfo>,
    val usableLanesCount: Int
) {
    fun printReport(): String {
        val sb = StringBuilder()
        sb.appendLine("=== Lane Status Report ===")
        sb.appendLine("Current Lane: $currentLane")
        sb.appendLine("Total Dispenses: $totalDispenses")
        sb.appendLine("Usable Lanes: $usableLanesCount/${lanes.size}")
        sb.appendLine("")
        
        lanes.forEach { lane ->
            sb.appendLine("Lane ${lane.lane}: ${lane.getStatusText()} | Failures: ${lane.failureCount} | Success: ${lane.successCount}")
        }
        
        return sb.toString()
    }
}
