package com.waterfountainmachine.app.admin.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.waterfountainmachine.app.R
import com.waterfountainmachine.app.WaterFountainApplication
import com.waterfountainmachine.app.databinding.FragmentHardwareConnectionBinding
import com.waterfountainmachine.app.hardware.sdk.UsbSerialCommunicator
import com.waterfountainmachine.app.utils.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hardware Connection Panel
 * Shows live connection status, USB devices, chipset info
 */
class HardwareConnectionFragment : Fragment() {
    
    private var _binding: FragmentHardwareConnectionBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var app: WaterFountainApplication
    private var isAutoRefreshEnabled = false
    
    companion object {
        private const val TAG = "HardwareConnectionFrag"
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHardwareConnectionBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        app = requireActivity().application as WaterFountainApplication
        
        setupUI()
        loadConnectionStatus()
        
        // Start auto-refresh
        startAutoRefresh()
    }
    
    private fun setupUI() {
        // Refresh button
        binding.refreshConnectionButton.setOnClickListener {
            loadConnectionStatus()
        }
        
        // Initialize hardware button
        binding.initializeHardwareButton.setOnClickListener {
            initializeHardware()
        }
        
        // Reconnect button
        binding.reconnectHardwareButton.setOnClickListener {
            reconnectHardware()
        }
        
        // Disconnect button
        binding.disconnectHardwareButton.setOnClickListener {
            disconnectHardware()
        }
        
        // Auto-refresh toggle
        binding.autoRefreshToggle.setOnCheckedChangeListener { _, isChecked ->
            isAutoRefreshEnabled = isChecked
            if (isChecked) {
                startAutoRefresh()
            }
        }
    }
    
    private fun loadConnectionStatus() {
        lifecycleScope.launch {
            try {
                AppLog.d(TAG, "Loading connection status...")
                
                // Hardware state
                val state = app.hardwareState
                val stateText = app.getHardwareStateDescription()
                val stateColor = when (state) {
                    WaterFountainApplication.HardwareState.READY -> R.color.status_success
                    WaterFountainApplication.HardwareState.INITIALIZING -> R.color.status_warning
                    WaterFountainApplication.HardwareState.ERROR -> R.color.status_error
                    else -> R.color.status_inactive
                }
                
                binding.hardwareStateText.text = stateText
                binding.hardwareStateIndicator.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), stateColor)
                )
                
                // Hardware mode (mock vs real)
                val prefs = requireContext().getSharedPreferences("system_settings", Context.MODE_PRIVATE)
                val useRealSerial = prefs.getBoolean("use_real_serial", false)
                
                binding.hardwareModeText.text = if (useRealSerial) "LIVE HARDWARE (USB)" else "MOCK (Testing)"
                binding.hardwareModeIndicator.setBackgroundColor(
                    ContextCompat.getColor(
                        requireContext(),
                        if (useRealSerial) R.color.status_success else R.color.status_warning
                    )
                )
                
                // Hardware ready status
                val isReady = app.isHardwareReady()
                binding.hardwareReadyText.text = if (isReady) "Ready" else "Not Ready"
                binding.hardwareReadyIndicator.setBackgroundColor(
                    ContextCompat.getColor(
                        requireContext(),
                        if (isReady) R.color.status_success else R.color.status_error
                    )
                )
                
                // USB device info (only for real hardware)
                if (useRealSerial && isReady) {
                    loadUsbDeviceInfo()
                } else {
                    binding.usbDeviceInfoCard.visibility = View.GONE
                }
                
                // Connection time
                binding.connectionTimeText.text = "Updated: ${getCurrentTime()}"
                
