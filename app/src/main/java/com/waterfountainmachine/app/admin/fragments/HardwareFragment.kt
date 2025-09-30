package com.waterfountainmachine.app.admin.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.waterfountainmachine.app.databinding.FragmentHardwareBinding
import com.waterfountainmachine.app.hardware.WaterFountainManager
import com.waterfountainmachine.app.utils.AppLog
import kotlinx.coroutines.launch

class HardwareFragment : Fragment() {
    
    private var _binding: FragmentHardwareBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var waterFountainManager: WaterFountainManager
    private var currentSlot = 1
    
    // Button debouncing
    private var isProcessing = false
    
    companion object {
        private const val TAG = "HardwareFragment"
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHardwareBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initializeHardware()
        setupUI()
        updateSlotDisplay()
        runInitialDiagnostics()
    }
    
    private fun initializeHardware() {
        waterFountainManager = WaterFountainManager.getInstance(requireContext())
        
        // Initialize the hardware if not already initialized
        lifecycleScope.launch {
            try {
                if (!waterFountainManager.isConnected()) {
                    binding.systemStatusText.text = "Initializing hardware..."
                    val success = waterFountainManager.initialize()
                    if (success) {
                        binding.systemStatusText.text = "Hardware initialized"
                        runInitialDiagnostics()
                    } else {
                        binding.systemStatusText.text = "Hardware initialization failed"
                    }
                }
            } catch (e: Exception) {
                binding.systemStatusText.text = "Initialization error: ${e.message}"
            }
        }
    }
    
    private fun setupUI() {
        // Slot management
        binding.slotPreviousButton.setOnClickListener {
            if (currentSlot > 1) {
                currentSlot--
                updateSlotDisplay()
            }
        }
        
        binding.slotNextButton.setOnClickListener {
            if (currentSlot < 10) { // 10 slots based on lane manager
                currentSlot++
                updateSlotDisplay()
            }
        }
        
        binding.resetSlotButton.setOnClickListener {
            if (!isProcessing) resetCurrentSlot()
        }
        
        binding.resetAllSlotsButton.setOnClickListener {
            if (!isProcessing) resetAllSlots()
        }
        
        // Dispenser controls
        binding.testDispenserButton.setOnClickListener {
            if (!isProcessing) testWaterDispenser()
        }
        
        // Diagnostics
        binding.runFullDiagnosticsButton.setOnClickListener {
            if (!isProcessing) runFullDiagnostics()
        }
        
        binding.clearErrorsButton.setOnClickListener {
            if (!isProcessing) clearAllErrors()
        }
        
        // Remove unused buttons
        binding.calibrateSlotButton.visibility = View.GONE
        binding.emergencyStopButton.visibility = View.GONE
        binding.primeSystemButton.visibility = View.GONE
        binding.checkSensorsButton.visibility = View.GONE
        binding.testMotorsButton.visibility = View.GONE
    }
    
    private fun updateSlotDisplay() {
        binding.currentSlotText.text = "Slot $currentSlot"
        
        // Update slot-specific info
        lifecycleScope.launch {
            try {
                val slotInfo = getSlotInfo(currentSlot)
                binding.slotStatusText.text = slotInfo.status
                binding.slotBottleCountText.text = "Bottles: ${slotInfo.bottleCount}"
                binding.slotMotorStatusText.text = "Motor: ${slotInfo.motorStatus}"
                binding.slotSensorStatusText.text = "Sensor: ${slotInfo.sensorStatus}"
                
                // Update slot status indicator
                binding.slotStatusIndicator.setBackgroundResource(
                    when (slotInfo.status) {
                        "Ready" -> android.R.drawable.presence_online
                        "Error" -> android.R.drawable.presence_busy
                        else -> android.R.drawable.presence_offline
                    }
                )
            } catch (e: Exception) {
                binding.slotStatusText.text = "Error: ${e.message}"
                binding.slotStatusIndicator.setBackgroundResource(android.R.drawable.presence_busy)
            }
        }
    }
    
