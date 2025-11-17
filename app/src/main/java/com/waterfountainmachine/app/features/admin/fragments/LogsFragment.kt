package com.waterfountainmachine.app.admin.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.waterfountainmachine.app.databinding.FragmentLogsBinding
import com.waterfountainmachine.app.admin.adapters.LogEntryAdapter
import com.waterfountainmachine.app.admin.models.LogEntry
import com.waterfountainmachine.app.utils.LogCollector
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.utils.AdminDebugConfig
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class LogsFragment : Fragment() {
    
    private var _binding: FragmentLogsBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var logsAdapter: LogEntryAdapter
    private val allLogEntries = mutableListOf<LogEntry>()
    private var currentFilter: LogEntry.Level? = null
    private var isRealTimeEnabled = false
    private var realTimeJob: kotlinx.coroutines.Job? = null
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogsBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupRecyclerView()
        setupControls()
        loadLogs()
    }
    
    private fun setupRecyclerView() {
        logsAdapter = LogEntryAdapter()
        binding.logsRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = logsAdapter
        }
    }
    
    private fun setupControls() {
        // Log level filters
        binding.filterAllButton.setOnClickListener {
            currentFilter = null
            updateFilterButtons()
            filterLogs()
        }
        
        binding.filterErrorButton.setOnClickListener {
            currentFilter = LogEntry.Level.ERROR
            updateFilterButtons()
            filterLogs()
        }
        
        binding.filterWarningButton.setOnClickListener {
            currentFilter = LogEntry.Level.WARNING
            updateFilterButtons()
            filterLogs()
        }
        
        binding.filterInfoButton.setOnClickListener {
            currentFilter = LogEntry.Level.INFO
            updateFilterButtons()
            filterLogs()
        }
        
        binding.filterDebugButton.setOnClickListener {
            currentFilter = LogEntry.Level.DEBUG
            updateFilterButtons()
            filterLogs()
        }
        
        // Log actions
        binding.refreshLogsButton.setOnClickListener {
            refreshLogs()
        }
        
        binding.clearLogsButton.setOnClickListener {
            clearLogs()
        }
        
        binding.exportLogsButton.setOnClickListener {
            exportLogs()
        }
        
        // Real-time toggle
        binding.realTimeToggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startRealTimeUpdates()
            } else {
                stopRealTimeUpdates()
            }
        }
    }
    
    private fun updateFilterButtons() {
        // Reset all buttons
        binding.filterAllButton.alpha = 0.6f
        binding.filterErrorButton.alpha = 0.6f
        binding.filterWarningButton.alpha = 0.6f
        binding.filterInfoButton.alpha = 0.6f
        binding.filterDebugButton.alpha = 0.6f
        
        // Highlight selected button
        when (currentFilter) {
            null -> binding.filterAllButton.alpha = 1.0f
            LogEntry.Level.ERROR -> binding.filterErrorButton.alpha = 1.0f
            LogEntry.Level.WARNING -> binding.filterWarningButton.alpha = 1.0f
            LogEntry.Level.INFO -> binding.filterInfoButton.alpha = 1.0f
            LogEntry.Level.DEBUG -> binding.filterDebugButton.alpha = 1.0f
        }
    }
    
    private fun loadLogs() {
        binding.logStatusText.text = "Loading logs..."
        
        lifecycleScope.launch {
            try {
                val logs = LogCollector.getLogs()
                allLogEntries.clear()
                allLogEntries.addAll(logs)
                
                filterLogs()
                binding.logStatusText.text = if (logs.isEmpty()) {
                    "No logs available yet"
                } else {
                    "Loaded ${logs.size} log entries"
                }
                
            } catch (e: Exception) {
                binding.logStatusText.text = "Error loading logs: ${e.message}"
                AppLog.e("LogsFragment", "Error loading logs", e)
            }
        }
    }
    
    private fun refreshLogs() {
        loadLogs()
    }
    
    private fun clearLogs() {
        lifecycleScope.launch {
            try {
                LogCollector.clear()
                allLogEntries.clear()
                logsAdapter.submitList(emptyList())
                binding.logStatusText.text = "Logs cleared"
                binding.logCountText.text = "0 entries"
                AdminDebugConfig.logAdminInfo(requireContext(), "LogsFragment", "All logs cleared")
                
            } catch (e: Exception) {
                binding.logStatusText.text = "Error clearing logs: ${e.message}"
                AppLog.e("LogsFragment", "Error clearing logs", e)
            }
        }
    }
    
    private fun exportLogs() {
        lifecycleScope.launch {
            try {
                val file = createLogFile()
                
                if (file != null) {
                    shareLogFile(file)
                    AdminDebugConfig.logAdminInfo(requireContext(), "LogsFragment", "Logs exported successfully")
                } else {
                    AdminDebugConfig.logAdminWarning(requireContext(), "LogsFragment", "Failed to export logs")
                }
                
            } catch (e: Exception) {
                binding.logStatusText.text = "Export error: ${e.message}"
                AppLog.e("LogsFragment", "Export error", e)
            }
        }
    }
    
    private fun filterLogs() {
        val filteredLogs = if (currentFilter == null) {
            allLogEntries
        } else {
            allLogEntries.filter { it.level == currentFilter }
        }
        
        logsAdapter.submitList(filteredLogs)
        binding.logCountText.text = "${filteredLogs.size} entries"
    }
    
    private fun startRealTimeUpdates() {
        isRealTimeEnabled = true
        binding.logStatusText.text = "Real-time monitoring enabled"
        
        realTimeJob = lifecycleScope.launch {
            while (isRealTimeEnabled) {
                try {
                    val logs = LogCollector.getLogs()
                    allLogEntries.clear()
                    allLogEntries.addAll(logs)
                    filterLogs()
                } catch (e: Exception) {
                    // Silently continue
                }
                delay(2000) // Update every 2 seconds
            }
        }
    }
    
    private fun stopRealTimeUpdates() {
        isRealTimeEnabled = false
        realTimeJob?.cancel()
        realTimeJob = null
        binding.logStatusText.text = "Real-time monitoring disabled"
    }
    

    
    private suspend fun createLogFile(): File? {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "waterfountain_logs_$timestamp.txt"
            val file = File(requireContext().cacheDir, fileName)
            
            FileWriter(file).use { writer ->
                writer.write("Water Fountain Vending Machine - Log Export\n")
                writer.write("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
                writer.write("Total Entries: ${allLogEntries.size}\n\n")
                
                allLogEntries.forEach { entry ->
                    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp))
                    writer.write("[$timestamp] [${entry.level.name}] [${entry.tag}] ${entry.message}\n")
                }
            }
            
            file
        } catch (e: Exception) {
            null
        }
    }
    
    private fun shareLogFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Water Fountain Logs")
                putExtra(Intent.EXTRA_TEXT, "Water fountain vending machine log export")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            
            startActivity(Intent.createChooser(intent, "Export Logs"))
            
        } catch (e: Exception) {
            AppLog.e("LogsFragment", "Error sharing log file", e)
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        stopRealTimeUpdates()
        _binding = null
    }
}
