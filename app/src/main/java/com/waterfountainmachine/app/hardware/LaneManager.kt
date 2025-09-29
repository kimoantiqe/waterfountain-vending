package com.waterfountainmachine.app.hardware

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.waterfountainmachine.app.hardware.sdk.WaterDispenseResult
import com.waterfountainmachine.app.hardware.sdk.VmcErrorCodes

/**
 * Smart Lane Management System for Water Fountain Vending Machine
 * 
 * Features:
 * - Tracks current active lane
 * - Automatic fallback to other lanes when current lane is empty/failed
 * - Load balancing across multiple water lanes
 * - Persistent storage of lane status
 * - Smart lane rotation to prevent premature emptying
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
        const val MAX_CONSECUTIVE_FAILURES = 3
        const val LOAD_BALANCE_THRESHOLD = 10 // Switch lanes every N successful dispenses
        
        @Volatile
        private var INSTANCE: LaneManager? = null
        
        fun getInstance(context: Context): LaneManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LaneManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // Configuration - can be made configurable later
    private val totalLanes = 8 // Assume 8 water lanes (1-8)
    private val enabledLanes = (1..totalLanes).toList() // All lanes enabled by default
    
    /**
     * Get the next best lane for water dispensing
     * Uses smart algorithm considering:
     * - Current lane status
     * - Failure rates
     * - Load balancing
     */
    fun getNextLane(): Int {
        val currentLane = getCurrentLane()
        
        // Check if current lane is still good
        if (isLaneUsable(currentLane)) {
            // Check if we should switch for load balancing
            if (shouldSwitchForLoadBalancing(currentLane)) {
                val nextLane = findNextAvailableLane(currentLane)
                if (nextLane != currentLane) {
                    Log.i(TAG, "Switching from lane $currentLane to lane $nextLane for load balancing")
                    setCurrentLane(nextLane)
                    return nextLane
                }
            }
            return currentLane
        }
        
        // Current lane not usable, find next available
        val nextLane = findNextAvailableLane(currentLane)
        if (nextLane != currentLane) {
            Log.i(TAG, "Lane $currentLane not usable, switching to lane $nextLane")
            setCurrentLane(nextLane)
        }
        
        return nextLane
    }
    
    /**
     * Get list of fallback lanes in priority order
     */
    fun getFallbackLanes(excludeLane: Int): List<Int> {
        return enabledLanes
            .filter { it != excludeLane }
            .sortedBy { getLaneFailureCount(it) } // Prefer lanes with fewer failures
            .take(3) // Limit to 3 fallback attempts
    }
    
    /**
     * Record successful dispensing from a lane
     */
    fun recordSuccess(lane: Int, dispensingTimeMs: Long) {
        Log.d(TAG, "Recording success for lane $lane (${dispensingTimeMs}ms)")
        
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
        
        Log.i(TAG, "Lane $lane success count: ${getLaneSuccessCount(lane)}")
    }
    
    /**
     * Record failure for a lane
     */
    fun recordFailure(lane: Int, errorCode: Byte?, errorMessage: String?) {
        Log.w(TAG, "Recording failure for lane $lane: $errorMessage (code: $errorCode)")
        
        val currentFailures = getLaneFailureCount(lane) + 1
        
        prefs.edit().apply {
            putInt(getLaneFailuresKey(lane), currentFailures)
            
            // Check if lane should be marked as empty or failed
            when (errorCode) {
                VmcErrorCodes.MOTOR_FAILURE -> {
                    if (currentFailures >= MAX_CONSECUTIVE_FAILURES) {
                        Log.w(TAG, "Lane $lane disabled due to motor failures")
                        putInt(getLaneStatusKey(lane), LANE_STATUS_FAILED)
                    }
                }
                VmcErrorCodes.OPTICAL_EYE_FAILURE -> {
                    Log.w(TAG, "Lane $lane marked as empty due to optical sensor")
                    putInt(getLaneStatusKey(lane), LANE_STATUS_EMPTY)
                }
                else -> {
                    if (currentFailures >= MAX_CONSECUTIVE_FAILURES) {
                        Log.w(TAG, "Lane $lane disabled due to repeated failures")
                        putInt(getLaneStatusKey(lane), LANE_STATUS_FAILED)
                    }
                }
            }
            
            apply()
        }
    }
    
    /**
     * Check if a lane is usable for dispensing
     */
    private fun isLaneUsable(lane: Int): Boolean {
        val status = getLaneStatus(lane)
        val failureCount = getLaneFailureCount(lane)
        
        return status == LANE_STATUS_ACTIVE && failureCount < MAX_CONSECUTIVE_FAILURES
    }
    
    /**
     * Find the next available lane starting from current lane
     */
    private fun findNextAvailableLane(startLane: Int): Int {
        // Try lanes in order starting from the next one
        val orderedLanes = generateSequence(startLane + 1) { if (it >= totalLanes) 1 else it + 1 }
            .take(totalLanes)
            .filter { enabledLanes.contains(it) }
        
        for (lane in orderedLanes) {
            if (isLaneUsable(lane)) {
                return lane
            }
        }
        
        // If no lanes are usable, return the original lane (will fail but logged)
        Log.e(TAG, "No usable lanes available! Returning lane $startLane")
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
        Log.i(TAG, "Resetting all lane statistics")
        
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
        Log.i(TAG, "Resetting lane $lane")
        
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
