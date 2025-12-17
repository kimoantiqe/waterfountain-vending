package com.waterfountainmachine.app.core.backend

import android.content.Context
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import com.waterfountainmachine.app.security.SecurityModule
import com.waterfountainmachine.app.core.slot.SlotInventoryManager
import com.waterfountainmachine.app.utils.AppLog
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/**
 * Backend Slot Service
 * 
 * Handles certificate-authenticated communication with backend slot functions
 * All requests are signed using SecurityModule for secure backend access
 */
class BackendSlotService private constructor(private val context: Context) : IBackendSlotService {
    
    companion object {
        private const val TAG = "BackendSlotService"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
        
        @Volatile
        private var INSTANCE: BackendSlotService? = null
        
        fun getInstance(context: Context): BackendSlotService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BackendSlotService(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val functions: FirebaseFunctions = Firebase.functions
    
    /**
     * Retry logic wrapper for backend calls
     */
    private suspend fun <T> retryOperation(
        operation: suspend () -> T,
        operationName: String
    ): T {
        var lastException: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                return operation()
            } catch (e: Exception) {
                lastException = e
                if (attempt < MAX_RETRIES - 1) {
                    AppLog.w(TAG, "$operationName failed (attempt ${attempt + 1}/$MAX_RETRIES), retrying...")
                    kotlinx.coroutines.delay(RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        throw lastException ?: Exception("Operation failed after $MAX_RETRIES attempts")
    }
    
    /**
     * Sync slot inventory with backend
     * Downloads all slot data from backend and updates local state
     */
    override suspend fun syncInventoryWithBackend(machineId: String): Result<List<SlotInventoryManager.SlotInventory>> {
        return try {
            AppLog.d(TAG, "Syncing slot inventory for machine: $machineId")
            
            // Call backend with retry logic
            val result = retryOperation(
                operation = {
                    val payload = JSONObject().apply {
                        put("machineId", machineId)
                    }
                    
                    val authenticatedRequest = SecurityModule.createAuthenticatedRequest(
                        endpoint = "getSlotInventory",
                        payload = payload
                    )
                    
                    functions
                        .getHttpsCallable("getSlotInventory")
                        .call(authenticatedRequest.toMap())
                        .await()
                },
                operationName = "syncInventoryWithBackend"
            )
            
            // Parse response
            val slotsData = result.data as? List<*> ?: emptyList<Any>()
            val slotInventoryManager = SlotInventoryManager.getInstance(context)
            val slots = mutableListOf<SlotInventoryManager.SlotInventory>()
            
            for (slotData in slotsData) {
                val slotMap = slotData as? Map<*, *> ?: continue
                
                val slot = (slotMap["slot"] as? Number)?.toInt() ?: continue
                val remainingBottles = (slotMap["remainingBottles"] as? Number)?.toInt() ?: 0
                val capacity = (slotMap["capacity"] as? Number)?.toInt() ?: 7
                val campaignId = slotMap["campaignId"] as? String
                val canDesignId = slotMap["canDesignId"] as? String
                val statusStr = slotMap["status"] as? String ?: "active"
                
                // Backend sends lowercase status, convert to enum
                val status = SlotInventoryManager.SlotStatus.fromString(statusStr)
                
                // Update local storage
                slotInventoryManager.updateSlotInventory(
                    slot = slot,
                    remainingBottles = remainingBottles,
                    capacity = capacity,
                    campaignId = campaignId,
                    canDesignId = canDesignId,
                    status = status
                )
                
                slots.add(SlotInventoryManager.SlotInventory(
                    slot = slot,
                    remainingBottles = remainingBottles,
                    capacity = capacity,
                    campaignId = campaignId,
                    canDesignId = canDesignId,
                    status = status,
                    lastUpdated = System.currentTimeMillis()
                ))
            }
            
            slotInventoryManager.updateLastSyncTimestamp()
            AppLog.i(TAG, "Successfully synced ${slots.size} slots from backend")
            
            Result.success(slots)
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to sync inventory with backend", e)
            Result.failure(e)
        }
    }
    
    /**
     * Record vend event with slot information
     */
    override suspend fun recordVendWithSlot(
        machineId: String,
        slot: Int,
        phoneHash: String?,
        success: Boolean,
        errorCode: String?,
        totalJourneyDurationMs: Long?,
        dispenseDurationMs: Long?
    ): Result<IBackendSlotService.VendEventResult> {
        return try {
            AppLog.d(TAG, "Recording vend event: machine=$machineId, slot=$slot, success=$success")
            
            // Call backend with retry logic
            val result = retryOperation(
                operation = {
                    val payload = JSONObject().apply {
                        put("machineId", machineId)
                        put("slot", slot)
                        if (phoneHash != null) put("phoneHash", phoneHash)
                        put("success", success)
                        if (errorCode != null) put("errorCode", errorCode)
                        if (totalJourneyDurationMs != null) put("totalJourneyDurationMs", totalJourneyDurationMs)
                        if (dispenseDurationMs != null) put("dispenseDurationMs", dispenseDurationMs)
                    }
                    
                    val authenticatedRequest = SecurityModule.createAuthenticatedRequest(
                        endpoint = "recordVendWithSlot",
                        payload = payload
                    )
                    
                    functions
                        .getHttpsCallable("recordVendWithSlot")
                        .call(authenticatedRequest.toMap())
                        .await()
                },
                operationName = "recordVendWithSlot"
            )
            
            // Parse response
            val resultData = result.data as? Map<*, *>
            val eventId = resultData?.get("eventId") as? String
            val campaignId = resultData?.get("campaignId") as? String
            val canDesignId = resultData?.get("canDesignId") as? String
            
            AppLog.i(TAG, "Vend event recorded: eventId=$eventId, campaign=$campaignId, design=$canDesignId")
            
            Result.success(IBackendSlotService.VendEventResult(
                eventId = eventId ?: "",
                campaignId = campaignId,
                canDesignId = canDesignId
            ))
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to record vend event", e)
            Result.failure(e)
        }
    }
    
    // Note: Machines can only READ inventory and RECORD vends
    // Only admin can UPDATE inventory via admin panel
    
    /**
     * Get slot inventory from backend
     */
    override suspend fun getSlotInventory(machineId: String, slot: Int?): Result<Any> {
        return try {
            AppLog.d(TAG, "Getting slot inventory: machine=$machineId, slot=$slot")
            
            // Create authenticated request
            val payload = JSONObject().apply {
                put("machineId", machineId)
                if (slot != null) put("slot", slot)
            }
            
            val authenticatedRequest = SecurityModule.createAuthenticatedRequest(
                endpoint = "getSlotInventory",
                payload = payload
            )
            
            // Call backend function
            val result = functions
                .getHttpsCallable("getSlotInventory")
                .call(authenticatedRequest.toMap())
                .await()
            
            Result.success(result.data ?: emptyList<Any>())
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to get slot inventory", e)
            Result.failure(e)
        }
    }
    
    /**
     * Update slot status in backend (e.g., mark as DISABLED after failures)
     */
    override suspend fun updateSlotStatus(
        machineId: String,
        slot: Int,
        status: String
    ): Result<Unit> {
        return try {
            AppLog.d(TAG, "Updating slot $slot status to $status for machine $machineId")
            
            val payload = JSONObject().apply {
                put("machineId", machineId)
                put("slot", slot)
                put("status", status)
            }
            
            val authenticatedRequest = SecurityModule.createAuthenticatedRequest(
                endpoint = "updateSlotInventory",
                payload = payload
            )
            
            functions
                .getHttpsCallable("updateSlotInventory")
                .call(authenticatedRequest.toMap())
                .await()
            
            AppLog.i(TAG, "Slot $slot status updated to $status successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to update slot status", e)
            Result.failure(e)
        }
    }
    
    // Helper to convert JSONObject to Map
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
