package com.waterfountainmachine.app.admin.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.waterfountainmachine.app.WaterFountainApplication
import com.waterfountainmachine.app.databinding.FragmentLaneDiagnosticsBinding
import com.waterfountainmachine.app.utils.AppLog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Lane Diagnostics Panel
 * Shows detailed statistics for all 8 lanes
 */
class LaneDiagnosticsFragment : Fragment() {
    
    private var _binding: FragmentLaneDiagnosticsBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var app: WaterFountainApplication
    
    companion object {
        private const val TAG = "LaneDiagnosticsFrag"
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLaneDiagnosticsBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        app = requireActivity().application as WaterFountainApplication
        
        setupUI()
        loadLaneStatistics()
    }
    
    private fun setupUI() {
        // Refresh button
        binding.refreshLanesButton.setOnClickListener {
            loadLaneStatistics()
        }
        
        // Reset all lanes
        binding.resetAllLanesButton.setOnClickListener {
            resetAllLanes()
        }
        
        // Individual lane reset buttons
        binding.resetLane1Button.setOnClickListener { resetLane(1) }
        binding.resetLane2Button.setOnClickListener { resetLane(2) }
        binding.resetLane3Button.setOnClickListener { resetLane(3) }
        binding.resetLane4Button.setOnClickListener { resetLane(4) }
        binding.resetLane5Button.setOnClickListener { resetLane(5) }
        binding.resetLane6Button.setOnClickListener { resetLane(6) }
        binding.resetLane7Button.setOnClickListener { resetLane(7) }
        binding.resetLane8Button.setOnClickListener { resetLane(8) }
    }
    
    private fun loadLaneStatistics() {
        lifecycleScope.launch {
            try {
                AppLog.d(TAG, "Loading lane statistics...")
                
                val laneReport = app.hardwareManager.getLaneStatusReport()
                
                // Update summary
                binding.currentLaneText.text = "Lane ${laneReport.currentLane}"
                binding.usableLanesText.text = "${laneReport.usableLanesCount}/${laneReport.lanes.size}"
                binding.totalDispensesText.text = laneReport.totalDispenses.toString()
                
                // Update individual lane cards
                updateLaneCard(1, laneReport.lanes.find { it.lane == 1 })
                updateLaneCard(2, laneReport.lanes.find { it.lane == 2 })
                updateLaneCard(3, laneReport.lanes.find { it.lane == 3 })
                updateLaneCard(4, laneReport.lanes.find { it.lane == 4 })
                updateLaneCard(5, laneReport.lanes.find { it.lane == 5 })
                updateLaneCard(6, laneReport.lanes.find { it.lane == 6 })
                updateLaneCard(7, laneReport.lanes.find { it.lane == 7 })
                updateLaneCard(8, laneReport.lanes.find { it.lane == 8 })
                
                // Update last refresh time
                binding.lastRefreshText.text = "Updated: ${getCurrentTime()}"
                
                AppLog.i(TAG, "Lane statistics loaded: Current=${laneReport.currentLane}, Usable=${laneReport.usableLanesCount}/8, Total=${laneReport.totalDispenses}")
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Error loading lane statistics", e)
                Toast.makeText(requireContext(), "Error loading statistics: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun updateLaneCard(laneNum: Int, laneStatus: com.waterfountainmachine.app.hardware.LaneInfo?) {
        if (laneStatus == null) return
        
        // Get views for this lane
        val (statusText, statsText, indicatorView) = when (laneNum) {
            1 -> Triple(binding.lane1StatusText, binding.lane1StatsText, binding.lane1Indicator)
            2 -> Triple(binding.lane2StatusText, binding.lane2StatsText, binding.lane2Indicator)
            3 -> Triple(binding.lane3StatusText, binding.lane3StatsText, binding.lane3Indicator)
            4 -> Triple(binding.lane4StatusText, binding.lane4StatsText, binding.lane4Indicator)
            5 -> Triple(binding.lane5StatusText, binding.lane5StatsText, binding.lane5Indicator)
            6 -> Triple(binding.lane6StatusText, binding.lane6StatsText, binding.lane6Indicator)
            7 -> Triple(binding.lane7StatusText, binding.lane7StatsText, binding.lane7Indicator)
            8 -> Triple(binding.lane8StatusText, binding.lane8StatsText, binding.lane8Indicator)
            else -> return
        }
        
        // Status text and indicator
        val (statusStr, indicatorColor) = when {
            laneStatus.isUsable -> "✅ ACTIVE" to android.graphics.Color.parseColor("#4CAF50")
            laneStatus.status == com.waterfountainmachine.app.hardware.LaneManager.LANE_STATUS_EMPTY -> 
                "⚠️ EMPTY" to android.graphics.Color.parseColor("#FF9800")
            laneStatus.status == com.waterfountainmachine.app.hardware.LaneManager.LANE_STATUS_FAILED -> 
                "❌ FAILED" to android.graphics.Color.parseColor("#F44336")
            else -> "⏸️ DISABLED" to android.graphics.Color.parseColor("#9E9E9E")
        }
        
        statusText.text = statusStr
        indicatorView.setBackgroundColor(indicatorColor)
        
        // Calculate failure rate
        val totalAttempts = laneStatus.successCount + laneStatus.failureCount
        val failureRate = if (totalAttempts > 0) {
            (laneStatus.failureCount.toFloat() / totalAttempts * 100).toInt()
        } else {
            0
        }
        
        // Stats text
        statsText.text = """
            Success: ${laneStatus.successCount}
            Failures: ${laneStatus.failureCount}
            Failure Rate: $failureRate%
            ${laneStatus.getStatusText()}
        """.trimIndent()
    }
    
    private fun resetLane(lane: Int) {
        lifecycleScope.launch {
            try {
                AppLog.i(TAG, "Resetting lane $lane...")
                
                val success = app.hardwareManager.resetSlot(lane)
                
                if (success) {
                    AppLog.i(TAG, "✅ Lane $lane reset successfully")
                    Toast.makeText(requireContext(), "Lane $lane reset successfully", Toast.LENGTH_SHORT).show()
                    loadLaneStatistics() // Refresh display
                } else {
                    AppLog.e(TAG, "❌ Failed to reset lane $lane")
                    Toast.makeText(requireContext(), "Failed to reset lane $lane", Toast.LENGTH_SHORT).show()
                }
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Error resetting lane $lane", e)
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun resetAllLanes() {
        lifecycleScope.launch {
            try {
                AppLog.i(TAG, "Resetting all lanes...")
                
                app.hardwareManager.resetAllLanes()
                
                AppLog.i(TAG, "✅ All lanes reset successfully")
                Toast.makeText(requireContext(), "All lanes reset successfully", Toast.LENGTH_SHORT).show()
                
                loadLaneStatistics() // Refresh display
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Error resetting all lanes", e)
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun getCurrentTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date())
    }
    
    override fun onResume() {
        super.onResume()
        loadLaneStatistics()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