                AppLog.d(TAG, "Connection status loaded: State=$stateText, Mode=${if (useRealSerial) "LIVE" else "MOCK"}, Ready=$isReady")
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Error loading connection status", e)
                binding.hardwareStateText.text = "Error: ${e.message}"
            }
        }
    }
    
    private fun loadUsbDeviceInfo() {
        try {
            // Try to get USB device info from UsbSerialCommunicator
            val communicator = UsbSerialCommunicator(requireContext())
            val devices = communicator.getAvailableDevices()
            
            if (devices.isNotEmpty()) {
                val device = devices[0]
                
                binding.usbDeviceInfoCard.visibility = View.VISIBLE
                binding.usbDeviceNameText.text = device.productName ?: "Unknown Device"
                binding.usbManufacturerText.text = device.manufacturerName ?: "Unknown"
                binding.usbVidPidText.text = "VID: 0x${String.format("%04X", device.vendorId)} / PID: 0x${String.format("%04X", device.productId)}"
                binding.usbSerialText.text = device.serialNumber ?: "N/A"
                
                // Chipset detection would require driver inspection
                binding.usbChipsetText.text = "Detecting..."
                
            } else {
                binding.usbDeviceInfoCard.visibility = View.GONE
            }
            
        } catch (e: Exception) {
            AppLog.e(TAG, "Error loading USB device info", e)
            binding.usbDeviceInfoCard.visibility = View.GONE
        }
    }
    
    private fun initializeHardware() {
        lifecycleScope.launch {
            try {
                binding.initializeHardwareButton.isEnabled = false
                binding.hardwareStateText.text = "Initializing..."
                
                AppLog.i(TAG, "User requested hardware initialization")
                
                app.initializeHardware { success ->
                    lifecycleScope.launch {
                        if (success) {
                            AppLog.i(TAG, "✅ Hardware initialized successfully")
                            android.widget.Toast.makeText(
                                requireContext(),
                                "Hardware initialized successfully",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            AppLog.e(TAG, "❌ Hardware initialization failed")
                            android.widget.Toast.makeText(
                                requireContext(),
                                "Hardware initialization failed. Check logs.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        
                        delay(500)
                        loadConnectionStatus()
                        binding.initializeHardwareButton.isEnabled = true
                    }
                }
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Error during initialization", e)
                binding.initializeHardwareButton.isEnabled = true
                loadConnectionStatus()
            }
        }
    }
    
    private fun reconnectHardware() {
        lifecycleScope.launch {
            try {
                binding.reconnectHardwareButton.isEnabled = false
                binding.hardwareStateText.text = "Reconnecting..."
                
                AppLog.i(TAG, "User requested hardware reconnect")
                
                app.reinitializeHardware { success ->
                    lifecycleScope.launch {
                        if (success) {
                            AppLog.i(TAG, "✅ Reconnect successful")
                            android.widget.Toast.makeText(
                                requireContext(),
                                "Reconnected successfully",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            AppLog.e(TAG, "❌ Reconnect failed")
                            android.widget.Toast.makeText(
                                requireContext(),
                                "Reconnect failed. Check logs.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        
                        delay(500)
                        loadConnectionStatus()
                        binding.reconnectHardwareButton.isEnabled = true
                    }
                }
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Error during reconnect", e)
                binding.reconnectHardwareButton.isEnabled = true
                loadConnectionStatus()
            }
        }
    }
    
    private fun disconnectHardware() {
        lifecycleScope.launch {
            try {
                binding.disconnectHardwareButton.isEnabled = false
                binding.hardwareStateText.text = "Disconnecting..."
                
                AppLog.i(TAG, "User requested hardware disconnect")
                
                app.shutdownHardware()
                
                delay(500)
                loadConnectionStatus()
                
                android.widget.Toast.makeText(
                    requireContext(),
                    "Hardware disconnected",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                
                binding.disconnectHardwareButton.isEnabled = true
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Error during disconnect", e)
                binding.disconnectHardwareButton.isEnabled = true
                loadConnectionStatus()
            }
        }
    }
    
    private fun startAutoRefresh() {
        if (!isAutoRefreshEnabled) return
        
        lifecycleScope.launch {
            while (isAutoRefreshEnabled && isAdded) {
                delay(2000) // Refresh every 2 seconds
                loadConnectionStatus()
            }
        }
    }
    
    private fun getCurrentTime(): String {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date())
    }
    
    override fun onResume() {
        super.onResume()
        loadConnectionStatus()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        isAutoRefreshEnabled = false
        _binding = null
    }
}
