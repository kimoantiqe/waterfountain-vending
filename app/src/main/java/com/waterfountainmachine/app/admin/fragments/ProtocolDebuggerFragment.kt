package com.waterfountainmachine.app.admin.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.waterfountainmachine.app.R
import com.waterfountainmachine.app.WaterFountainApplication
import com.waterfountainmachine.app.databinding.FragmentProtocolDebuggerBinding
import com.waterfountainmachine.app.hardware.sdk.*
import com.waterfountainmachine.app.utils.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * Protocol Debugger Fragment
 * 
 * Advanced debugging panel for VMC protocol frames:
 * - Manual command construction
 * - Hex frame display with annotations
 * - Checksum validation and calculation
 * - Response time measurement
 * - Sent/received frame history
 * - Export trace functionality
 */
class ProtocolDebuggerFragment : Fragment() {
    
    private var _binding: FragmentProtocolDebuggerBinding? = null
    private val binding get() = _binding!!
    
    private val app: WaterFountainApplication by lazy {
        requireActivity().application as WaterFountainApplication
    }
    
    private val traceHistory = mutableListOf<TraceEntry>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    
    data class TraceEntry(
        val timestamp: Long,
        val type: String, // "SENT" or "RECEIVED"
        val frame: ProtocolFrame,
        val responseTimeMs: Long? = null
    )
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProtocolDebuggerBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupCommandDropdown()
        setupButtons()
        updateHardwareStatus()
    }
    
    private fun setupCommandDropdown() {
        val commands = listOf(
            "GET_DEVICE_ID (0x31)",
            "DELIVERY_COMMAND (0x41)",
            "QUERY_STATUS (0xE1)",
            "REMOVE_FAULT (0xA2)"
        )
        
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            commands
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.commandSpinner.adapter = adapter
        
        // Update UI based on selected command
        binding.commandSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateParametersVisibility(position)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }
    
    private fun updateParametersVisibility(commandIndex: Int) {
        when (commandIndex) {
            0 -> { // GET_DEVICE_ID - no parameters
                binding.slotInputLayout.visibility = View.GONE
                binding.quantityInputLayout.visibility = View.GONE
            }
            1 -> { // DELIVERY_COMMAND - slot and quantity
                binding.slotInputLayout.visibility = View.VISIBLE
                binding.quantityInputLayout.visibility = View.VISIBLE
            }
            2 -> { // QUERY_STATUS - slot and quantity
                binding.slotInputLayout.visibility = View.VISIBLE
                binding.quantityInputLayout.visibility = View.VISIBLE
            }
            3 -> { // REMOVE_FAULT - no parameters
                binding.slotInputLayout.visibility = View.GONE
                binding.quantityInputLayout.visibility = View.GONE
            }
        }
    }
    
    private fun setupButtons() {
        binding.buildFrameButton.setOnClickListener {
            buildAndDisplayFrame()
        }
        
        binding.sendCommandButton.setOnClickListener {
            sendCommand()
        }
        
        binding.clearHistoryButton.setOnClickListener {
            clearHistory()
        }
        
        binding.exportTraceButton.setOnClickListener {
            exportTrace()
        }
        
        binding.validateChecksumButton.setOnClickListener {
            validateChecksum()
        }
    }
    
    private fun buildAndDisplayFrame() {
        try {
            val commandIndex = binding.commandSpinner.selectedItemPosition
            val frame = when (commandIndex) {
                0 -> VmcCommandBuilder.getDeviceId()
                1 -> {
                    val slot = binding.slotInput.text.toString().toIntOrNull()?.toByte() ?: 1
                    val quantity = binding.quantityInput.text.toString().toIntOrNull()?.toByte() ?: 1
                    VmcCommandBuilder.deliveryCommand(slot, quantity)
                }
                2 -> {
                    val slot = binding.slotInput.text.toString().toIntOrNull()?.toByte() ?: 1
                    val quantity = binding.quantityInput.text.toString().toIntOrNull()?.toByte() ?: 1
                    VmcCommandBuilder.queryStatus(slot, quantity)
                }
                3 -> VmcCommandBuilder.removeFault()
                else -> return
            }
            
            displayFrame(frame, "BUILT")
            binding.sendCommandButton.isEnabled = true
            
        } catch (e: Exception) {
            appendToLog("❌ Error building frame: ${e.message}")
        }
    }
    
    private fun displayFrame(frame: ProtocolFrame, type: String) {
        val frameBytes = frame.toByteArray()
        val hexString = frameBytes.joinToString(" ") { byte ->
            "%02X".format(byte)
        }
        
        // Annotated hex display
        val annotated = buildString {
            appendLine("═══════════════════════════════════════════")
            appendLine("$type FRAME")
            appendLine("═══════════════════════════════════════════")
            appendLine()
            
            // Address
            appendLine("ADDR:   ${"%02X".format(frame.address)} (Fixed: 0xFF)")
            
            // Frame Number
            appendLine("FRAME#: ${"%02X".format(frame.frameNumber)} (Fixed: 0x00)")
            
            // Header
            val headerType = if (frame.header == ProtocolFrame.APP_HEADER) "APP" else "VMC"
            appendLine("HEADER: ${"%02X".format(frame.header)} ($headerType)")
            
            // Command
            val cmdName = getCommandName(frame.command)
            appendLine("CMD:    ${"%02X".format(frame.command)} ($cmdName)")
            
            // Data Length
            appendLine("LEN:    ${"%02X".format(frame.dataLength)} (${frame.dataLength.toInt() and 0xFF} bytes)")
            
            // Data
            if (frame.data.isNotEmpty()) {
                val dataHex = frame.data.joinToString(" ") { "%02X".format(it) }
                appendLine("DATA:   $dataHex")
            } else {
                appendLine("DATA:   (none)")
            }
            
            // Checksum
            val calculatedChecksum = frame.calculateChecksum()
            val isValid = frame.isChecksumValid()
            val validSymbol = if (isValid) "✅" else "❌"
            appendLine("CHK:    ${"%02X".format(frame.checksum)} $validSymbol")
            appendLine("        Calculated: ${"%02X".format(calculatedChecksum)}")
            
            appendLine()
            appendLine("FULL FRAME (${frameBytes.size} bytes):")
            appendLine(hexString)
            appendLine("═══════════════════════════════════════════")
        }
        
        binding.hexDisplay.text = annotated
        
        // Update checksum calculation display
        displayChecksumCalculation(frame)
    }
    
    private fun displayChecksumCalculation(frame: ProtocolFrame) {
        val calculation = buildString {
            appendLine("Checksum Calculation:")
            appendLine("─────────────────────")
            
            var sum = 0
            
            // Header
            val headerVal = frame.header.toInt() and 0xFF
            sum += headerVal
            appendLine("HEADER:  0x${"%02X".format(frame.header)} = $headerVal")
            
            // Command
            val cmdVal = frame.command.toInt() and 0xFF
            sum += cmdVal
            appendLine("CMD:     0x${"%02X".format(frame.command)} = $cmdVal")
            
            // Data Length
            val lenVal = frame.dataLength.toInt() and 0xFF
            sum += lenVal
            appendLine("LEN:     0x${"%02X".format(frame.dataLength)} = $lenVal")
            
            // Data bytes
            if (frame.data.isNotEmpty()) {
                frame.data.forEachIndexed { index, byte ->
                    val byteVal = byte.toInt() and 0xFF
                    sum += byteVal
                    appendLine("DATA[$index]: 0x${"%02X".format(byte)} = $byteVal")
                }
            }
            
            appendLine("─────────────────────")
            appendLine("SUM:     $sum (0x${"%02X".format(sum)})")
            appendLine("RESULT:  ${sum and 0xFF} (0x${"%02X".format(sum and 0xFF)})")
            appendLine()
            
            val isValid = frame.isChecksumValid()
            if (isValid) {
                appendLine("✅ Checksum VALID")
            } else {
                appendLine("❌ Checksum INVALID")
                appendLine("Expected: 0x${"%02X".format(frame.calculateChecksum())}")
                appendLine("Actual:   0x${"%02X".format(frame.checksum)}")
            }
        }
        
        binding.checksumCalculation.text = calculation
    }
    
    private fun getCommandName(command: Byte): String {
        return when (command) {
            VmcCommands.GET_DEVICE_ID -> "GET_DEVICE_ID"
            VmcCommands.DELIVERY_COMMAND -> "DELIVERY_COMMAND"
            VmcCommands.QUERY_STATUS -> "QUERY_STATUS"
            VmcCommands.REMOVE_FAULT -> "REMOVE_FAULT"
            else -> "UNKNOWN"
        }
    }
    
    private fun sendCommand() {
        if (!app.isHardwareReady()) {
            appendToLog("❌ Hardware not ready. Initialize hardware first.")
            return
        }
        
        binding.sendCommandButton.isEnabled = false
        binding.buildFrameButton.isEnabled = false
        
        lifecycleScope.launch {
            try {
                val startTime = System.currentTimeMillis()
                
                val commandIndex = binding.commandSpinner.selectedItemPosition
                val result = when (commandIndex) {
                    0 -> app.hardwareManager.getDeviceId()
                    1 -> {
                        val slot = binding.slotInput.text.toString().toIntOrNull() ?: 1
                        val quantity = binding.quantityInput.text.toString().toIntOrNull() ?: 1
                        app.hardwareManager.dispenseWater(slot, quantity)
                        "Dispense command sent"
                    }
                    2 -> {
                        // Query status
                        app.hardwareManager.queryVmcStatus()
                        "Status query sent"
                    }
                    3 -> {
                        app.hardwareManager.clearFaults()
                        "Clear faults sent"
                    }
                    else -> "Unknown command"
                }
                
                val responseTime = System.currentTimeMillis() - startTime
                
                withContext(Dispatchers.Main) {
                    appendToLog("✅ Command sent successfully")
                    appendToLog("⏱️  Response time: ${responseTime}ms")
                    appendToLog("📥 Response: $result")
                    appendToLog("─────────────────────────────────────────")
                }
                
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendToLog("❌ Error sending command: ${e.message}")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.sendCommandButton.isEnabled = true
                    binding.buildFrameButton.isEnabled = true
                }
            }
        }
    }
    
    private fun validateChecksum() {
        // Re-display current frame with checksum validation
        try {
            val commandIndex = binding.commandSpinner.selectedItemPosition
            val frame = when (commandIndex) {
                0 -> VmcCommandBuilder.getDeviceId()
                1 -> {
                    val slot = binding.slotInput.text.toString().toIntOrNull()?.toByte() ?: 1
                    val quantity = binding.quantityInput.text.toString().toIntOrNull()?.toByte() ?: 1
                    VmcCommandBuilder.deliveryCommand(slot, quantity)
                }
                2 -> {
                    val slot = binding.slotInput.text.toString().toIntOrNull()?.toByte() ?: 1
                    val quantity = binding.quantityInput.text.toString().toIntOrNull()?.toByte() ?: 1
                    VmcCommandBuilder.queryStatus(slot, quantity)
                }
                3 -> VmcCommandBuilder.removeFault()
                else -> return
            }
            
            displayChecksumCalculation(frame)
            
        } catch (e: Exception) {
            appendToLog("❌ Error validating checksum: ${e.message}")
        }
    }
    
    private fun clearHistory() {
        traceHistory.clear()
        binding.traceHistoryLog.text = "History cleared."
        binding.hexDisplay.text = ""
        binding.checksumCalculation.text = ""
    }
    
    private fun exportTrace() {
        if (traceHistory.isEmpty()) {
            appendToLog("📋 No trace history to export")
            return
        }
        
        val trace = buildString {
            appendLine("═══════════════════════════════════════════")
            appendLine("PROTOCOL TRACE EXPORT")
            appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine("Total Entries: ${traceHistory.size}")
            appendLine("═══════════════════════════════════════════")
            appendLine()
            
            traceHistory.forEach { entry ->
                appendLine("─────────────────────────────────────────")
                appendLine("${entry.type} at ${dateFormat.format(Date(entry.timestamp))}")
                if (entry.responseTimeMs != null) {
                    appendLine("Response Time: ${entry.responseTimeMs}ms")
                }
                
                val frameBytes = entry.frame.toByteArray()
                val hex = frameBytes.joinToString(" ") { "%02X".format(it) }
                appendLine("Frame: $hex")
                appendLine("Command: ${getCommandName(entry.frame.command)}")
                appendLine()
            }
        }
        
        // For now, just log it. In a real app, you'd save to file or share
        AppLog.i("ProtocolDebugger", trace)
        appendToLog("📋 Trace exported to log (${traceHistory.size} entries)")
    }
    
    private fun appendToLog(message: String) {
        val timestamp = dateFormat.format(Date())
        val currentText = binding.traceHistoryLog.text.toString()
        val newText = if (currentText.isEmpty()) {
            "[$timestamp] $message"
        } else {
            "$currentText\n[$timestamp] $message"
        }
        binding.traceHistoryLog.text = newText
        
        // Auto-scroll to bottom
        binding.traceHistoryScrollView.post {
            binding.traceHistoryScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }
    
    private fun updateHardwareStatus() {
        val isReady = app.isHardwareReady()
        val statusText = if (isReady) "✅ READY" else "❌ NOT READY"
        val statusColor = if (isReady) R.color.status_success else R.color.status_error
        
        binding.hardwareStatusText.text = statusText
        binding.hardwareStatusIndicator.setColorFilter(
            ContextCompat.getColor(requireContext(), statusColor)
        )
        
        binding.sendCommandButton.isEnabled = isReady
    }
    
    override fun onResume() {
        super.onResume()
        updateHardwareStatus()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
