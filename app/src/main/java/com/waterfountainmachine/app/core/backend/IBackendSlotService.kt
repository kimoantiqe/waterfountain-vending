package com.waterfountainmachine.app.core.backend

import com.waterfountainmachine.app.core.slot.SlotInventoryManager

/**
 * Interface for Backend Slot Service
 * 
 * Allows polymorphic behavior between real backend API calls
 * and mock implementation for testing/demo mode
 */
interface IBackendSlotService {
    
    /**
     * Sync slot inventory with backend
     * Downloads all slot data from backend and updates local state
     */
    suspend fun syncInventoryWithBackend(machineId: String): Result<List<SlotInventoryManager.SlotInventory>>
    
    /**
     * Record vend event with slot information
     */
    suspend fun recordVendWithSlot(
        machineId: String,
        slot: Int,
        phoneHash: String?,
        success: Boolean,
        errorCode: String? = null,
        totalJourneyDurationMs: Long? = null,
        dispenseDurationMs: Long? = null
    ): Result<VendEventResult>
    
    /**
     * Get slot inventory from backend
     */
    suspend fun getSlotInventory(machineId: String, slot: Int? = null): Result<Any>
    
    /**
     * Update slot status in backend (e.g., mark as DISABLED after failures)
     */
    suspend fun updateSlotStatus(
        machineId: String,
        slot: Int,
        status: String
    ): Result<Unit>
    
    /**
     * Result data class for vend events
     */
    data class VendEventResult(
        val eventId: String,
        val campaignId: String?,
        val canDesignId: String?
    )
}
