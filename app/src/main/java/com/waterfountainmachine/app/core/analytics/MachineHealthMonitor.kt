package com.waterfountainmachine.app.analytics

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import com.waterfountainmachine.app.BuildConfig
import com.waterfountainmachine.app.security.SecurityModule
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.core.slot.SlotInventoryManager
import org.json.JSONObject

/**
 * Monitors machine health and sends heartbeat data via backend functions.
 * Tracks uptime, dispense metrics, and operational status.
 * 
 * Security: All health data requires certificate authentication.
 * Uses SecurityModule to sign requests with machine certificate.
 */
class MachineHealthMonitor private constructor(private val context: Context) {
    
    private val functions: FirebaseFunctions = Firebase.functions
    private val handler = Handler(Looper.getMainLooper())
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
        private const val HEARTBEAT_INTERVAL_MS = 60 * 60 * 1000L // 1 hour
        
        @Volatile
        private var instance: MachineHealthMonitor? = null
        
        fun getInstance(context: Context): MachineHealthMonitor {
            return instance ?: synchronized(this) {
                instance ?: MachineHealthMonitor(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            sendHealthHeartbeat()
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }
    
    /**
     * Start the health monitor
     */
    fun start(machineId: String) {
        if (isRunning) {
            AppLog.w(TAG, "Health monitor already running")
            return
        }
        
        this.machineId = machineId
        this.sessionStartTime = System.currentTimeMillis()
        this.isRunning = true
        
        // Send initial heartbeat
        sendHealthHeartbeat()
        
        // Schedule periodic heartbeats
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
        
        AppLog.i(TAG, "Health monitor started for machine: $machineId")
    }
    
    /**
     * Stop the health monitor
     */
    fun stop() {
        if (!isRunning) {
            return
        }
        
        handler.removeCallbacks(heartbeatRunnable)
        isRunning = false
        
        // Send final heartbeat
        sendHealthHeartbeat()
        
        AppLog.i(TAG, "Health monitor stopped")
    }
    
    /**
     * Record a dispense attempt
     */
    fun recordDispenseAttempt(success: Boolean, errorCode: String? = null) {
        totalDispensesToday++
        if (success) {
            successfulDispensesToday++
        } else {
            failedDispensesToday++
            lastErrorCode = errorCode
        }
        
        AppLog.d(TAG, "Dispense recorded: success=$success, total=$totalDispensesToday, successful=$successfulDispensesToday, failed=$failedDispensesToday")
    }
    
    /**
     * Send health heartbeat via backend function
     * Backend validates machine certificate and writes to Firestore
     */
    private fun sendHealthHeartbeat() {
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
            // Get inventory summary from slot manager
            val slotInventoryManager = SlotInventoryManager.getInstance(context)
            val inventorySummary = slotInventoryManager.getInventorySummary()
            
            // Create base payload
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
                
                // Add inventory summary
                put("inventorySummary", JSONObject().apply {
                    inventorySummary.forEach { (key, value) ->
                        put(key, value)
                    }
                })
            }
            
            // Add certificate authentication fields
            val authenticatedPayload = SecurityModule.createAuthenticatedRequest("logMachineHealth", payload)
            
            // Convert JSONObject to HashMap for Firebase Functions
            val healthData = hashMapOf<String, Any>()
            authenticatedPayload.keys().forEach { key ->
                val value = authenticatedPayload.get(key)
                when (value) {
                    is String -> healthData[key] = value
                    is Int -> healthData[key] = value
                    is Long -> healthData[key] = value
                    is Double -> healthData[key] = value
                    is Boolean -> healthData[key] = value
                    else -> healthData[key] = value.toString()
                }
            }
            
            functions
                .getHttpsCallable("logMachineHealth")
                .call(healthData)
                .addOnSuccessListener { result ->
                    val data = result.data as? Map<*, *>
                    val documentId = data?.get("documentId") as? String
                    AppLog.d(TAG, "✅ Health heartbeat sent: uptime=${uptimeSeconds}s, dispenses=$totalDispensesToday, docId=$documentId")
                }
                .addOnFailureListener { e ->
                    AppLog.e(TAG, "❌ Failed to send health heartbeat", e)
                }
        } catch (e: Exception) {
            AppLog.e(TAG, "Error creating authenticated health request", e)
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
}
