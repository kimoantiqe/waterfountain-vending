package com.waterfountainmachine.app.core.backend

import android.content.Context
import com.waterfountainmachine.app.core.slot.SlotInventoryManager
import com.waterfountainmachine.app.utils.AppLog
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * Mock Backend Slot Service
 * 
 * Simulates backend slot operations without making real API calls.
 * Used for testing and demo mode where real backend is not available.
 * 
 * Features:
 * - Simulates network delay
 * - Returns success responses
 * - Logs all operations for debugging
 * - Uses local slot inventory data only
 */
class MockBackendSlotService(private val context: Context) : IBackendSlotService {
    
    companion object {
        private const val TAG = "MockBackendSlotService"
        private const val SIMULATED_NETWORK_DELAY_MS = 500L
    }
    
    /**
     * Mock sync - just returns success, doesn't actually sync
     * Vending will rely on local slot inventory only
     */
    override suspend fun syncInventoryWithBackend(machineId: String): Result<List<SlotInventoryManager.SlotInventory>> {
        return try {
            AppLog.d(TAG, "[MOCK] Syncing slot inventory for machine: $machineId")
            
            // Simulate network delay
            delay(SIMULATED_NETWORK_DELAY_MS)
            
            // Get current local inventory
            val slotInventoryManager = SlotInventoryManager.getInstance(context)
            val slots = mutableListOf<SlotInventoryManager.SlotInventory>()
            
            // Return current local state (no actual backend sync)
            for (slot in 1..58) {
                if (isValidSlot(slot)) {
                    val inventory = slotInventoryManager.getSlotInventory(slot)
                    if (inventory != null) {
                        slots.add(inventory)
                    }
                }
            }
            
            AppLog.i(TAG, "[MOCK] Successfully 'synced' ${slots.size} slots (using local data)")
            Result.success(slots)
        } catch (e: Exception) {
            AppLog.e(TAG, "[MOCK] Failed to sync inventory", e)
            Result.failure(e)
        }
    }
    
    /**
     * Mock record vend - just logs and returns success
     * No actual backend recording happens
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
            AppLog.d(TAG, "[MOCK] Recording vend event: machine=$machineId, slot=$slot, success=$success")
            
            // Simulate network delay
            delay(SIMULATED_NETWORK_DELAY_MS)
            
            // Generate mock event ID
            val eventId = "mock_${UUID.randomUUID()}"
            
            AppLog.i(TAG, "[MOCK] Vend event 'recorded': eventId=$eventId")
            
            Result.success(
                IBackendSlotService.VendEventResult(
                    eventId = eventId,
                    campaignId = "mock_campaign",
                    canDesignId = "mock_design"
                )
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "[MOCK] Failed to record vend event", e)
            Result.failure(e)
        }
    }
    
    /**
     * Mock get slot inventory - returns local data
     */
    override suspend fun getSlotInventory(machineId: String, slot: Int?): Result<Any> {
        return try {
            AppLog.d(TAG, "[MOCK] Getting slot inventory: machine=$machineId, slot=$slot")
            
            // Simulate network delay
            delay(SIMULATED_NETWORK_DELAY_MS)
            
            val slotInventoryManager = SlotInventoryManager.getInstance(context)
            
            if (slot != null) {
                val inventory = slotInventoryManager.getSlotInventory(slot)
                AppLog.i(TAG, "[MOCK] Returned slot $slot inventory from local storage")
                Result.success(inventory ?: emptyMap<String, Any>())
            } else {
                val allSlots = mutableListOf<SlotInventoryManager.SlotInventory>()
                for (s in 1..58) {
                    if (isValidSlot(s)) {
                        val inventory = slotInventoryManager.getSlotInventory(s)
                        if (inventory != null) {
                            allSlots.add(inventory)
                        }
                    }
                }
                AppLog.i(TAG, "[MOCK] Returned ${allSlots.size} slots from local storage")
                Result.success(allSlots)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "[MOCK] Failed to get slot inventory", e)
            Result.failure(e)
        }
    }
    
    /**
     * Mock update slot status - just logs, no actual backend update
     */
    override suspend fun updateSlotStatus(
        machineId: String,
        slot: Int,
        status: String
    ): Result<Unit> {
        return try {
            AppLog.d(TAG, "[MOCK] Updating slot $slot status to $status for machine $machineId")
            
            // Simulate network delay
            delay(SIMULATED_NETWORK_DELAY_MS)
            
            // Update local inventory only
            val slotInventoryManager = SlotInventoryManager.getInstance(context)
            val statusEnum = SlotInventoryManager.SlotStatus.fromString(status)
            
            val currentInventory = slotInventoryManager.getSlotInventory(slot)
            if (currentInventory != null) {
                slotInventoryManager.updateSlotInventory(
                    slot = slot,
                    remainingBottles = currentInventory.remainingBottles,
                    capacity = currentInventory.capacity,
                    campaignId = currentInventory.campaignId,
                    canDesignId = currentInventory.canDesignId,
                    status = statusEnum
                )
            }
            
            AppLog.i(TAG, "[MOCK] Slot $slot status 'updated' to $status (local only)")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLog.e(TAG, "[MOCK] Failed to update slot status", e)
            Result.failure(e)
        }
    }
    
    /**
     * Check if slot number is valid (matches 6x8 layout)
     */
    private fun isValidSlot(slot: Int): Boolean {
        val ones = slot % 10
        val tens = slot / 10
        return ones in 1..8 && tens in 0..5
    }
}
