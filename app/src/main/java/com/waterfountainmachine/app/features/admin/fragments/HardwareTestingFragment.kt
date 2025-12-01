package com.waterfountainmachine.app.admin.fragments

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.waterfountainmachine.app.R
import com.waterfountainmachine.app.WaterFountainApplication
import com.waterfountainmachine.app.databinding.FragmentHardwareTestingBinding
import com.waterfountainmachine.app.hardware.sdk.SlotValidator
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.utils.AdminDebugConfig
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hardware Testing Panel
 * Test individual slots (48 total) and verify hardware status
 * Supports 48 slots in 6 rows: 1-8, 11-18, 21-28, 31-38, 41-48, 51-58
 */
class HardwareTestingFragment : Fragment() {
    
    private var _binding: FragmentHardwareTestingBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var app: WaterFountainApplication
    private val slotButtons = mutableMapOf<Int, Button>()
    
    companion object {
        private const val TAG = "HardwareTestingFrag"
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHardwareTestingBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        app = requireActivity().application as WaterFountainApplication
        
        // Observe hardware state changes using StateFlow (lifecycle-safe)
        viewLifecycleOwner.lifecycleScope.launch {
            app.hardwareStateFlow.collect { state ->
                updateStatus()
            }
        }
        
        createSlotButtons()
        setupUI()
        updateStatus()
    }
    
