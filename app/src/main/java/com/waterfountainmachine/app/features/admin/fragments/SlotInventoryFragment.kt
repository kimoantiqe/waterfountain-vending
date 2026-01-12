package com.waterfountainmachine.app.admin.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.waterfountainmachine.app.R
import com.waterfountainmachine.app.core.slot.SlotInventoryManager
import com.waterfountainmachine.app.core.backend.IBackendSlotService
import com.waterfountainmachine.app.core.backend.BackendSlotService
import com.waterfountainmachine.app.hardware.LaneManager
import com.waterfountainmachine.app.di.BackendModule
import com.waterfountainmachine.app.hardware.sdk.SlotValidator
import com.waterfountainmachine.app.utils.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * Slot Inventory Admin Fragment
 * 
 * Displays all 48 slots in a 6×8 grid showing:
 * - Slot number
 * - Remaining bottles
 * - Can design
 * - Status (active/empty/disabled/maintenance)
 * 
 * Features:
 * - Visual grid layout matching admin panel
 * - Refresh button to sync with backend
 * - Color-coded status indicators
 * - Real-time inventory display
 */
class SlotInventoryFragment : Fragment() {
    
    companion object {
        private const val TAG = "SlotInventoryFragment"
        
        fun newInstance() = SlotInventoryFragment()
    }
    
    private lateinit var slotInventoryManager: SlotInventoryManager
    private lateinit var backendSlotService: IBackendSlotService
    private lateinit var laneManager: LaneManager
    
    private lateinit var refreshButton: Button
    private lateinit var statusText: TextView
    private lateinit var syncStatusBanner: LinearLayout
    private lateinit var syncStatusTitle: TextView
    private lateinit var syncStatusMessage: TextView
    private lateinit var lastSyncText: TextView
    private lateinit var totalCansText: TextView
    private lateinit var filledSlotsText: TextView
    private lateinit var emptySlotsText: TextView
    private lateinit var slotsGridContainer: LinearLayout
    private lateinit var currentLaneText: TextView
    private lateinit var dispenseQueueContainer: LinearLayout
    
    private val slotViews = mutableMapOf<Int, SlotCardView>()
    
