package com.yishengkj.logging

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

/**
 * LogQueueDatabase - Encrypted Room database for storing log batches before upload.
 * 
 * Uses SQLCipher for encryption to protect log data at rest.
 * Passphrase can be configured via Firebase (fetched at app startup).
 */
@Database(
    entities = [LogUploadQueue::class],
    version = 1,
    exportSchema = false
)
abstract class LogQueueDatabase : RoomDatabase() {
    
    abstract fun logUploadQueueDao(): LogUploadQueueDao
    
    companion object {
        private const val DATABASE_NAME = "log_queue.db"
        internal const val DEFAULT_PASSPHRASE = "WaterFountainLogs2026Default!"
        
        @Volatile
        private var INSTANCE: LogQueueDatabase? = null
        
        /**
         * Gets the singleton database instance with encryption.
         * 
         * @param context Application context
         * @param passphrase Encryption passphrase (uses default if null)
         * @return Encrypted database instance
         */
        fun getInstance(context: Context, passphrase: String? = null): LogQueueDatabase {
            return INSTANCE ?: synchronized(this) {
                val actualPassphrase = passphrase ?: DEFAULT_PASSPHRASE
                val instance = buildDatabase(context, actualPassphrase)
                INSTANCE = instance
                instance
            }
        }
        
        /**
         * Resets the database with a new passphrase.
         * CAUTION: This deletes all existing data.
         * 
         * @param context Application context
         * @param newPassphrase New encryption passphrase
         */
        fun resetWithNewPassphrase(context: Context, newPassphrase: String): LogQueueDatabase {
            synchronized(this) {
                // Close existing database
                INSTANCE?.close()
                INSTANCE = null
                
                // Delete the database file
                context.getDatabasePath(DATABASE_NAME).delete()
                
                // Create new database with new passphrase
                val instance = buildDatabase(context, newPassphrase)
                INSTANCE = instance
                return instance
            }
        }
        
        private fun buildDatabase(context: Context, passphrase: String): LogQueueDatabase {
            val passphraseBytes = SQLiteDatabase.getBytes(passphrase.toCharArray())
            val factory = SupportFactory(passphraseBytes)
            
            return Room.databaseBuilder(
                context.applicationContext,
                LogQueueDatabase::class.java,
                DATABASE_NAME
            )
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration() // Safe since we only store temporary upload queue
                .build()
        }
    }
}
