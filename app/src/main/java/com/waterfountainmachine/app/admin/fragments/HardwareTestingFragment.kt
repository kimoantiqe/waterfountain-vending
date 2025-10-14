package com.waterfountainmachine.app.admin.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.waterfountainmachine.app.WaterFountainApplication
import com.waterfountainmachine.app.databinding.FragmentHardwareTestingBinding
import com.waterfountainmachine.app.utils.AppLog
import kotlinx.coroutines.launch

/**
 * Hardware Testing Panel
 * Test connection, individual lanes, and clear faults
 */
class HardwareTestingFragment : Fragment() {
    
    private var _binding: FragmentHardwareTestingBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var app: WaterFountainApplication
    
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
        
        setupUI()
        updateStatus()
    }
    
    private fun setupUI() {
        // Test connection
        binding.testConnectionButton.setOnClickListener {
            testConnection()
        }
        
        // Clear faults
        binding.clearFaultsButton.setOnClickListener {
            clearFaults()
        }
        
        // Lane test buttons
        binding.testLane1Button.setOnClickListener { testLane(1) }
        binding.testLane2Button.setOnClickListener { testLane(2) }
        binding.testLane3Button.setOnClickListener { testLane(3) }
        binding.testLane4Button.setOnClickListener { testLane(4) }
        binding.testLane5Button.setOnClickListener { testLane(5) }
        binding.testLane6Button.setOnClickListener { testLane(6) }
        binding.testLane7Button.setOnClickListener { testLane(7) }
        binding.testLane8Button.setOnClickListener { testLane(8) }
        
        // Test all lanes
        binding.testAllLanesButton.setOnClickListener {
            testAllLanes()
        }
    }
    
    private fun updateStatus() {
        val isReady = app.isHardwareReady()
        val statusText = if (isReady) "✅ Hardware Ready" else "❌ Hardware Not Ready"
        
        binding.hardwareStatusText.text = statusText
        
        // Enable/disable buttons based on hardware state
        val enabled = isReady
        binding.testConnectionButton.isEnabled = enabled
        binding.clearFaultsButton.isEnabled = enabled
        binding.testLane1Button.isEnabled = enabled
        binding.testLane2Button.isEnabled = enabled
        binding.testLane3Button.isEnabled = enabled
        binding.testLane4Button.isEnabled = enabled
        binding.testLane5Button.isEnabled = enabled
        binding.testLane6Button.isEnabled = enabled
        binding.testLane7Button.isEnabled = enabled
        binding.testLane8Button.isEnabled = enabled
        binding.testAllLanesButton.isEnabled = enabled
    }
    
    private fun testConnection() {
        lifecycleScope.launch {
            try {
                binding.testResultText.text = "Testing connection..."
                AppLog.i(TAG, "Testing hardware connection...")
                
                val startTime = System.currentTimeMillis()
                
                // Get device ID as connection test
                val result = app.hardwareManager.getDeviceStatus()
                
                val elapsed = System.currentTimeMillis() - startTime
                
                if (result != null && result.startsWith("Connected:")) {
                    val deviceId = result.substringAfter("Connected: ")
                    binding.testResultText.text = "✅ Connection OK\nDevice: $deviceId\nResponse time: ${elapsed}ms"
                    AppLog.i(TAG, "✅ Connection test passed: $deviceId (${elapsed}ms)")
                    Toast.makeText(requireContext(), "Connection test passed!", Toast.LENGTH_SHORT).show()
                } else {
                    binding.testResultText.text = "❌ Connection Failed\n$result\nResponse time: ${elapsed}ms"
                    AppLog.e(TAG, "❌ Connection test failed: $result")
                    Toast.makeText(requireContext(), "Connection test failed!", Toast.LENGTH_SHORT).show()
                }
                
            } catch (e: Exception) {
                binding.testResultText.text = "❌ Error: ${e.message}"
                AppLog.e(TAG, "Connection test error", e)
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun clearFaults() {
        lifecycleScope.launch {
            try {
                binding.testResultText.text = "Clearing faults..."
                AppLog.i(TAG, "Clearing hardware faults...")
                
                val success = app.hardwareManager.clearFaults()
                
                if (success) {
                    binding.testResultText.text = "✅ Faults Cleared Successfully"
                    AppLog.i(TAG, "✅ Faults cleared")
                    Toast.makeText(requireContext(), "Faults cleared successfully", Toast.LENGTH_SHORT).show()
                } else {
                    binding.testResultText.text = "❌ Failed to Clear Faults"
                    AppLog.e(TAG, "❌ Failed to clear faults")
                    Toast.makeText(requireContext(), "Failed to clear faults", Toast.LENGTH_SHORT).show()
                }
                
            } catch (e: Exception) {
                binding.testResultText.text = "❌ Error: ${e.message}"
                AppLog.e(TAG, "Clear faults error", e)
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun testLane(lane: Int) {
        lifecycleScope.launch {
            try {
                binding.testResultText.text = "Testing lane $lane..."
                AppLog.i(TAG, "Testing lane $lane...")
                
                // Disable all lane buttons during test
                setLaneButtonsEnabled(false)
                
                val startTime = System.currentTimeMillis()
                
                // Test dispenser
                val success = app.hardwareManager.testDispenser(lane)
                
                val elapsed = System.currentTimeMillis() - startTime
                
                if (success) {
                    binding.testResultText.text = "✅ Lane $lane: Test Passed\nDispensing time: ${elapsed}ms"
                    AppLog.i(TAG, "✅ Lane $lane test passed (${elapsed}ms)")
                    Toast.makeText(requireContext(), "Lane $lane test passed!", Toast.LENGTH_SHORT).show()
                    
                    // Highlight successful lane button
                    highlightLaneButton(lane, true)
                } else {
                    binding.testResultText.text = "❌ Lane $lane: Test Failed\nTime: ${elapsed}ms"
                    AppLog.e(TAG, "❌ Lane $lane test failed")
                    Toast.makeText(requireContext(), "Lane $lane test failed!", Toast.LENGTH_SHORT).show()
                    
                    // Highlight failed lane button
                    highlightLaneButton(lane, false)
                }
                
                // Re-enable buttons
                setLaneButtonsEnabled(true)
                
            } catch (e: Exception) {
                binding.testResultText.text = "❌ Lane $lane Error: ${e.message}"
                AppLog.e(TAG, "Lane $lane test error", e)
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
                setLaneButtonsEnabled(true)
            }
        }
    }
    
    private fun testAllLanes() {
        lifecycleScope.launch {
            try {
                binding.testResultText.text = "Testing all lanes..."
                AppLog.i(TAG, "Testing all lanes...")
                
                setLaneButtonsEnabled(false)
                
                val results = mutableListOf<String>()
                var successCount = 0
                
                for (lane in 1..8) {
                    binding.testResultText.text = "Testing all lanes...\nCurrent: Lane $lane"
                    
                    val success = app.hardwareManager.testDispenser(lane)
                    
                    if (success) {
                        results.add("Lane $lane: ✅")
                        successCount++
                        highlightLaneButton(lane, true)
                    } else {
                        results.add("Lane $lane: ❌")
                        highlightLaneButton(lane, false)
                    }
                    
                    kotlinx.coroutines.delay(500) // Brief delay between tests
                }
                
                val summary = "All Lanes Test Complete\n✅ $successCount/8 passed\n\n${results.joinToString("\n")}"
                binding.testResultText.text = summary
                AppLog.i(TAG, "All lanes test complete: $successCount/8 passed")
                
                Toast.makeText(
                    requireContext(),
                    "$successCount out of 8 lanes passed",
                    Toast.LENGTH_LONG
                ).show()
                
                setLaneButtonsEnabled(true)
                
            } catch (e: Exception) {
                binding.testResultText.text = "❌ Error: ${e.message}"
                AppLog.e(TAG, "Test all lanes error", e)
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
                setLaneButtonsEnabled(true)
            }
        }
    }
    
    private fun setLaneButtonsEnabled(enabled: Boolean) {
        binding.testLane1Button.isEnabled = enabled
        binding.testLane2Button.isEnabled = enabled
        binding.testLane3Button.isEnabled = enabled
        binding.testLane4Button.isEnabled = enabled
        binding.testLane5Button.isEnabled = enabled
        binding.testLane6Button.isEnabled = enabled
        binding.testLane7Button.isEnabled = enabled
        binding.testLane8Button.isEnabled = enabled
        binding.testAllLanesButton.isEnabled = enabled
    }
    
    private fun highlightLaneButton(lane: Int, success: Boolean) {
        val color = if (success) android.graphics.Color.parseColor("#4CAF50") 
                    else android.graphics.Color.parseColor("#F44336")
        
        val button = when (lane) {
            1 -> binding.testLane1Button
            2 -> binding.testLane2Button
            3 -> binding.testLane3Button
            4 -> binding.testLane4Button
            5 -> binding.testLane5Button
            6 -> binding.testLane6Button
            7 -> binding.testLane7Button
            8 -> binding.testLane8Button
            else -> return
        }
        
        button.setBackgroundColor(color)
        
        // Reset color after 2 seconds
        button.postDelayed({
            button.setBackgroundColor(android.graphics.Color.parseColor("#2196F3"))
        }, 2000)
    }
    
    override fun onResume() {
        super.onResume()
        updateStatus()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