    private var isRefreshing = false
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_slot_inventory, container, false)
        
        slotInventoryManager = SlotInventoryManager.getInstance(requireContext())
        backendSlotService = BackendModule.getBackendSlotService(requireContext())
        laneManager = LaneManager.getInstance(requireContext())
        
        initializeViews(view)
        setupUI()
        updateSyncStatusBanner()
        loadSlotInventory()
        updateNextDispenseQueue()
        
        return view
    }
    
    private fun initializeViews(view: View) {
        refreshButton = view.findViewById(R.id.refreshButton)
        statusText = view.findViewById(R.id.statusText)
        syncStatusBanner = view.findViewById(R.id.syncStatusBanner)
        syncStatusTitle = view.findViewById(R.id.syncStatusTitle)
        syncStatusMessage = view.findViewById(R.id.syncStatusMessage)
        lastSyncText = view.findViewById(R.id.lastSyncText)
        totalCansText = view.findViewById(R.id.totalCansText)
        filledSlotsText = view.findViewById(R.id.filledSlotsText)
        emptySlotsText = view.findViewById(R.id.emptySlotsText)
        slotsGridContainer = view.findViewById(R.id.slotsGridContainer)
        currentLaneText = view.findViewById(R.id.currentLaneText)
        dispenseQueueContainer = view.findViewById(R.id.dispenseQueueContainer)
    }
    
    private fun setupUI() {
        refreshButton.setOnClickListener {
            refreshFromBackend()
        }
        
        // Build 6×8 slot grid
        buildSlotGrid()
    }
    
    /**
     * Build the slot grid UI (6 rows × 8 columns)
     */
    private fun buildSlotGrid() {
        slotsGridContainer.removeAllViews()
        slotViews.clear()
        
        for (row in 1..6) {
            val rowLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 16)
                }
            }
            
            for (col in 1..8) {
                val slotNumber = (row - 1) * 10 + col
                val slotCard = SlotCardView(requireContext(), slotNumber)
                
                slotCard.layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    setMargins(8, 0, 8, 0)
                }
                
                slotViews[slotNumber] = slotCard
                rowLayout.addView(slotCard)
            }
            
            slotsGridContainer.addView(rowLayout)
        }
    }
    
    /**
     * Load slot inventory from local cache
     */
    private fun loadSlotInventory() {
        val allSlots = slotInventoryManager.getAllSlots()
        
        // Update each slot card
        for (slotData in allSlots) {
            slotViews[slotData.slot]?.updateData(
                remainingBottles = slotData.remainingBottles,
                capacity = slotData.capacity,
                canDesignId = slotData.canDesignId,
                status = slotData.status
            )
        }
        
        // Update totals
        updateTotals(allSlots)
        
        // Update status message
        if (!com.waterfountainmachine.app.security.SecurityModule.isEnrolled()) {
            statusText.text = "⚠️ Machine not enrolled. Showing cached data. Enroll machine to sync."
            statusText.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_orange_dark))
        } else {
            statusText.text = "Showing cached inventory. Click Refresh to sync with backend."
            statusText.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
        }
    }
    
    /**
     * Refresh inventory from backend
     */
    private fun refreshFromBackend() {
        if (isRefreshing) {
            AppLog.d(TAG, "Refresh already in progress")
            return
        }
        
        isRefreshing = true
        refreshButton.isEnabled = false
        statusText.text = "Syncing with backend..."
        statusText.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_blue_dark))
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Check if machine is enrolled
                if (!com.waterfountainmachine.app.security.SecurityModule.isEnrolled()) {
                    withContext(Dispatchers.Main) {
                        statusText.text = "Error: Machine not enrolled. Enroll machine first."
                        statusText.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
                        isRefreshing = false
                        refreshButton.isEnabled = true
                    }
                    return@launch
                }
                
                // Get machine ID from certificate
                val machineId = com.waterfountainmachine.app.security.SecurityModule.getMachineId()
                
                if (machineId == null) {
                    withContext(Dispatchers.Main) {
                        statusText.text = "Error: Could not extract machine ID from certificate"
                        statusText.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
                        isRefreshing = false
                        refreshButton.isEnabled = true
                    }
                    return@launch
                }
                
                AppLog.d(TAG, "Syncing inventory for machine: $machineId")
                
                // Sync with backend
                val result = backendSlotService.syncInventoryWithBackend(machineId)
                
                result.fold(
                    onSuccess = { slots ->
                        AppLog.i(TAG, "Successfully synced ${slots.size} slots")
                        
                        withContext(Dispatchers.Main) {
                            statusText.text = "✓ Synced ${slots.size} slots"
                            statusText.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))
                            
                            // Update sync status banner
                            updateSyncStatusBanner()
                            
                            // Reload UI with fresh data
                            loadSlotInventory()
                            
                            // Update next dispense queue
                            updateNextDispenseQueue()
                        }
                    },
                    onFailure = { error ->
                        AppLog.e(TAG, "Failed to sync inventory", error)
                        
                        withContext(Dispatchers.Main) {
                            statusText.text = "✗ Sync failed: ${error.message}"
                            statusText.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
                        }
                    }
                )
            } catch (e: Exception) {
                AppLog.e(TAG, "Error during refresh", e)
                
                withContext(Dispatchers.Main) {
                    statusText.text = "✗ Error: ${e.message}"
                    statusText.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isRefreshing = false
                    refreshButton.isEnabled = true
                }
            }
        }
    }
    
    /**
     * Update totals display
     */
    private fun updateTotals(slots: List<SlotInventoryManager.SlotInventory>) {
        val totalCans = slots.sumOf { it.remainingBottles }
        val filledSlots = slots.count { it.remainingBottles > 0 }
        val emptySlots = slots.count { it.remainingBottles == 0 }
        
        totalCansText.text = "$totalCans"
        filledSlotsText.text = "$filledSlots"
        emptySlotsText.text = "$emptySlots"
    }
    
    /**
     * Custom View for Slot Card
     */
    private inner class SlotCardView(context: Context, private val slotNumber: Int) : CardView(context) {
        
        private val slotNumberText: TextView
        private val bottlesText: TextView
        private val canDesignText: TextView
        private val statusBadge: TextView
        
        init {
            // Set card properties
            radius = 12f
            cardElevation = 4f
            setContentPadding(12, 12, 12, 12)
            
            // Create layout
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
                )
            }
            
            // Slot number
            slotNumberText = TextView(context).apply {
                text = slotNumber.toString()
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            container.addView(slotNumberText)
            
            // Bottles count
            bottlesText = TextView(context).apply {
                text = "?"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 8, 0, 0)
                }
            }
            container.addView(bottlesText)
            
            // Can design
            canDesignText = TextView(context).apply {
                text = ""
                textSize = 10f
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 4, 0, 0)
                }
            }
            container.addView(canDesignText)
            
            // Status badge
            statusBadge = TextView(context).apply {
                text = ""
                textSize = 9f
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 4, 0, 0)
                }
                setPadding(8, 4, 8, 4)
                setBackgroundResource(R.drawable.status_badge_background)
            }
            container.addView(statusBadge)
            
            addView(container)
            
            // Default state
            setCardBackgroundColor(ContextCompat.getColor(context, android.R.color.darker_gray))
        }
        
        fun updateData(
            remainingBottles: Int,
            capacity: Int,
            canDesignId: String?,
            status: SlotInventoryManager.SlotStatus
        ) {
            // Update bottles
            bottlesText.text = "🍶 $remainingBottles"
            
            // Update can design
            if (!canDesignId.isNullOrEmpty()) {
                canDesignText.text = "🏷️ ${canDesignId.take(15)}"
                canDesignText.visibility = View.VISIBLE
            } else {
                canDesignText.visibility = View.GONE
            }
            
            // Update status badge and colors
            when (status) {
                SlotInventoryManager.SlotStatus.ACTIVE -> {
                    if (remainingBottles == 0) {
                        setCardBackgroundColor(ContextCompat.getColor(context, android.R.color.holo_orange_light))
                        statusBadge.text = "EMPTY"
                        statusBadge.visibility = View.VISIBLE
                    } else if (remainingBottles <= (capacity * 0.2).toInt()) {
                        setCardBackgroundColor(ContextCompat.getColor(context, android.R.color.holo_orange_light))
                        statusBadge.visibility = View.GONE
                    } else {
                        setCardBackgroundColor(ContextCompat.getColor(context, android.R.color.holo_green_light))
                        statusBadge.visibility = View.GONE
                    }
                }
                SlotInventoryManager.SlotStatus.EMPTY -> {
                    setCardBackgroundColor(ContextCompat.getColor(context, android.R.color.darker_gray))
                    statusBadge.text = "EMPTY"
                    statusBadge.visibility = View.VISIBLE
                }
                SlotInventoryManager.SlotStatus.DISABLED -> {
                    setCardBackgroundColor(ContextCompat.getColor(context, android.R.color.darker_gray))
                    statusBadge.text = "DISABLED"
                    statusBadge.visibility = View.VISIBLE
                }
                SlotInventoryManager.SlotStatus.MAINTENANCE -> {
                    setCardBackgroundColor(ContextCompat.getColor(context, android.R.color.holo_blue_light))
                    statusBadge.text = "MAINTENANCE"
                    statusBadge.visibility = View.VISIBLE
                }
            }
        }
    }
    
    /**
     * Update sync status banner based on backend slot service mode
     */
    private fun updateSyncStatusBanner() {
        val useMockMode = BackendModule.loadSlotServiceModePreference(requireContext())
        val lastSync = slotInventoryManager.getLastSyncTimestamp()
        
        if (useMockMode) {
            // Local only mode
            syncStatusBanner.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_orange_light))
            syncStatusTitle.text = "⚠️ LOCAL ONLY MODE"
            syncStatusMessage.text = "Inventory not syncing with backend\nChanges are local only"
            lastSyncText.text = "Backend sync: DISABLED"
        } else {
            // Synced mode
            syncStatusBanner.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_light))
            syncStatusTitle.text = "✅ SYNCED WITH BACKEND"
            syncStatusMessage.text = "Inventory synchronized with Firebase"
            
            if (lastSync > 0) {
                val timeAgo = getTimeAgo(lastSync)
                lastSyncText.text = "Last sync: $timeAgo"
            } else {
                lastSyncText.text = "Last sync: Never"
            }
        }
    }
    
    /**
     * Update next dispense queue showing all upcoming slots
     */
    private fun updateNextDispenseQueue() {
        val laneReport = laneManager.getLaneStatusReport()
        val currentLane = laneReport.currentLane
        
        // Update current lane text
        val currentSlotInventory = slotInventoryManager.getSlotInventory(currentLane)
        if (currentSlotInventory != null) {
            currentLaneText.text = "Next slot: #$currentLane (${currentSlotInventory.remainingBottles} bottles remaining)"
        } else {
            currentLaneText.text = "Next slot: #$currentLane (no data)"
        }
        
        // Clear previous queue
        dispenseQueueContainer.removeAllViews()
        
        // Build full queue list (all available slots from current position)
        val queueSlots = buildFullDispenseQueue(currentLane)
        
        // Add header row
        val headerRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(8, 8, 8, 8)
            setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
        }
        
        headerRow.addView(TextView(requireContext()).apply {
            text = "#"
            layoutParams = LinearLayout.LayoutParams(60, LinearLayout.LayoutParams.WRAP_CONTENT)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
        })
        headerRow.addView(TextView(requireContext()).apply {
            text = "Bottles"
            layoutParams = LinearLayout.LayoutParams(100, LinearLayout.LayoutParams.WRAP_CONTENT)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
        })
        headerRow.addView(TextView(requireContext()).apply {
            text = "Status"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
        })
        headerRow.addView(TextView(requireContext()).apply {
            text = "Notes"
            layoutParams = LinearLayout.LayoutParams(100, LinearLayout.LayoutParams.WRAP_CONTENT)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
        })
        
        dispenseQueueContainer.addView(headerRow)
        
        // Add queue rows
        queueSlots.forEachIndexed { index, queueItem ->
            val row = createQueueRow(queueItem, index == 0)
            dispenseQueueContainer.addView(row)
        }
        
        AppLog.d(TAG, "Next dispense queue updated: ${queueSlots.size} slots")
    }
    
    /**
     * Build full dispense queue from current lane
     */
    private fun buildFullDispenseQueue(startLane: Int): List<QueueItem> {
        val queue = mutableListOf<QueueItem>()
        val enabledLanes = listOf(
            1, 11, 21, 31, 41, 51,
            2, 12, 22, 32, 42, 52,
            3, 13, 23, 33, 43, 53,
            4, 14, 24, 34, 44, 54,
            5, 15, 25, 35, 45, 55,
            6, 16, 26, 36, 46, 56,
            7, 17, 27, 37, 47, 57,
            8, 18, 28, 38, 48, 58
        )
        
        val startIndex = enabledLanes.indexOf(startLane)
        if (startIndex == -1) return queue
        
        // Build queue starting from current lane, wrapping around
        for (i in 0 until enabledLanes.size) {
            val index = (startIndex + i) % enabledLanes.size
            val lane = enabledLanes[index]
            val inventory = slotInventoryManager.getSlotInventory(lane)
            
            if (inventory != null) {
                val willSkip = !slotInventoryManager.isSlotAvailable(lane)
                queue.add(QueueItem(
                    slot = lane,
                    remainingBottles = inventory.remainingBottles,
                    capacity = inventory.capacity,
                    status = inventory.status,
                    willSkip = willSkip
                ))
            }
        }
        
        return queue
    }
    
    /**
     * Create a queue row view
     */
    private fun createQueueRow(item: QueueItem, isNext: Boolean): LinearLayout {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(8, 8, 8, 8)
            
            // Highlight next slot
            if (isNext) {
                setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_blue_light))
            }
        }
        
        // Slot number
        row.addView(TextView(requireContext()).apply {
            text = item.slot.toString()
            layoutParams = LinearLayout.LayoutParams(60, LinearLayout.LayoutParams.WRAP_CONTENT)
            setTypeface(null, if (isNext) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        })
        
        // Bottles
        row.addView(TextView(requireContext()).apply {
            text = "${item.remainingBottles}/${item.capacity}"
            layoutParams = LinearLayout.LayoutParams(100, LinearLayout.LayoutParams.WRAP_CONTENT)
            
            // Color code based on inventory
            when {
                item.remainingBottles == 0 -> setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
                item.remainingBottles <= (item.capacity * 0.3) -> setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_orange_dark))
                else -> setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))
            }
        })
        
        // Status
        row.addView(TextView(requireContext()).apply {
            text = when (item.status) {
                SlotInventoryManager.SlotStatus.ACTIVE -> "Active"
                SlotInventoryManager.SlotStatus.EMPTY -> "Empty"
                SlotInventoryManager.SlotStatus.DISABLED -> "Disabled"
                SlotInventoryManager.SlotStatus.MAINTENANCE -> "Maintenance"
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        
        // Notes
        row.addView(TextView(requireContext()).apply {
            text = when {
                isNext -> "← NEXT"
                item.willSkip -> "SKIP"
                else -> ""
            }
            layoutParams = LinearLayout.LayoutParams(100, LinearLayout.LayoutParams.WRAP_CONTENT)
            setTypeface(null, android.graphics.Typeface.BOLD)
            
            if (item.willSkip) {
                setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
            } else if (isNext) {
                setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_blue_dark))
            }
        })
        
        return row
    }
    
    /**
     * Get human-readable time ago string
     */
    private fun getTimeAgo(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        
        return when {
            diff < 60_000 -> "Just now"
            diff < 3600_000 -> "${diff / 60_000} minutes ago"
            diff < 86400_000 -> "${diff / 3600_000} hours ago"
            else -> SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
    }
    
    /**
     * Data class for queue items
     */
    private data class QueueItem(
        val slot: Int,
        val remainingBottles: Int,
        val capacity: Int,
        val status: SlotInventoryManager.SlotStatus,
        val willSkip: Boolean
    )
}
