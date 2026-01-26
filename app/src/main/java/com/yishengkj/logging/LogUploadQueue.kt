package com.yishengkj.logging

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * LogUploadQueue - Room entity representing a batch of logs queued for upload.
 * 
 * Each batch contains up to 500 log entries compressed and ready for upload.
 * Batches are retained for up to 48 hours with retry logic on upload failure.
 */
@Entity(tableName = "log_upload_queue")
data class LogUploadQueue(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /**
     * Compressed log data (gzip + Base64 encoded JSON array of log entries)
     */
    val compressedLogs: String,
    
    /**
     * Uncompressed size in bytes (for monitoring and size validation)
     */
    val uncompressedSize: Int,
    
    /**
     * Compressed size in bytes (for monitoring network usage)
     */
    val compressedSize: Int,
    
    /**
     * Number of log entries in this batch
     */
    val logCount: Int,
    
    /**
     * Timestamp when this batch was created (milliseconds since epoch)
     */
    val createdAt: Long = System.currentTimeMillis(),
    
    /**
     * Number of upload attempts for this batch
     */
    val uploadAttempts: Int = 0,
    
    /**
     * Status of this batch: "pending", "uploading", "uploaded", "failed"
     */
    val status: String = "pending",
    
    /**
     * Last error message if upload failed (null if no error)
     */
    val lastError: String? = null,
    
    /**
     * Timestamp of last upload attempt (null if never attempted)
     */
    val lastAttemptAt: Long? = null
)
