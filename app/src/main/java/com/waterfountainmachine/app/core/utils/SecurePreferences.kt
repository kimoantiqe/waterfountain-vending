package com.waterfountainmachine.app.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure SharedPreferences utility using EncryptedSharedPreferences
 * 
 * Provides encrypted storage for sensitive app settings including:
 * - System settings (kiosk mode, serial configuration, etc.)
 * - API mode preferences
 * - Hardware settings
 * 
 * Security Features:
 * - AES-256-GCM encryption for values
 * - AES-256-SIV encryption for keys
 * - Hardware-backed master key when available
 */
object SecurePreferences {
    
    private const val SYSTEM_SETTINGS_NAME = "system_settings_encrypted"
    
    /**
     * Get encrypted SharedPreferences for system settings
     * Replaces plain "system_settings" with encrypted version
     */
    fun getSystemSettings(context: Context): SharedPreferences {
        return getEncryptedPreferences(context, SYSTEM_SETTINGS_NAME)
    }
    
    /**
     * Create encrypted SharedPreferences with given name
     */
    private fun getEncryptedPreferences(
        context: Context,
        name: String
    ): SharedPreferences {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        
        return EncryptedSharedPreferences.create(
            context.applicationContext,
            name,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    /**
     * Migrate data from old plain SharedPreferences to encrypted
     * Call this once during app initialization
     */
    fun migrateSystemSettings(context: Context) {
        try {
            val oldPrefs = context.getSharedPreferences("system_settings", Context.MODE_PRIVATE)
            val newPrefs = getSystemSettings(context)
            
            // Check if migration already done
            if (newPrefs.getBoolean("_migrated", false)) {
                AppLog.d("SecurePreferences", "Migration already completed")
                return
            }
            
            // Migrate all values
            val allEntries = oldPrefs.all
            if (allEntries.isNotEmpty()) {
                val editor = newPrefs.edit()
                
                for ((key, value) in allEntries) {
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is String -> editor.putString(key, value)
                    }
                }
                
                // Mark migration complete
                editor.putBoolean("_migrated", true)
                editor.apply()
                
                AppLog.i("SecurePreferences", "Migrated ${allEntries.size} settings to encrypted storage")
                
                // Clear old preferences after successful migration
                oldPrefs.edit().clear().apply()
                AppLog.i("SecurePreferences", "Cleared old plain-text preferences")
            } else {
                // No data to migrate, just mark as done
                newPrefs.edit().putBoolean("_migrated", true).apply()
                AppLog.i("SecurePreferences", "No settings to migrate")
            }
        } catch (e: Exception) {
            AppLog.e("SecurePreferences", "Error migrating system settings", e)
        }
    }
}
