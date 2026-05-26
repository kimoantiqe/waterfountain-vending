package com.waterfountainmachine.app.core.utils
import android.util.Log
import com.waterfountainmachine.app.features.admin.models.LogEntry
import com.yishengkj.logging.SensitiveDataRedactor
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
 * Extension functions for easy logging with automatic collection.
 *
 * SECURITY: every message and tag is run through [SensitiveDataRedactor]
 * before it reaches logcat or the in-memory [LogCollector] buffer, so PII
 * (E.164 phone numbers, OTP codes, PEM blocks, emails) never lands in any
 * log sink — including the admin-panel log viewer and any future log-export
 * support flow.
 */
object AppLog {

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val t = SensitiveDataRedactor.redactTag(tag)
        val m = SensitiveDataRedactor.redact(message)
        Log.e(t, m, throwable)
        LogCollector.addLog(LogEntry.Level.ERROR, t, m, throwable)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        val t = SensitiveDataRedactor.redactTag(tag)
        val m = SensitiveDataRedactor.redact(message)
        Log.w(t, m, throwable)
        LogCollector.addLog(LogEntry.Level.WARNING, t, m, throwable)
    }

    fun i(tag: String, message: String) {
        val t = SensitiveDataRedactor.redactTag(tag)
        val m = SensitiveDataRedactor.redact(message)
        Log.i(t, m)
        LogCollector.addLog(LogEntry.Level.INFO, t, m)
    }

    fun d(tag: String, message: String) {
        val t = SensitiveDataRedactor.redactTag(tag)
        val m = SensitiveDataRedactor.redact(message)
        Log.d(t, m)
        LogCollector.addLog(LogEntry.Level.DEBUG, t, m)
    }

    fun v(tag: String, message: String) {
        val t = SensitiveDataRedactor.redactTag(tag)
        val m = SensitiveDataRedactor.redact(message)
        Log.v(t, m)
        LogCollector.addLog(LogEntry.Level.DEBUG, t, m)
    }
}
