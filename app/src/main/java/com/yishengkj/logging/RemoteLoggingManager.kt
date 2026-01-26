package com.yishengkj.logging

import android.content.Context
import android.content.SharedPreferences
import androidx.work.*
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import com.waterfountainmachine.app.admin.models.LogEntry
import com.waterfountainmachine.app.security.SecurityModule
import com.waterfountainmachine.app.utils.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * RemoteLoggingManager - Manages remote log upload to Firebase.
 * 
 * Features:
 * - 2-hour periodic uploads via WorkManager
 * - 500-entry batch size with automatic splitting
 * - Encrypted Room database queue with 48-hour retention
 * - Size logging for monitoring
 * - Retry logic for failed uploads
 */
class RemoteLoggingManager private constructor(private val context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val functions: FirebaseFunctions = Firebase.functions
    
    companion object {
        private const val TAG = "RemoteLoggingManager"
        private const val PREFS_NAME = "remote_logging"
        private const val KEY_ENABLED = "remote_logging_enabled"
        private const val KEY_MACHINE_ID = "machine_id"
        private const val BATCH_SIZE = 500
        private const val MAX_BATCH_AGE_MS = 48 * 60 * 60 * 1000L // 48 hours
        private const val UPLOAD_WORK_NAME = "log_upload_work"
        private const val UPLOAD_INTERVAL_HOURS = 2L
        
        @Volatile
        private var instance: RemoteLoggingManager? = null
        
        fun getInstance(context: Context): RemoteLoggingManager {
            return instance ?: synchronized(this) {
                instance ?: RemoteLoggingManager(context.applicationContext).also { 
                    instance = it 
                }
            }
        }
    }
    
    /**
     * Initialize remote logging with machine ID and custom passphrase
     */
    fun initialize(machineId: String, customPassphrase: String? = null) {
        prefs.edit().putString(KEY_MACHINE_ID, machineId).apply()
        
        // Initialize database with custom passphrase if provided
        if (customPassphrase != null) {
            LogQueueDatabase.resetWithNewPassphrase(context, customPassphrase)
        } else {
            LogQueueDatabase.getInstance(context)
        }
        
        AppLog.i(TAG, "RemoteLoggingManager initialized for machine: $machineId")
    }
    
    /**
     * Enable remote logging and schedule periodic uploads
     */
    fun enable() {
        prefs.edit().putBoolean(KEY_ENABLED, true).apply()
        schedulePeriodicUpload()
        AppLog.i(TAG, "Remote logging enabled")
    }
    
    /**
     * Disable remote logging and cancel scheduled uploads
     */
    fun disable() {
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        cancelPeriodicUpload()
        AppLog.i(TAG, "Remote logging disabled")
    }
    
    /**
     * Check if remote logging is enabled
     */
    fun isEnabled(): Boolean {
        return prefs.getBoolean(KEY_ENABLED, false)
    }
    
    /**
     * Queue a log entry for remote upload
     */
    suspend fun queueLog(logEntry: LogEntry) {
        if (!isEnabled()) return
        
        // Note: Actual batching and storage happens in the upload worker
        // For now, logs are collected in LogCollector and processed during upload cycle
    }
    
    /**
     * Schedule periodic upload work with WorkManager
     */
    private fun schedulePeriodicUpload() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        
        val uploadRequest = PeriodicWorkRequestBuilder<LogUploadWorker>(
            UPLOAD_INTERVAL_HOURS,
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.MINUTES
            )
            .build()
        
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UPLOAD_WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            uploadRequest
        )
        
        AppLog.i(TAG, "Scheduled periodic log upload every $UPLOAD_INTERVAL_HOURS hours")
    }
    
    /**
     * Cancel periodic upload work
     */
    private fun cancelPeriodicUpload() {
        WorkManager.getInstance(context).cancelUniqueWork(UPLOAD_WORK_NAME)
        AppLog.i(TAG, "Cancelled periodic log upload")
    }
    
    /**
     * Trigger immediate upload (used for manual uploads or testing)
     */
    fun triggerImmediateUpload() {
        val uploadRequest = OneTimeWorkRequestBuilder<LogUploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        
        WorkManager.getInstance(context).enqueue(uploadRequest)
        AppLog.i(TAG, "Triggered immediate log upload")
    }
    
    /**
     * Update encryption passphrase and clear old database
     */
    fun updatePassphrase(newPassphrase: String) {
        LogQueueDatabase.resetWithNewPassphrase(context, newPassphrase)
        AppLog.i(TAG, "Updated log encryption passphrase and cleared queue")
    }
}

/**
 * LogUploadWorker - WorkManager worker that processes and uploads log batches
 */
class LogUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    private val functions: FirebaseFunctions = Firebase.functions
    private val database = LogQueueDatabase.getInstance(context)
    private val dao = database.logUploadQueueDao()
    
    companion object {
        private const val TAG = "LogUploadWorker"
        private const val BATCH_SIZE = 500
        private const val MAX_BATCH_AGE_MS = 48 * 60 * 60 * 1000L
    }
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            AppLog.i(TAG, "Starting log upload job")
            
            // Check if machine is enrolled first
            if (!SecurityModule.isEnrolled()) {
                AppLog.w(TAG, "Cannot upload: Machine not enrolled")
                return@withContext Result.failure()
            }
            
            // Get machine ID
            val prefs = applicationContext.getSharedPreferences("remote_logging", Context.MODE_PRIVATE)
            val machineId = prefs.getString("machine_id", null)
            if (machineId == null) {
                AppLog.w(TAG, "Cannot upload: Machine ID not set")
                return@withContext Result.failure()
            }
            
            // Fetch encryption passphrase from backend
            val passphrase = try {
                fetchEncryptionPassphrase(machineId)
            } catch (e: Exception) {
                AppLog.e(TAG, "❌ Failed to fetch encryption passphrase: ${e.message}", e)
                // Store error in failed batch for visibility in admin panel
                val errorBatch = LogUploadQueue(
                    compressedLogs = "",
                    uncompressedSize = 0,
                    compressedSize = 0,
                    logCount = 0,
                    uploadAttempts = 1,
                    status = "failed",
                    lastError = "Passphrase fetch failed: ${e.message}",
                    lastAttemptAt = System.currentTimeMillis()
                )
                dao.insert(errorBatch)
                return@withContext Result.failure()
            }
            
            AppLog.i(TAG, "Encryption passphrase fetched successfully")
            
            // Get logs from LogCollector
            val allLogs = com.waterfountainmachine.app.utils.LogCollector.getLogs()
            if (allLogs.isEmpty()) {
                AppLog.i(TAG, "No logs to upload")
                return@withContext Result.success()
            }
            
            AppLog.i(TAG, "Found ${allLogs.size} logs to process")
            
            // Apply redaction and batch into 500-entry chunks
            val batches = allLogs.chunked(BATCH_SIZE)
            val totalBatches = batches.size
            
            batches.forEachIndexed { index, batch ->
                val batchNum = index + 1
                
                // Redact sensitive data
                val redactedBatch = batch.map { log ->
                    log.copy(
                        tag = SensitiveDataRedactor.redactTag(log.tag),
                        message = SensitiveDataRedactor.redact(log.message)
                    )
                }
                
                // Convert to JSON
                val jsonArray = JSONArray()
                redactedBatch.forEach { log ->
                    val jsonLog = JSONObject().apply {
                        put("id", log.id)
                        put("timestamp", log.timestamp)
                        put("level", log.level.name)
                        put("tag", log.tag)
                        put("message", log.message)
                    }
                    jsonArray.put(jsonLog)
                }
                
                val jsonString = jsonArray.toString()
                val uncompressedSize = jsonString.toByteArray(Charsets.UTF_8).size
                
                // Compress
                val compressed = LogCompressor.compress(jsonString)
                val compressedSize = LogCompressor.getCompressedSize(compressed)
                val compressedKB = LogCompressor.getCompressedSizeKB(compressed)
                val uncompressedKB = (uncompressedSize / 1024.0 * 100).toInt() / 100.0
                
                AppLog.i(TAG, "Uploading batch $batchNum/$totalBatches: ${compressedKB}KB compressed, ${uncompressedKB}KB uncompressed, ${batch.size} entries")
                
                // Upload to Firebase
                val success = uploadBatch(compressed, uncompressedSize, compressedSize, batch.size)
                
                if (!success) {
                    // Store failed batch in database for retry
                    val queueEntry = LogUploadQueue(
                        compressedLogs = compressed,
                        uncompressedSize = uncompressedSize,
                        compressedSize = compressedSize,
                        logCount = batch.size,
                        uploadAttempts = 1,
                        status = "failed",
                        lastError = "Upload failed",
                        lastAttemptAt = System.currentTimeMillis()
                    )
                    dao.insert(queueEntry)
                    AppLog.w(TAG, "Batch $batchNum/$totalBatches failed, queued for retry")
                }
            }
            
            // Retry failed batches
            retryFailedBatches()
            
            // Clean up old failed batches (>48 hours)
            cleanupOldBatches()
            
            AppLog.i(TAG, "Log upload job completed")
            Result.success()
            
        } catch (e: Exception) {
            AppLog.e(TAG, "Log upload job failed", e)
            Result.retry()
        }
    }
    
    private suspend fun fetchEncryptionPassphrase(machineId: String): String {
        val payload = JSONObject().apply {
            put("machineId", machineId)
        }
        
        // Add certificate authentication
        val authenticatedPayload = SecurityModule.createAuthenticatedRequest("getLogEncryptionKey", payload)
        
        // Call Firebase Function
        val result = functions
            .getHttpsCallable("getLogEncryptionKey")
            .call(authenticatedPayload.toMap())
            .await()
        
        val data = result.data as? Map<*, *>
            ?: throw Exception("Invalid response from getLogEncryptionKey")
        
        val passphrase = data["passphrase"] as? String
        val isDefault = data["isDefault"] as? Boolean ?: true
        
        if (passphrase == null && !isDefault) {
            throw Exception("No passphrase configured for this machine")
        }
        
        // Return passphrase or null (which will use default in database)
        return passphrase ?: LogQueueDatabase.DEFAULT_PASSPHRASE
    }
    
    private suspend fun uploadBatch(
        compressed: String,
        uncompressedSize: Int,
        compressedSize: Int,
        logCount: Int
    ): Boolean {
        return try {
            // Check if machine is enrolled
            if (!SecurityModule.isEnrolled()) {
                AppLog.w(TAG, "Cannot upload: Machine not enrolled")
                return false
            }
            
            // Get machine ID from RemoteLoggingManager prefs
            val prefs = applicationContext.getSharedPreferences("remote_logging", Context.MODE_PRIVATE)
            val machineId = prefs.getString("machine_id", null)
            if (machineId == null) {
                AppLog.w(TAG, "Cannot upload: Machine ID not set")
                return false
            }
            
            // Create payload
            val payload = JSONObject().apply {
                put("machineId", machineId)
                put("compressedLogs", compressed)
                put("uncompressedSize", uncompressedSize)
                put("compressedSize", compressedSize)
                put("logCount", logCount)
            }
            
            // Add certificate authentication
            val authenticatedPayload = SecurityModule.createAuthenticatedRequest("uploadMachineLogs", payload)
            
            // Call Firebase Function
            val result = functions
                .getHttpsCallable("uploadMachineLogs")
                .call(authenticatedPayload.toMap())
                .await()
            
            val data = result.data as? Map<*, *>
            val batchId = data?.get("batchId") as? String
            val actualSize = data?.get("uncompressedSize") as? Number
            
            AppLog.i(TAG, "✅ Batch uploaded: batchId=$batchId, size=${actualSize}KB")
            true
            
        } catch (e: Exception) {
            AppLog.e(TAG, "❌ Batch upload failed", e)
            false
        }
    }
    
    private suspend fun retryFailedBatches() {
        val failedBatches = dao.getFailedBatches()
        if (failedBatches.isEmpty()) return
        
        AppLog.i(TAG, "Retrying ${failedBatches.size} failed batches")
        
        failedBatches.forEach { batch ->
            // Skip invalid batches (empty logs or zero sizes)
            if (batch.compressedLogs.isEmpty() || batch.logCount <= 0 || batch.compressedSize <= 0) {
                AppLog.w(TAG, "⚠️ Deleting invalid batch ${batch.id} (empty or zero size)")
                dao.deleteBatch(batch.id)
                return@forEach
            }
            
            val success = uploadBatch(
                batch.compressedLogs,
                batch.uncompressedSize,
                batch.compressedSize,
                batch.logCount
            )
            
            if (success) {
                dao.deleteBatch(batch.id)
                AppLog.i(TAG, "✅ Retry successful for batch ${batch.id}")
            } else {
                // Update attempt count
                dao.update(
                    batch.copy(
                        uploadAttempts = batch.uploadAttempts + 1,
                        lastAttemptAt = System.currentTimeMillis()
                    )
                )
                AppLog.w(TAG, "❌ Retry failed for batch ${batch.id} (attempt ${batch.uploadAttempts + 1})")
            }
        }
    }
    
    private suspend fun cleanupOldBatches() {
        val cutoffTime = System.currentTimeMillis() - MAX_BATCH_AGE_MS
        val deleted = dao.deleteOldFailedBatches(cutoffTime)
        if (deleted > 0) {
            AppLog.i(TAG, "Cleaned up $deleted old failed batches (>48 hours)")
        }
    }
    
    /**
     * Extension function to convert JSONObject to Map for Firebase Functions calls
     */
    private fun JSONObject.toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        keys().forEach { key ->
            map[key] = when (val value = get(key)) {
                is JSONObject -> value.toMap()
                is JSONArray -> {
                    val list = mutableListOf<Any?>()
                    for (i in 0 until value.length()) {
                        list.add(value.get(i))
                    }
                    list
                }
                else -> value
            }
        }
        return map
    }
}