    private fun resetCurrentSlot() {
        if (isProcessing) return
        isProcessing = true
        binding.slotStatusText.text = "Resetting slot $currentSlot..."
        binding.resetSlotButton.isEnabled = false
        
        lifecycleScope.launch {
            try {
                val success = waterFountainManager.resetSlot(currentSlot)
                
                if (success) {
                    Toast.makeText(context, "Slot $currentSlot reset successfully", Toast.LENGTH_SHORT).show()
                    updateSlotDisplay()
                } else {
                    Toast.makeText(context, "Failed to reset slot $currentSlot", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error resetting slot: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isProcessing = false
                binding.resetSlotButton.isEnabled = true
            }
        }
    }
    
    private fun resetAllSlots() {
        if (isProcessing) return
        isProcessing = true
        binding.systemStatusText.text = "Resetting all slots..."
        binding.resetAllSlotsButton.isEnabled = false
        
        lifecycleScope.launch {
            try {
                var successCount = 0
                
                for (slot in 1..10) {
                    val success = waterFountainManager.resetSlot(slot)
                    if (success) successCount++
                    
                    // Update progress
                    binding.systemStatusText.text = "Resetting slots... ($slot/10)"
                    kotlinx.coroutines.delay(300)
                }
                
                binding.systemStatusText.text = "Reset complete: $successCount/10 slots"
                Toast.makeText(context, "Reset complete: $successCount/10 slots", Toast.LENGTH_SHORT).show()
                
                updateSlotDisplay()
                runInitialDiagnostics()
            } catch (e: Exception) {
                binding.systemStatusText.text = "Error during reset: ${e.message}"
                Toast.makeText(context, "Error resetting slots: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isProcessing = false
                binding.resetAllSlotsButton.isEnabled = true
            }
        }
    }
    
    private fun testWaterDispenser() {
        if (isProcessing) return
        
        // Check if system is connected first
        if (!waterFountainManager.isConnected()) {
            Toast.makeText(context, "Hardware not initialized. Please wait...", Toast.LENGTH_LONG).show()
            binding.dispenserStatusText.text = "Hardware not ready"
            return
        }
        
        AppLog.i(TAG, "Testing water dispenser on slot $currentSlot")
        isProcessing = true
        binding.dispenserStatusText.text = "Testing water dispenser slot $currentSlot..."
        binding.testDispenserButton.isEnabled = false
        
        lifecycleScope.launch {
            try {
                val success = waterFountainManager.testDispenser(currentSlot)
                
                binding.dispenserStatusText.text = if (success) {
                    binding.dispenserStatusIndicator.setBackgroundResource(android.R.drawable.presence_online)
                    AppLog.i(TAG, "Dispenser test on slot $currentSlot: SUCCESS")
                    "✓ Dispenser test passed (slot $currentSlot)"
                } else {
                    binding.dispenserStatusIndicator.setBackgroundResource(android.R.drawable.presence_busy)
                    AppLog.w(TAG, "Dispenser test on slot $currentSlot: FAILED")
                    "✗ Dispenser test failed (slot $currentSlot)"
                }
                Toast.makeText(context, if (success) "Test successful" else "Test failed - check hardware", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                AppLog.e(TAG, "Error testing dispenser on slot $currentSlot", e)
                binding.dispenserStatusText.text = "✗ Error: ${e.message}"
                binding.dispenserStatusIndicator.setBackgroundResource(android.R.drawable.presence_busy)
                Toast.makeText(context, "Test error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isProcessing = false
                binding.testDispenserButton.isEnabled = true
            }
        }
    }
    
    private fun runFullDiagnostics() {
        if (isProcessing) return
        
        if (!waterFountainManager.isConnected()) {
            Toast.makeText(context, "Hardware not initialized. Attempting to initialize...", Toast.LENGTH_LONG).show()
            initializeHardware()
            return
        }
        
        isProcessing = true
        binding.systemStatusText.text = "Running full diagnostics..."
        binding.runFullDiagnosticsButton.isEnabled = false
        
        lifecycleScope.launch {
            try {
                val diagnosticsMap = waterFountainManager.runFullDiagnostics()
                
                binding.systemStatusText.text = "Diagnostics complete"
                
                // Format the results as strings
                val results = diagnosticsMap.entries.map { "${it.key}: ${it.value}" }
                binding.diagnosticsResultText.text = results.joinToString("\n")
                
                // Update system status based on results
                val resultsText = results.joinToString(" ")
                val hasErrors = resultsText.contains("error", ignoreCase = true) || 
                                resultsText.contains("false", ignoreCase = true)
                binding.systemStatusIndicator.setBackgroundResource(
                    if (hasErrors) android.R.drawable.presence_busy 
                    else android.R.drawable.presence_online
                )
                
                Toast.makeText(context, "Diagnostics complete", Toast.LENGTH_SHORT).show()
                
            } catch (e: Exception) {
                binding.systemStatusText.text = "Diagnostics failed: ${e.message}"
                binding.systemStatusIndicator.setBackgroundResource(android.R.drawable.presence_busy)
                Toast.makeText(context, "Diagnostics failed: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isProcessing = false
                binding.runFullDiagnosticsButton.isEnabled = true
            }
        }
    }
    
    private fun clearAllErrors() {
        if (isProcessing) return
        
        if (!waterFountainManager.isConnected()) {
            Toast.makeText(context, "Hardware not initialized. Please wait...", Toast.LENGTH_LONG).show()
            return
        }
        
        isProcessing = true
        binding.clearErrorsButton.isEnabled = false
        
        lifecycleScope.launch {
            try {
                val success = waterFountainManager.clearAllErrors()
                
                if (success) {
                    binding.systemStatusText.text = "All errors cleared"
                    Toast.makeText(context, "All errors cleared successfully", Toast.LENGTH_SHORT).show()
                    
                    // Refresh displays
                    updateSlotDisplay()
                    runInitialDiagnostics()
                } else {
                    binding.systemStatusText.text = "Failed to clear errors"
                    Toast.makeText(context, "Failed to clear errors", Toast.LENGTH_SHORT).show()
                }
                
            } catch (e: Exception) {
                binding.systemStatusText.text = "Failed to clear errors: ${e.message}"
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isProcessing = false
                binding.clearErrorsButton.isEnabled = true
            }
        }
    }
    
    private fun runInitialDiagnostics() {
        lifecycleScope.launch {
            try {
                // Quick system status check
                val isConnected = waterFountainManager.isConnected()
                
                binding.systemStatusIndicator.setBackgroundResource(
                    if (isConnected) android.R.drawable.presence_online 
                    else android.R.drawable.presence_offline
                )
                
                binding.systemStatusText.text = if (isConnected) {
                    "System connected and ready"
                } else {
                    "System disconnected"
                }
                
                // Show dispenser ready status
                if (isConnected) {
                    binding.dispenserStatusIndicator.setBackgroundResource(android.R.drawable.presence_online)
                    binding.dispenserStatusText.text = "Dispenser ready"
                } else {
                    binding.dispenserStatusIndicator.setBackgroundResource(android.R.drawable.presence_offline)
                    binding.dispenserStatusText.text = "Dispenser offline"
                }
                
            } catch (e: Exception) {
                binding.systemStatusText.text = "Initialization error: ${e.message}"
                binding.systemStatusIndicator.setBackgroundResource(android.R.drawable.presence_busy)
            }
        }
    }
    
    private suspend fun getSlotInfo(slotNumber: Int): SlotInfo {
        return try {
            val status = waterFountainManager.getSlotStatus(slotNumber)
            val laneReport = waterFountainManager.getLaneStatusReport()
            val lane = laneReport.lanes.find { it.lane == slotNumber }
            
            SlotInfo(
                status = status,
                bottleCount = lane?.failureCount ?: 0,
                motorStatus = if (lane?.isUsable == true) "OK" else "Check",
                sensorStatus = "OK"
            )
        } catch (e: Exception) {
            SlotInfo("Error", 0, "Unknown", "Unknown")
        }
    }
    
    data class SlotInfo(
        val status: String,
        val bottleCount: Int,
        val motorStatus: String,
        val sensorStatus: String
    )
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
