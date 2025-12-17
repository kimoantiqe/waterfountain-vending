package com.waterfountainmachine.app.core.slot

import android.content.Context
import android.content.SharedPreferences
import com.waterfountainmachine.app.hardware.sdk.SlotValidator
import com.waterfountainmachine.app.utils.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Slot Inventory Manager
 * 
 * Manages local slot inventory state with backend synchronization
 * Tracks remaining bottles, campaign/design attribution per slot
 * 
 * Features:
 * - Local caching of slot inventory in SharedPreferences
 * - Reactive StateFlow for UI updates
 * - Backend synchronization via BackendSlotService
 * - Automatic inventory decrement on successful vends
 */
class SlotInventoryManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "SlotInventoryManager"
        private const val PREFS_NAME = "slot_inventory_prefs"
        private const val PREF_LAST_SYNC = "last_sync_timestamp"
        
        // Slot data key prefixes
        private const val PREFIX_BOTTLES = "slot_"
        private const val SUFFIX_BOTTLES = "_bottles"
        private const val SUFFIX_CAPACITY = "_capacity"
        private const val SUFFIX_CAMPAIGN = "_campaign"
        private const val SUFFIX_DESIGN = "_design"
        private const val SUFFIX_STATUS = "_status"
        private const val SUFFIX_LAST_UPDATED = "_last_updated"
        
        @Volatile
        private var INSTANCE: SlotInventoryManager? = null
        
        fun getInstance(context: Context): SlotInventoryManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SlotInventoryManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // Reactive state for UI
    private val _slotInventoryFlow = MutableStateFlow<List<SlotInventory>>(emptyList())
    val slotInventoryFlow: StateFlow<List<SlotInventory>> = _slotInventoryFlow.asStateFlow()
    
    /**
     * Data class representing slot inventory
     */
    data class SlotInventory(
        val slot: Int,
        val remainingBottles: Int,
        val capacity: Int,
        val campaignId: String?,
        val canDesignId: String?,
        val status: SlotStatus,
        val lastUpdated: Long
    )
    
    enum class SlotStatus {
        ACTIVE,
        EMPTY,
        DISABLED,
        MAINTENANCE;
        
        companion object {
            fun fromString(value: String): SlotStatus {
                return try {
                    valueOf(value.uppercase())
                } catch (e: IllegalArgumentException) {
                    ACTIVE
                }
            }
        }
    }
    
    init {
        // Load initial state from SharedPreferences
        refreshStateFlow()
    }
    
    /**
     * Get inventory for a specific slot
     */
    fun getSlotInventory(slot: Int): SlotInventory? {
        if (!SlotValidator.isValidSlot(slot)) {
            AppLog.w(TAG, "Invalid slot number: $slot")
            return null
        }
        
        val bottles = prefs.getInt(getBottlesKey(slot), 0)
        val capacity = prefs.getInt(getCapacityKey(slot), 7) // Default capacity 7 (max)
        val campaignId = prefs.getString(getCampaignKey(slot), null)
        val canDesignId = prefs.getString(getDesignKey(slot), null)
        val statusStr = prefs.getString(getStatusKey(slot), "ACTIVE") ?: "ACTIVE"
        val lastUpdated = prefs.getLong(getLastUpdatedKey(slot), 0L)
        
        return SlotInventory(
            slot = slot,
            remainingBottles = bottles,
            capacity = capacity,
            campaignId = campaignId,
            canDesignId = canDesignId,
            status = SlotStatus.fromString(statusStr),
            lastUpdated = lastUpdated
        )
    }
    
    /**
     * Get all slot inventories
     */
    fun getAllSlots(): List<SlotInventory> {
        return SlotValidator.VALID_SLOTS.mapNotNull { slot ->
            getSlotInventory(slot)
        }
    }
    
    /**
     * Update slot inventory locally
     */
    fun updateSlotInventory(
        slot: Int,
        remainingBottles: Int,
        capacity: Int = 7,
        campaignId: String? = null,
        canDesignId: String? = null,
        status: SlotStatus = SlotStatus.ACTIVE
    ) {
        if (!SlotValidator.isValidSlot(slot)) {
            AppLog.w(TAG, "Invalid slot number: $slot")
            return
        }
        
        val editor = prefs.edit()
        editor.putInt(getBottlesKey(slot), remainingBottles)
        editor.putInt(getCapacityKey(slot), capacity)
        editor.putString(getStatusKey(slot), status.name)
        editor.putLong(getLastUpdatedKey(slot), System.currentTimeMillis())
        
        if (campaignId != null) {
            editor.putString(getCampaignKey(slot), campaignId)
        }
        if (canDesignId != null) {
            editor.putString(getDesignKey(slot), canDesignId)
        }
        
        editor.apply()
        
        // Refresh StateFlow
        refreshStateFlow()
        
        AppLog.d(TAG, "Slot $slot inventory updated: $remainingBottles bottles")
    }
    
    /**
     * Decrement inventory for a slot (called after successful vend)
     */
    fun decrementInventory(slot: Int): Boolean {
        val currentInventory = getSlotInventory(slot)
        if (currentInventory == null) {
            AppLog.w(TAG, "Cannot decrement - slot $slot not found")
            return false
        }
        
        if (currentInventory.remainingBottles <= 0) {
            AppLog.w(TAG, "Cannot decrement - slot $slot already empty")
            return false
        }
        
        val newBottles = currentInventory.remainingBottles - 1
        updateSlotInventory(
            slot = slot,
            remainingBottles = newBottles,
            capacity = currentInventory.capacity,
            campaignId = currentInventory.campaignId,
            canDesignId = currentInventory.canDesignId,
            status = if (newBottles == 0) SlotStatus.EMPTY else currentInventory.status
        )
        
        AppLog.i(TAG, "Slot $slot decremented: ${currentInventory.remainingBottles} -> $newBottles bottles")
        return true
    }
    
    /**
     * Check if slot is available for dispensing
     * (has inventory and not disabled)
     */
    fun isSlotAvailable(slot: Int): Boolean {
        val inventory = getSlotInventory(slot) ?: return false
        return inventory.remainingBottles > 0 && 
               inventory.status != SlotStatus.DISABLED && 
               inventory.status != SlotStatus.MAINTENANCE
    }
    
    /**
     * Get list of available slots (non-empty, enabled)
     */
    fun getAvailableSlots(): List<Int> {
        return SlotValidator.VALID_SLOTS.filter { slot ->
            isSlotAvailable(slot)
        }
    }
    
    /**
     * Get slots that are low on inventory (< 20% capacity)
     */
    fun getLowInventorySlots(): List<Int> {
        return getAllSlots().filter { inventory ->
            val threshold = (inventory.capacity * 0.2).toInt()
            inventory.remainingBottles in 1..threshold
        }.map { it.slot }
    }
    
    /**
     * Get empty slots
     */
    fun getEmptySlots(): List<Int> {
        return getAllSlots().filter { it.remainingBottles == 0 }.map { it.slot }
    }
    
    /**
     * Get total inventory across all slots
     */
    fun getTotalInventory(): Int {
        return getAllSlots().sumOf { it.remainingBottles }
    }
    
    /**
     * Get total capacity across all slots
     */
    fun getTotalCapacity(): Int {
        return getAllSlots().sumOf { it.capacity }
    }
    
    /**
     * Get inventory fill rate (percentage)
     */
    fun getInventoryFillRate(): Float {
        val totalCapacity = getTotalCapacity()
        if (totalCapacity == 0) return 0f
        return (getTotalInventory().toFloat() / totalCapacity.toFloat()) * 100f
    }
    
    /**
     * Clear all slot inventory (for testing/reset)
     */
    fun clearAll() {
        prefs.edit().clear().apply()
        refreshStateFlow()
        AppLog.i(TAG, "All slot inventory cleared")
    }
    
    /**
     * Get last sync timestamp
     */
    fun getLastSyncTimestamp(): Long {
        return prefs.getLong(PREF_LAST_SYNC, 0L)
    }
    
    /**
     * Update last sync timestamp
     */
    fun updateLastSyncTimestamp() {
        prefs.edit().putLong(PREF_LAST_SYNC, System.currentTimeMillis()).apply()
    }
    
    /**
     * Refresh StateFlow with current data
     */
    private fun refreshStateFlow() {
        _slotInventoryFlow.value = getAllSlots()
    }
    
    // Key generation helpers
    private fun getBottlesKey(slot: Int) = "$PREFIX_BOTTLES${slot}$SUFFIX_BOTTLES"
    private fun getCapacityKey(slot: Int) = "$PREFIX_BOTTLES${slot}$SUFFIX_CAPACITY"
    private fun getCampaignKey(slot: Int) = "$PREFIX_BOTTLES${slot}$SUFFIX_CAMPAIGN"
    private fun getDesignKey(slot: Int) = "$PREFIX_BOTTLES${slot}$SUFFIX_DESIGN"
    private fun getStatusKey(slot: Int) = "$PREFIX_BOTTLES${slot}$SUFFIX_STATUS"
    private fun getLastUpdatedKey(slot: Int) = "$PREFIX_BOTTLES${slot}$SUFFIX_LAST_UPDATED"
    
    /**
     * Get inventory summary for health reporting
     */
    fun getInventorySummary(): Map<String, Any> {
        return mapOf(
            "totalInventory" to getTotalInventory(),
            "totalCapacity" to getTotalCapacity(),
            "fillRate" to getInventoryFillRate(),
            "emptySlots" to getEmptySlots().size,
            "lowInventorySlots" to getLowInventorySlots().size,
            "availableSlots" to getAvailableSlots().size
        )
    }
}
