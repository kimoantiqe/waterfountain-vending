package com.waterfountainmachine.app.analytics

import android.content.Context
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import com.waterfountainmachine.app.BuildConfig
import com.waterfountainmachine.app.security.SecurityModule
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.utils.CrashlyticsHelper
import com.waterfountainmachine.app.core.slot.SlotInventoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/**
 * Interface for Machine Health Monitor
 * Allows polymorphic behavior between real and mock implementations
 */
interface IMachineHealthMonitor {
    fun start(machineId: String)
    fun stop()
    fun recordDispense(slotNumber: Int, success: Boolean, errorCode: String? = null)
}

/**
 * Monitors machine health and sends heartbeat data via backend functions.
 * Tracks uptime, dispense metrics, and operational status.
 * 
 * Security: All health data requires certificate authentication.
 * Uses SecurityModule to sign requests with machine certificate.
 * 
 * Implementation: Uses coroutines for periodic heartbeats instead of Handler
 */
class MachineHealthMonitor private constructor(private val context: Context) : IMachineHealthMonitor {
    
    private val functions: FirebaseFunctions = Firebase.functions
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var heartbeatJob: Job? = null
    private var machineId: String? = null
    private var isRunning = false
    
    // Session metrics
    private var sessionStartTime: Long = 0
    private var totalDispensesToday = 0
    private var successfulDispensesToday = 0
    private var failedDispensesToday = 0
    private var lastErrorCode: String? = null
    
    companion object {
        private const val TAG = "MachineHealthMonitor"
        private const val COLLECTION_MACHINE_HEALTH = "machineHealth"
        private const val HEARTBEAT_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
        
        @Volatile
        private var instance: MachineHealthMonitor? = null
        
        fun getInstance(context: Context): MachineHealthMonitor {
            return instance ?: synchronized(this) {
                instance ?: MachineHealthMonitor(context.applicationContext).also { instance = it }
            }
        }
    }
    
    /**
     * Start the health monitor
     */
    override fun start(machineId: String) {
        if (isRunning) {
            AppLog.w(TAG, "Health monitor already running")
            return
        }
        
        this.machineId = machineId
        this.sessionStartTime = System.currentTimeMillis()
        this.isRunning = true
        
        // Start periodic heartbeat coroutine
        heartbeatJob = scope.launch {
            // Send initial heartbeat
            sendHealthHeartbeat()
            
            // Schedule periodic heartbeats
            while (isRunning) {
                delay(HEARTBEAT_INTERVAL_MS)
                sendHealthHeartbeat()
            }
        }
        
        AppLog.i(TAG, "Health monitor started for machine: $machineId")
    }
    
    /**
     * Stop the health monitor
     */
    override fun stop() {
        if (!isRunning) {
            return
        }
        
        // Cancel heartbeat coroutine
        heartbeatJob?.cancel()
        heartbeatJob = null
        isRunning = false
        
        // Send final heartbeat
        scope.launch {
            sendHealthHeartbeat()
        }
        
        AppLog.i(TAG, "Health monitor stopped")
    }
    
    /**
     * Clean up resources (call when app is destroyed)
     */
    fun cleanup() {
        stop()
        scope.cancel()
    }
    
    /**
     * Record a dispense attempt
     */
    override fun recordDispense(slotNumber: Int, success: Boolean, errorCode: String?) {
        totalDispensesToday++
        if (success) {
            successfulDispensesToday++
        } else {
            failedDispensesToday++
            lastErrorCode = errorCode
        }
        
        AppLog.d(TAG, "Dispense recorded: slot=$slotNumber, success=$success, total=$totalDispensesToday, successful=$successfulDispensesToday, failed=$failedDispensesToday")
    }
    
    /**
     * Send health heartbeat via backend function
     * Backend validates machine certificate and writes to Firestore
     * 
     * Suspend function with proper error handling
     */
    private suspend fun sendHealthHeartbeat() {
        val currentMachineId = machineId
        if (currentMachineId == null) {
            AppLog.w(TAG, "Cannot send heartbeat: machineId not set")
            return
        }
        
        // Check if machine is enrolled with certificate
        if (!SecurityModule.isEnrolled()) {
            AppLog.w(TAG, "Cannot send heartbeat: Machine not enrolled")
            return
        }
        
        val uptimeSeconds = (System.currentTimeMillis() - sessionStartTime) / 1000
        
        try {
            // Create base payload (matches logMachineHealthSchema in backend)
            val payload = JSONObject().apply {
                put("machineId", currentMachineId)
                put("status", "active")
                put("uptimeSeconds", uptimeSeconds)
                put("totalDispensesToday", totalDispensesToday)
                put("successfulDispensesToday", successfulDispensesToday)
                put("failedDispensesToday", failedDispensesToday)
                put("lastErrorCode", lastErrorCode ?: "none")
                put("appVersion", BuildConfig.VERSION_NAME)
                put("sdkVersion", android.os.Build.VERSION.SDK_INT)
            }
            
            // Add certificate authentication fields (same pattern as BackendSlotService)
            val authenticatedPayload = SecurityModule.createAuthenticatedRequest("logMachineHealth", payload)
            
            // Use coroutines-compatible await() instead of callbacks
            val result = functions
                .getHttpsCallable("logMachineHealth")
                .call(authenticatedPayload.toMap())
                .await()
            
            val data = result.data as? Map<*, *>
            val documentId = data?.get("documentId") as? String
            AppLog.d(TAG, "✅ Health heartbeat sent: uptime=${uptimeSeconds}s, dispenses=$totalDispensesToday, docId=$documentId")
            
        } catch (e: Exception) {
            // Log to AppLog and Crashlytics (standardized error logging)
            AppLog.e(TAG, "❌ Failed to send health heartbeat: uptime=${uptimeSeconds}s", e)
            CrashlyticsHelper.recordException(e)
        }
    }
    
    /**
     * Reset daily counters (call this at midnight or when needed)
     */
    fun resetDailyCounters() {
        totalDispensesToday = 0
        successfulDispensesToday = 0
        failedDispensesToday = 0
        lastErrorCode = null
        AppLog.i(TAG, "Daily counters reset")
    }
    
    /**
     * Get current health status
     */
    fun getHealthStatus(): Map<String, Any> {
        return mapOf(
            "machineId" to (machineId ?: "unknown"),
            "uptimeSeconds" to ((System.currentTimeMillis() - sessionStartTime) / 1000),
            "totalDispensesToday" to totalDispensesToday,
            "successfulDispensesToday" to successfulDispensesToday,
            "failedDispensesToday" to failedDispensesToday,
            "successRate" to if (totalDispensesToday > 0) {
                (successfulDispensesToday.toFloat() / totalDispensesToday * 100).toInt()
            } else 100
        )
    }
    
    /**
     * Extension function to convert JSONObject to Map for Firebase Functions
     * Same implementation as BackendSlotService for consistency
     */
    private fun JSONObject.toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        keys().forEach { key ->
            map[key] = when (val value = get(key)) {
                is JSONObject -> value.toMap()
                is JSONArray -> {
                    val list = mutableListOf<Any?>()
                    for (i in 0 until value.length()) {
                        list.add(value.get(i))
                    }
                    list
                }
                else -> value
            }
        }
        return map
    }
}
