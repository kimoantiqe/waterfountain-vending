package com.waterfountainmachine.app.di

import android.content.Context
import com.waterfountainmachine.app.analytics.IMachineHealthMonitor
import com.waterfountainmachine.app.analytics.MachineHealthMonitor
import com.waterfountainmachine.app.analytics.MockMachineHealthMonitor
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.utils.SecurePreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for health monitor dependencies
 * 
 * Also provides static accessor for non-Hilt classes
 */
@Module
@InstallIn(SingletonComponent::class)
object HealthMonitorModule {
    
    private const val TAG = "HealthMonitorModule"
    private const val KEY_USE_MOCK_HEALTH_MONITOR = "use_mock_health_monitor"
    
    @Volatile
    private var cachedMonitor: IMachineHealthMonitor? = null
    
    /**
     * Initialize health monitor mode (called from Admin panel)
     * @param context Application context
     * @param useMockMode true for mock, false for real health monitoring
     */
    fun initialize(context: Context, useMockMode: Boolean) {
        AppLog.i(TAG, "initialize() called with useMockMode=$useMockMode")
        
        val prefs = SecurePreferences.getSystemSettings(context)
        prefs.edit()
            .putBoolean(KEY_USE_MOCK_HEALTH_MONITOR, useMockMode)
            .apply()
        
        val savedValue = prefs.getBoolean(KEY_USE_MOCK_HEALTH_MONITOR, false)
        AppLog.i(TAG, "EncryptedSharedPreferences written: useMockMode=$savedValue")
        
        val mode = if (useMockMode) "Mock" else "Real Health Monitoring"
        AppLog.i(TAG, "Health monitor mode set to: $mode")
    }
    
    /**
     * Load current health monitor mode preference
     * @param context Application context
     * @return true if using mock mode, false if using real health monitoring
     */
    fun loadHealthMonitorModePreference(context: Context): Boolean {
        val prefs = SecurePreferences.getSystemSettings(context)
        val useMockMode = prefs.getBoolean(KEY_USE_MOCK_HEALTH_MONITOR, false) // Default to real
        AppLog.i(TAG, "loadHealthMonitorModePreference() reading: useMockMode=$useMockMode")
        return useMockMode
    }
    
    /**
     * Provide Machine Health Monitor
     * 
     * Injects the correct monitor (Mock or Real) based on SharedPreferences
     * at app startup. Changes require app restart to take effect.
     */
    @Provides
    @Singleton
    fun provideMachineHealthMonitor(
        @ApplicationContext context: Context
    ): IMachineHealthMonitor {
        if (cachedMonitor != null) {
            return cachedMonitor!!
        }
        
        val useMockMode = loadHealthMonitorModePreference(context)
        val mode = if (useMockMode) "Mock" else "Real Health Monitoring"
        
        AppLog.i(TAG, "Providing MachineHealthMonitor: $mode")
        
        val monitor = if (useMockMode) {
            AppLog.i(TAG, "✅ Injecting MockMachineHealthMonitor")
            MockMachineHealthMonitor(context)
        } else {
            AppLog.i(TAG, "✅ Injecting MachineHealthMonitor (Real)")
            MachineHealthMonitor.getInstance(context)
        }
        
        cachedMonitor = monitor
        return monitor
    }
    
    /**
     * Get health monitor instance for non-Hilt classes
     * 
     * This allows classes that don't use Hilt to get the correct monitor
     * based on the current mode setting.
     */
    fun getMachineHealthMonitor(context: Context): IMachineHealthMonitor {
        if (cachedMonitor != null) {
            val monitorType = if (cachedMonitor is MockMachineHealthMonitor) "Mock" else "Real"
            AppLog.d(TAG, "Returning cached monitor: $monitorType")
            return cachedMonitor!!
        }
        
        val useMockMode = loadHealthMonitorModePreference(context)
        val mode = if (useMockMode) "Mock" else "Real Health Monitoring"
        AppLog.i(TAG, "Creating new MachineHealthMonitor: $mode")
        
        val monitor = if (useMockMode) {
            AppLog.i(TAG, "🔴 Using MockMachineHealthMonitor - NO HEARTBEATS")
            MockMachineHealthMonitor(context)
        } else {
            AppLog.i(TAG, "🟢 Using MachineHealthMonitor - REAL HEARTBEATS TO FIREBASE")
            MachineHealthMonitor.getInstance(context)
        }
        
        cachedMonitor = monitor
        return monitor
    }
}
