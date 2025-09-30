package com.waterfountainmachine.app.utils

import android.util.Log
import com.waterfountainmachine.app.admin.models.LogEntry
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Singleton class that collects and stores all application logs in memory.
 * This allows the admin panel to display real logs without requiring READ_LOGS permission.
 */
object LogCollector {
    
    private const val MAX_LOG_ENTRIES = 1000
    private val logQueue = ConcurrentLinkedQueue<LogEntry>()
    private var idCounter = 0
    
    /**
     * Add a log entry to the collector
     */
    fun addLog(level: LogEntry.Level, tag: String, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) {
            "$message\n${Log.getStackTraceString(throwable)}"
        } else {
            message
        }
        
        val entry = LogEntry(
            id = (idCounter++).toString(),
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = fullMessage
        )
        
        logQueue.add(entry)
        
        // Maintain max size
        while (logQueue.size > MAX_LOG_ENTRIES) {
            logQueue.poll()
        }
    }
    
    /**
     * Get all collected logs
     */
    fun getLogs(): List<LogEntry> {
        return logQueue.toList().sortedByDescending { it.timestamp }
    }
    
    /**
     * Clear all collected logs
     */
    fun clear() {
        logQueue.clear()
        idCounter = 0
    }
    
    /**
     * Get logs filtered by level
     */
    fun getLogsByLevel(level: LogEntry.Level): List<LogEntry> {
        return logQueue.filter { it.level == level }.sortedByDescending { it.timestamp }
    }
    
    /**
     * Get log count
     */
    fun getCount(): Int {
        return logQueue.size
    }
}

/**
 * Extension functions for easy logging with automatic collection
 */
object AppLog {
    
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        LogCollector.addLog(LogEntry.Level.ERROR, tag, message, throwable)
    }
    
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        LogCollector.addLog(LogEntry.Level.WARNING, tag, message, throwable)
    }
    
    fun i(tag: String, message: String) {
        Log.i(tag, message)
        LogCollector.addLog(LogEntry.Level.INFO, tag, message)
    }
    
    fun d(tag: String, message: String) {
        Log.d(tag, message)
        LogCollector.addLog(LogEntry.Level.DEBUG, tag, message)
    }
    
    fun v(tag: String, message: String) {
        Log.v(tag, message)
        LogCollector.addLog(LogEntry.Level.DEBUG, tag, message)
    }
}
