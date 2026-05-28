package com.waterfountainmachine.app.core.backend

import android.content.Context
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import com.waterfountainmachine.app.core.security.SecurityModule
import com.waterfountainmachine.app.core.slot.SlotInventoryManager
import com.waterfountainmachine.app.core.utils.AppLog
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
        internal const val MAX_RETRIES = 3
        internal const val RETRY_DELAY_MS = 1000L
        
        @Volatile
        private var INSTANCE: BackendSlotService? = null
        
        fun getInstance(context: Context): BackendSlotService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BackendSlotService(context.applicationContext).also { INSTANCE = it }
            }
        }

        /**
         * Exponential-ish backoff retry. Used by every backend call so a
         * single transient network blip doesn't surface as a user-visible
         * failure. Delay grows linearly: 1×, 2×, 3× [RETRY_DELAY_MS] — fast
         * enough to recover from a brief gateway hiccup, slow enough to back
         * off when the backend is actually down. Exposed (internal) so tests
         * can exercise it directly without spinning up Firebase.
         *
         * @param operation suspend block to retry on any [Exception].
         * @param operationName label used in retry-log lines.
         * @param delayMillis delay implementation; default forwards to
         *   [kotlinx.coroutines.delay] so tests can pass a no-op.
         */
        internal suspend fun <T> retryOperation(
            operation: suspend () -> T,
            operationName: String,
            delayMillis: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }
        ): T {
            var lastException: Exception? = null
            repeat(MAX_RETRIES) { attempt ->
                try {
                    return operation()
                } catch (e: Exception) {
                    lastException = e
                    if (attempt < MAX_RETRIES - 1) {
                        AppLog.w(TAG, "$operationName failed (attempt ${attempt + 1}/$MAX_RETRIES), retrying...")
                        delayMillis(RETRY_DELAY_MS * (attempt + 1))
                    }
                }
            }
            throw lastException ?: Exception("Operation failed after $MAX_RETRIES attempts")
        }

        /**
         * Pure parser for the `getSlotInventory` callable response. Accepts
         * the raw `result.data` (a `List<Map<*, *>>` shaped payload) and
         * returns a clean list of [SlotInventoryManager.SlotInventory].
         *
         * Malformed entries (missing or non-numeric `slot`) are silently
         * dropped — same behavior as production. Unknown status strings
         * fall back to [SlotInventoryManager.SlotStatus.ACTIVE] via
         * [SlotInventoryManager.SlotStatus.fromString].
         */
        internal fun parseSlotInventoryList(
            data: Any?,
            nowMillis: Long = System.currentTimeMillis()
        ): List<SlotInventoryManager.SlotInventory> {
            val list = data as? List<*> ?: return emptyList()
            val out = mutableListOf<SlotInventoryManager.SlotInventory>()
            for (raw in list) {
                val m = raw as? Map<*, *> ?: continue
                val slot = (m["slot"] as? Number)?.toInt() ?: continue
                out += SlotInventoryManager.SlotInventory(
                    slot = slot,
                    remainingBottles = (m["remainingBottles"] as? Number)?.toInt() ?: 0,
                    capacity = (m["capacity"] as? Number)?.toInt() ?: 7,
                    campaignId = m["campaignId"] as? String,
                    canDesignId = m["canDesignId"] as? String,
                    canDesignName = m["canDesignName"] as? String,
                    status = SlotInventoryManager.SlotStatus.fromString(
                        m["status"] as? String ?: "active"
                    ),
                    lastUpdated = nowMillis
                )
            }
            return out
        }

        /**
         * Pure parser for the `recordVendWithSlot` callable response. Missing
         * `eventId` becomes `""` (production behavior) so callers that only
         * care about the analytics-attribution fields still get them.
         */
        internal fun parseVendEventResult(data: Any?): IBackendSlotService.VendEventResult {
            val m = data as? Map<*, *>
            // Treat blank strings as missing so the animation does not show
            // an empty billboard or attempt to Glide-load an empty URL.
            fun stringOrNull(key: String): String? =
                (m?.get(key) as? String)?.takeIf { it.isNotBlank() }
            return IBackendSlotService.VendEventResult(
                eventId = m?.get("eventId") as? String ?: "",
                campaignId = m?.get("campaignId") as? String,
                canDesignId = m?.get("canDesignId") as? String,
                advertiserId = m?.get("advertiserId") as? String,
                machineName = m?.get("machineName") as? String,
                campaignName = m?.get("campaignName") as? String,
                canDesignName = m?.get("canDesignName") as? String,
                advertiserName = m?.get("advertiserName") as? String,
                customerMessage = stringOrNull("customerMessage"),
                advertiserLogoUrl = stringOrNull("advertiserLogoUrl")
            )
        }

        /**
         * Translate a [com.google.firebase.functions.FirebaseFunctionsException]
         * surfaced from `recordVendWithSlot` into the right [Result] shape.
         * `RESOURCE_EXHAUSTED` becomes [DailyLimitReachedException] so
         * the vending UI can render the "daily limit reached" path instead
         * of a generic backend-error screen; every other code is surfaced
         * as the original [FirebaseFunctionsException] so callers can
         * still switch on `.code`.
         *
         * Split into (code, message, original) because
         * `FirebaseFunctionsException`'s constructor is package-private
         * and cannot be instantiated from unit tests. Keeping the
         * signature surface-area small lets the mapping table be
         * exercised without spinning up Firebase.
         */
        internal fun mapVendFirebaseException(
            code: com.google.firebase.functions.FirebaseFunctionsException.Code,
            message: String?,
            original: com.google.firebase.functions.FirebaseFunctionsException
        ): Result<IBackendSlotService.VendEventResult> {
            return when (code) {
                com.google.firebase.functions.FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ->
                    Result.failure(DailyLimitReachedException(message ?: "Daily limit reached"))
                else -> Result.failure(original)
            }
        }
    }
    
    private val functions: FirebaseFunctions = Firebase.functions
    
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
            
            // Parse response into SlotInventory list (no writes yet — we only
            // mutate the local cache after the full payload parses cleanly).
            val slots = parseSlotInventoryList(result.data)
            val slotInventoryManager = SlotInventoryManager.getInstance(context)

            // Atomic replace: backend is the source of truth, so any slot the
            // backend did NOT return must be evicted from the local cache.
            // replaceAllSlots also bumps lastSyncTimestamp inside the same
            // commit so we don't need a separate updateLastSyncTimestamp call.
            slotInventoryManager.replaceAllSlots(slots)
            AppLog.i(TAG, "Successfully synced ${slots.size} slots from backend (atomic replace)")

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
        phone: String?,
        success: Boolean,
        errorCode: String?,
        totalJourneyDurationMs: Long?,
        dispenseDurationMs: Long?,
        isMock: Boolean
    ): Result<IBackendSlotService.VendEventResult> {
        return try {
            AppLog.i(TAG, "🟢 Recording vend: machine=$machineId, slot=$slot, success=$success")
            
            val result = retryOperation(
                operation = {
                    val payload = JSONObject().apply {
                        put("machineId", machineId)
                        put("slot", slot)
                        if (phone != null) put("phone", phone)
                        put("success", success)
                        if (errorCode != null) put("errorCode", errorCode)
                        if (totalJourneyDurationMs != null) put("totalJourneyDurationMs", totalJourneyDurationMs)
                        if (dispenseDurationMs != null) put("dispenseDurationMs", dispenseDurationMs)
                        put("isMock", isMock)
                    }
                    
                    val authenticatedRequest = SecurityModule.createAuthenticatedRequest(
                        endpoint = "recordVendWithSlot",
                        payload = payload
                    )
                    
                    functions.getHttpsCallable("recordVendWithSlot")
                        .call(authenticatedRequest.toMap())
                        .await()
                },
                operationName = "recordVendWithSlot"
            )
            
            val vendResult = parseVendEventResult(result.data)
            AppLog.d(TAG, "📊 Vend result: eventId=${vendResult.eventId}, campaign=${vendResult.campaignName ?: vendResult.campaignId}, design=${vendResult.canDesignName ?: vendResult.canDesignId}, advertiser=${vendResult.advertiserName ?: vendResult.advertiserId}")
            Result.success(vendResult)
        } catch (e: com.google.firebase.functions.FirebaseFunctionsException) {
            AppLog.e(TAG, "Firebase error: ${e.code}", e)
            return mapVendFirebaseException(e.code, e.message, e)
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to record vend", e)
            Result.failure(e)
        }
    }
    
    class DailyLimitReachedException(message: String) : Exception(message)
    
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
     * Update slot status in backend (e.g., mark as error after failures)
     */
    override suspend fun updateSlotStatus(
        machineId: String,
        slot: Int,
        status: String,
        errorCode: String?,
        errorMessage: String?
    ): Result<Unit> {
        return try {
            AppLog.d(TAG, "Updating slot $slot status to $status for machine $machineId")
            
            val payload = JSONObject().apply {
                put("machineId", machineId)
                put("slot", slot)
                put("status", status)
                errorCode?.let { put("errorCode", it) }
                errorMessage?.let { put("errorMessage", it) }
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
