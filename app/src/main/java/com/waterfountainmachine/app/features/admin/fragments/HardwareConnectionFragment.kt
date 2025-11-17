package com.waterfountainmachine.app.admin.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.waterfountainmachine.app.R
import com.waterfountainmachine.app.databinding.FragmentConnectionTestBinding
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.utils.AdminDebugConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*

/**
 * Network Connectivity Test Fragment
 * Tests internet connection by attempting to connect to google.com
 */
class HardwareConnectionFragment : Fragment() {
    
    private var _binding: FragmentConnectionTestBinding? = null
    private val binding get() = _binding!!
    
    private var isTesting = false
    
    companion object {
        private const val TAG = "ConnectionTest"
        private const val TEST_HOST = "google.com"
        private const val TEST_PORT = 80
        private const val TIMEOUT_MS = 3000
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConnectionTestBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupUI()
    }
    
    private fun setupUI() {
        // Set target host display
        binding.targetHost.text = "$TEST_HOST:$TEST_PORT"
        
        // Setup test button
        binding.testConnectionButton.setOnClickListener {
            performConnectionTest()
        }
    }
    
    private fun performConnectionTest() {
        if (isTesting) return
        
        lifecycleScope.launch {
            try {
                isTesting = true
                binding.testConnectionButton.isEnabled = false
                
                // Update UI - Testing state
                updateUITesting()
                
                AdminDebugConfig.logAdmin(requireContext(), TAG, "Starting connection test to $TEST_HOST:$TEST_PORT")
                
                // Perform connection test
                val startTime = System.currentTimeMillis()
                val result = withContext(Dispatchers.IO) {
                    testConnection(TEST_HOST, TEST_PORT, TIMEOUT_MS)
                }
                val latency = System.currentTimeMillis() - startTime
                
                // Update UI - Show result
                if (result) {
                    updateUISuccess(latency)
                    AdminDebugConfig.logAdminInfo(requireContext(), TAG, "✅ Connection successful: ${latency}ms")
                } else {
                    updateUIFailure()
                    AdminDebugConfig.logAdminWarning(requireContext(), TAG, "❌ Connection failed")
                }
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Error during connection test", e)
                updateUIError(e.message ?: "Unknown error")
                
            } finally {
                isTesting = false
                binding.testConnectionButton.isEnabled = true
            }
        }
    }
    
    private fun updateUITesting() {
        // Status icon - emoji
        binding.statusIcon.text = "🔄"
        
        // Status text
        binding.statusText.text = "Testing..."
        binding.statusText.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.status_warning)
        )
        
        // Details
        binding.statusDetails.text = "Attempting to connect to $TEST_HOST"
        
        // Latency
        binding.latencyText.text = "—"
        
        // Update timestamp
        binding.lastTestTime.text = getCurrentTime()
    }
    
    private fun updateUISuccess(latencyMs: Long) {
        // Icon - success emoji
        binding.statusIcon.text = "✅"
        
        // Status text
        binding.statusText.text = "✓ Connected"
        binding.statusText.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.status_success)
        )
        
        // Details
        binding.statusDetails.text = "Successfully connected to $TEST_HOST"
        
        // Latency
        val latencyColor = when {
            latencyMs < 100 -> R.color.status_success
            latencyMs < 300 -> R.color.status_warning
            else -> R.color.status_error
        }
        binding.latencyText.text = "${latencyMs}ms"
        binding.latencyText.setTextColor(
            ContextCompat.getColor(requireContext(), latencyColor)
        )
        
        // Update timestamp
        binding.lastTestTime.text = getCurrentTime()
    }
    
    private fun updateUIFailure() {
        // Icon - error emoji
        binding.statusIcon.text = "❌"
        
        // Status text
        binding.statusText.text = "✗ No Connection"
        binding.statusText.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.status_error)
        )
        
        // Details
        binding.statusDetails.text = "Unable to reach $TEST_HOST. Check your internet connection."
        
        // Latency
        binding.latencyText.text = "Timeout"
        binding.latencyText.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.status_error)
        )
        
        // Update timestamp
        binding.lastTestTime.text = getCurrentTime()
    }
    
    private fun updateUIError(errorMessage: String) {
        // Icon - error emoji
        binding.statusIcon.text = "⚠️"
        
        // Status text
        binding.statusText.text = "Error"
        binding.statusText.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.status_error)
        )
        
        // Details
        binding.statusDetails.text = errorMessage
        
        // Latency
        binding.latencyText.text = "—"
        
        // Update timestamp
        binding.lastTestTime.text = getCurrentTime()
    }
    
    /**
     * Test connection by attempting to open a socket
     */
    private fun testConnection(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (e: IOException) {
            AdminDebugConfig.logAdmin(requireContext(), TAG, "Connection test failed: ${e.message}")
            false
        }
    }
    
    private fun getCurrentTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date())
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
