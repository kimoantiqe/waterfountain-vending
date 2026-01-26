package com.yishengkj.logging

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * LogUploadQueueDao - Data Access Object for log upload queue operations
 */
@Dao
interface LogUploadQueueDao {
    
    @Insert
    suspend fun insert(batch: LogUploadQueue): Long
    
    @Update
    suspend fun update(batch: LogUploadQueue)
    
    @Query("SELECT * FROM log_upload_queue WHERE status = 'pending' ORDER BY createdAt ASC")
    suspend fun getPendingBatches(): List<LogUploadQueue>
    
    @Query("SELECT * FROM log_upload_queue WHERE id = :id")
    suspend fun getBatchById(id: Long): LogUploadQueue?
    
    @Query("DELETE FROM log_upload_queue WHERE id = :id")
    suspend fun deleteBatch(id: Long)
    
    @Query("DELETE FROM log_upload_queue WHERE createdAt < :cutoffTime AND status = 'failed'")
    suspend fun deleteOldFailedBatches(cutoffTime: Long): Int
    
    @Query("SELECT COUNT(*) FROM log_upload_queue WHERE status IN ('pending', 'failed')")
    suspend fun getPendingBatchCount(): Int
    
    @Query("SELECT * FROM log_upload_queue WHERE status = 'failed' ORDER BY createdAt ASC")
    suspend fun getFailedBatches(): List<LogUploadQueue>
}