    private fun createSlotButtons() {
        // Create buttons for all 48 valid slots organized in 6 rows
        binding.slotsContainer.removeAllViews()
        
        for (row in 1..6) {
            // Create row header
            val rowHeaderLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 16, 0, 8)
                }
            }
            
            val rowHeader = android.widget.TextView(requireContext()).apply {
                text = "Row $row"
                textSize = 14f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            rowHeaderLayout.addView(rowHeader)
            binding.slotsContainer.addView(rowHeaderLayout)
            
            // Create row container for slot buttons
            val rowLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 8)
                }
            }
            
            val slots = SlotValidator.getSlotsInRow(row)
            for (slot in slots) {
                val button = Button(requireContext()).apply {
                    text = slot.toString()
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        setMargins(4, 0, 4, 0)
                    }
                    setOnClickListener { testSlot(slot) }
                }
                slotButtons[slot] = button
                rowLayout.addView(button)
            }
            
            binding.slotsContainer.addView(rowLayout)
        }
    }
    
    private fun setupUI() {
        // Test all slots button
        binding.testAllSlotsButton.setOnClickListener {
            testAllSlots()
        }
        
        // Get device status button
        binding.testConnectionButton.text = "Get Device Status"
        binding.testConnectionButton.setOnClickListener {
            getDeviceStatus()
        }
        
        // Hide clear faults button (not implemented in vendor SDK)
        binding.clearFaultsButton.visibility = View.GONE
    }
    
    private fun updateStatus() {
        val state = app.hardwareState
        val isReady = app.isHardwareReady()
        
        // Update status text with detailed state
        val statusText = when (state) {
            WaterFountainApplication.HardwareState.UNINITIALIZED -> 
                "❌ Hardware Not Initialized"
            WaterFountainApplication.HardwareState.INITIALIZING -> 
                "🔄 Initializing..."
            WaterFountainApplication.HardwareState.READY -> 
                "✅ Hardware Ready"
            WaterFountainApplication.HardwareState.ERROR -> 
                "❌ Hardware Error - Check logs"
            WaterFountainApplication.HardwareState.MAINTENANCE_MODE -> 
                "🔧 Maintenance Mode"
            WaterFountainApplication.HardwareState.DISCONNECTED -> 
                "❌ Hardware Disconnected"
        }
        
        binding.hardwareStatusText.text = statusText
        binding.hardwareStatusText.setTextColor(
            ContextCompat.getColor(
                requireContext(), 
                if (isReady) R.color.status_success else R.color.status_error
            )
        )
        
        // Enable/disable buttons based on hardware state
        val enabled = isReady
        binding.testConnectionButton.isEnabled = enabled
        binding.testAllSlotsButton.isEnabled = enabled
        
        slotButtons.values.forEach { it.isEnabled = enabled }
        
        // Show warning if not ready
        if (!isReady) {
            binding.testResultText.text = "⚠️ Hardware is not ready.\n\nInitialize hardware from the Connection tab first."
        }
    }
    
    private fun getDeviceStatus() {
        lifecycleScope.launch {
            try {
                binding.testResultText.text = "Getting device status..."
                AdminDebugConfig.logAdminInfo(requireContext(), TAG, "Running device diagnostics...")
                
                val startTime = System.currentTimeMillis()
                
                // Run diagnostics instead of calling removed getDeviceStatus()
                val diagnostics = app.hardwareManager.runFullDiagnostics()
                val elapsed = System.currentTimeMillis() - startTime
                
                // Format diagnostics results
                val statusText = buildString {
                    append("✅ Device Diagnostics\n\n")
                    diagnostics.forEach { (key, value) ->
                        append("$key: $value\n")
                    }
                    append("\nResponse time: ${elapsed}ms")
                }
                
                binding.testResultText.text = statusText
                AdminDebugConfig.logAdminInfo(requireContext(), TAG, "Device diagnostics completed (${elapsed}ms)")
                
            } catch (e: Exception) {
                binding.testResultText.text = "❌ Error: ${e.message}"
                AppLog.e(TAG, "Get device status error", e)
            }
        }
    }
    
    private fun testSlot(slot: Int) {
        lifecycleScope.launch {
            try {
                val position = SlotValidator.getSlotPosition(slot) ?: "Unknown"
                binding.testResultText.text = "Testing slot $slot ($position)..."
                AdminDebugConfig.logAdminInfo(requireContext(), TAG, "Testing slot $slot...")
                
                // Disable all slot buttons during test
                setSlotButtonsEnabled(false)
                
                val startTime = System.currentTimeMillis()
                
                // ⚡ FIX: Run hardware operation on IO dispatcher to prevent blocking UI thread
                val success = withContext(Dispatchers.IO) {
                    app.hardwareManager.testDispenser(slot)
                }
                
                val elapsed = System.currentTimeMillis() - startTime
                
                if (success) {
                    binding.testResultText.text = "✅ Slot $slot: Test Passed\n$position\nDispensing time: ${elapsed}ms"
                    AdminDebugConfig.logAdminInfo(requireContext(), TAG, "Slot $slot test passed (${elapsed}ms)")
                    
                    // Highlight successful slot button
                    highlightSlotButton(slot, true)
                } else {
                    binding.testResultText.text = "❌ Slot $slot: Test Failed\n$position\nTime: ${elapsed}ms"
                    AppLog.e(TAG, "Slot $slot test failed")
                    
                    // Highlight failed slot button
                    highlightSlotButton(slot, false)
                }
                
                // Re-enable buttons
                setSlotButtonsEnabled(true)
                
            } catch (e: Exception) {
                binding.testResultText.text = "❌ Slot $slot Error: ${e.message}"
                AppLog.e(TAG, "Slot $slot test error", e)
                setSlotButtonsEnabled(true)
            }
        }
    }
    
    private fun testAllSlots() {
        lifecycleScope.launch {
            try {
                binding.testResultText.text = "Testing all 48 slots..."
                AdminDebugConfig.logAdminInfo(requireContext(), TAG, "Testing all slots...")
                
                setSlotButtonsEnabled(false)
                binding.testAllSlotsButton.isEnabled = false
                
                val results = mutableListOf<String>()
                var successCount = 0
                
                for (slot in SlotValidator.VALID_SLOTS) {
                    val position = SlotValidator.getSlotPosition(slot)
                    binding.testResultText.text = "Testing all slots...\nCurrent: Slot $slot ($position)"
                    
                    // ⚡ FIX: Run hardware operation on IO dispatcher
                    val success = withContext(Dispatchers.IO) {
                        app.hardwareManager.testDispenser(slot)
                    }
                    
                    if (success) {
                        results.add("Slot $slot: ✅")
                        successCount++
                        highlightSlotButton(slot, true)
                    } else {
                        results.add("Slot $slot: ❌")
                        highlightSlotButton(slot, false)
                    }
                    
                    kotlinx.coroutines.delay(300) // Brief delay between tests
                }
                
                val summary = "All Slots Test Complete\n✅ $successCount/48 passed\n\n${results.take(10).joinToString("\n")}\n... (${results.size - 10} more)"
                binding.testResultText.text = summary
                AdminDebugConfig.logAdminInfo(requireContext(), TAG, "All slots test complete: $successCount/48 passed")
                
                setSlotButtonsEnabled(true)
                binding.testAllSlotsButton.isEnabled = true
                
            } catch (e: Exception) {
                binding.testResultText.text = "❌ Error: ${e.message}"
                AppLog.e(TAG, "Test all slots error", e)
                setSlotButtonsEnabled(true)
                binding.testAllSlotsButton.isEnabled = true
            }
        }
    }
    
    private fun setSlotButtonsEnabled(enabled: Boolean) {
        slotButtons.values.forEach { it.isEnabled = enabled }
    }
    
    private fun highlightSlotButton(slot: Int, success: Boolean) {
        val button = slotButtons[slot] ?: return
        val color = if (success) Color.parseColor("#4CAF50") 
                    else Color.parseColor("#F44336")
        
        button.setBackgroundColor(color)
        
        // Reset color after 2 seconds
        button.postDelayed({
            button.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.primary))
        }, 2000)
    }
    
    override fun onResume() {
        super.onResume()
        updateStatus()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        // StateFlow collection is automatically cancelled when viewLifecycleOwner is destroyed
        _binding = null
    }
}
