package com.yishengkj.logging

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.waterfountainmachine.app.core.utils.AppLog
import com.waterfountainmachine.app.core.utils.SecurePreferences
import java.security.SecureRandom
import java.util.Base64
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

/**
 * LogQueueDatabase - Encrypted Room database for storing log batches before upload.
 *
 * Uses SQLCipher for encryption to protect log data at rest. The encryption
 * passphrase is a **per-device 32-byte random key** generated on first launch
 * and stored in [SecurePreferences] (EncryptedSharedPreferences backed by the
 * Android Keystore, AES-256-GCM). The key never leaves the device and is
 * never embedded in the APK.
 *
 * Backward compatibility: devices upgrading from a build that used a hard-coded
 * passphrase will fail to open the existing DB with the new random key. Because
 * this queue only holds transient upload batches that have not yet been flushed,
 * the upgrade path deletes the legacy file and starts a fresh queue (see
 * [LEGACY_PASSPHRASE_MARKER]). At worst this drops a handful of unflushed log
 * batches during the upgrade window.
 */
@Database(
    entities = [LogUploadQueue::class],
    version = 1,
    exportSchema = false
)
abstract class LogQueueDatabase : RoomDatabase() {
    
    abstract fun logUploadQueueDao(): LogUploadQueueDao
    
    companion object {
        private const val TAG = "LogQueueDatabase"
        private const val DATABASE_NAME = "log_queue.db"
        private const val PREF_PASSPHRASE = "log_queue_passphrase_b64"
        private const val PREF_PASSPHRASE_GENERATED = "log_queue_passphrase_generated"
        private const val PASSPHRASE_BYTES = 32

        /**
         * Sentinel marker written to [PREF_PASSPHRASE_GENERATED] once a fresh
         * per-device key has been provisioned. Absence of this marker is how
         * we detect upgrades from the legacy hard-coded-passphrase build and
         * scrub the stale database file.
         */
        private const val LEGACY_PASSPHRASE_MARKER = "v1"

        @Volatile
        private var INSTANCE: LogQueueDatabase? = null

        /**
         * Gets the singleton database instance with encryption.
         *
         * @param context Application context
         * @param passphrase Override passphrase. Intended for tests only;
         *     production callers MUST pass `null` to use the per-device
         *     random key from [SecurePreferences].
         * @return Encrypted database instance
         */
        fun getInstance(context: Context, passphrase: String? = null): LogQueueDatabase {
            return INSTANCE ?: synchronized(this) {
                val actualPassphrase = passphrase ?: getOrCreatePassphrase(context)
                val instance = buildDatabase(context, actualPassphrase)
                INSTANCE = instance
                instance
            }
        }

        /**
         * Returns the per-device passphrase, generating one if missing.
         *
         * Side effect: on first call after upgrading from a legacy build the
         * stale `log_queue.db` file is deleted (it was encrypted with the old
         * hard-coded key and cannot be reopened).
         */
        private fun getOrCreatePassphrase(context: Context): String {
            val prefs = SecurePreferences.getSystemSettings(context)
            val existing = prefs.getString(PREF_PASSPHRASE, null)
            val isProvisioned = prefs.getString(PREF_PASSPHRASE_GENERATED, null) == LEGACY_PASSPHRASE_MARKER

            if (existing != null && isProvisioned) {
                return existing
            }

            val bytes = ByteArray(PASSPHRASE_BYTES)
            SecureRandom().nextBytes(bytes)
            val generated = Base64.getEncoder().encodeToString(bytes)

            // Scrub any legacy DB encrypted with the old hard-coded key.
            val legacyFile = context.getDatabasePath(DATABASE_NAME)
            if (legacyFile.exists()) {
                val deleted = legacyFile.delete()
                AppLog.w(
                    TAG,
                    "Provisioning new per-device passphrase; legacy log_queue.db " +
                        "${if (deleted) "deleted" else "could not be deleted"}"
                )
            } else {
                AppLog.i(TAG, "Provisioning new per-device passphrase (fresh install)")
            }

            prefs.edit()
                .putString(PREF_PASSPHRASE, generated)
                .putString(PREF_PASSPHRASE_GENERATED, LEGACY_PASSPHRASE_MARKER)
                .apply()
            return generated
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
