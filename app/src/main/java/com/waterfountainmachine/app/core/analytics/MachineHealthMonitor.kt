package com.waterfountainmachine.app.core.analytics
import android.content.Context
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import com.waterfountainmachine.app.BuildConfig
import com.waterfountainmachine.app.core.security.SecurityModule
import com.waterfountainmachine.app.core.utils.AppLog
import com.waterfountainmachine.app.core.utils.CrashlyticsHelper
import com.waterfountainmachine.app.core.slot.SlotInventoryManager
import com.waterfountainmachine.app.core.backend.BackendMachineService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    /**
     * Has the backend remotely disabled this machine? Customer-facing
     * Activities call this in `onCreate` and short-circuit to the
     * maintenance screen when it returns `true`.
     */
    fun isMachineDisabled(): Boolean = false

    /**
     * Optional reason string for [isMachineDisabled]; used only for logs.
     */
    fun getDisabledReason(): String? = null
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

    // Lazy so unit tests that exercise the pure counter/status logic do
    // not need FirebaseApp.initializeApp() up front. The heartbeat path
    // still resolves the same instance the first time it sends.
    private val functions: FirebaseFunctions by lazy { Firebase.functions }
    private val backendMachineService = BackendMachineService.getInstance(context)

    /**
     * Process-scoped coroutine scope. [MachineHealthMonitor] is a singleton
     * keyed on [Context.getApplicationContext] and is intentionally never
     * torn down — the heartbeat must run for the life of the kiosk process.
     * The scope dies with the process; there is no [stop]+cancel pairing.
     * Use [stop] to halt heartbeats temporarily without killing the scope.
     */
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
    
    // Machine status storage (for remote disable)
    private val prefs = context.getSharedPreferences("machine_health", Context.MODE_PRIVATE)
    
    companion object {
        private const val TAG = "MachineHealthMonitor"
        private const val COLLECTION_MACHINE_HEALTH = "machineHealth"
        private const val HEARTBEAT_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
        
        // SharedPreferences keys for machine status
        private const val KEY_IS_DISABLED = "is_disabled"
        private const val KEY_DISABLED_REASON = "disabled_reason"
        private const val KEY_DISABLED_AT = "disabled_at"
        private const val KEY_LAST_STATUS_CHECK = "last_status_check"
        
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
        
        // Start periodic heartbeat and status check coroutine
        heartbeatJob = scope.launch {
            // Send initial heartbeat and check status
            sendHealthHeartbeat()
            checkMachineStatus()
            
            // Schedule periodic heartbeats and status checks
            while (isRunning) {
                delay(HEARTBEAT_INTERVAL_MS)
                sendHealthHeartbeat()
                checkMachineStatus()
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
     * Check machine status from backend
     * Detects if machine has been remotely disabled
     */
    private suspend fun checkMachineStatus() {
        val currentMachineId = machineId
        if (currentMachineId == null) {
            AppLog.w(TAG, "Cannot check machine status: machineId not set")
            return
        }
        
        // Check if machine is enrolled
        if (!SecurityModule.isEnrolled()) {
            AppLog.w(TAG, "Cannot check machine status: Machine not enrolled")
            return
        }
        
        try {
            val result = backendMachineService.getMachineStatus(currentMachineId)
            
            result.onSuccess { status ->
                val isDisabled = status.status == "disabled"
                
                // Store status in SharedPreferences
                prefs.edit().apply {
                    putBoolean(KEY_IS_DISABLED, isDisabled)
                    putString(KEY_DISABLED_REASON, status.disabledReason)
                    putLong(KEY_DISABLED_AT, status.disabledAt ?: 0L)
                    putLong(KEY_LAST_STATUS_CHECK, System.currentTimeMillis())
                    apply()
                }
                
                if (isDisabled) {
                    AppLog.w(TAG, "⚠️ Machine is DISABLED: ${status.disabledReason}")
                } else {
                    AppLog.d(TAG, "✅ Machine status check: ACTIVE")
                }
            }
            
            result.onFailure { error ->
                // Handle "Machine not active" as a special case (expected during setup or deactivation)
                val errorMessage = error.message ?: ""
                if (errorMessage.contains("Machine not active", ignoreCase = true)) {
                    AppLog.w(TAG, "⚠️ Machine not active in backend - may need activation")
                    // Mark as disabled locally until activated
                    prefs.edit().apply {
                        putBoolean(KEY_IS_DISABLED, true)
                        putString(KEY_DISABLED_REASON, "Machine not activated in system")
                        putLong(KEY_LAST_STATUS_CHECK, System.currentTimeMillis())
                        apply()
                    }
                } else {
                    AppLog.e(TAG, "Failed to check machine status", error)
                }
            }
            
        } catch (e: Exception) {
            AppLog.e(TAG, "Exception checking machine status", e)
        }
    }
    
    /**
     * Check if machine is currently disabled
     * Called by MainActivity to determine if error screen should be shown
     */
    override fun isMachineDisabled(): Boolean {
        return prefs.getBoolean(KEY_IS_DISABLED, false)
    }
    
    /**
     * Get disabled reason message
     */
    override fun getDisabledReason(): String? {
        return prefs.getString(KEY_DISABLED_REASON, null)
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
