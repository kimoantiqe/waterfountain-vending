package com.waterfountainmachine.app.core.di
import android.content.Context
import com.waterfountainmachine.app.core.analytics.IMachineHealthMonitor
import com.waterfountainmachine.app.core.analytics.MachineHealthMonitor
import com.waterfountainmachine.app.core.analytics.MockMachineHealthMonitor
import com.waterfountainmachine.app.core.utils.AppLog
import com.waterfountainmachine.app.core.utils.SecurePreferences
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
     * Provide [IMachineHealthMonitor] to the Hilt graph.
     *
     * Delegates to [getMachineHealthMonitor] so Hilt-managed and non-Hilt
     * callers (e.g. [com.waterfountainmachine.app.WaterFountainApplication])
     * always observe the same singleton instance and the same Mock/Real
     * decision. Without this delegation, Hilt would create its own cache
     * and a parallel one would live in [cachedMonitor], causing the two
     * graphs to drift apart over time.
     *
     * Mode is captured once at app startup; changes require an app restart.
     */
    @Provides
    @Singleton
    fun provideMachineHealthMonitor(
        @ApplicationContext context: Context
    ): IMachineHealthMonitor = getMachineHealthMonitor(context)

    /**
     * Get the singleton health monitor instance.
     *
     * Public so non-Hilt entry points (currently only [Application.onCreate])
     * can obtain it without going through [dagger.hilt.EntryPoint] indirection.
     * All other callers should `@Inject` it.
     *
     * Thread-safe via the double-checked-lock pattern in [synchronized].
     */
    fun getMachineHealthMonitor(context: Context): IMachineHealthMonitor {
        cachedMonitor?.let { return it }
        return synchronized(this) {
            cachedMonitor ?: createMonitor(context).also { cachedMonitor = it }
        }
    }

    private fun createMonitor(context: Context): IMachineHealthMonitor {
        val useMockMode = loadHealthMonitorModePreference(context)
        return if (useMockMode) {
            AppLog.i(TAG, "🔴 Using MockMachineHealthMonitor - NO HEARTBEATS")
            MockMachineHealthMonitor(context)
        } else {
            AppLog.i(TAG, "🟢 Using MachineHealthMonitor - REAL HEARTBEATS TO FIREBASE")
            MachineHealthMonitor.getInstance(context)
        }
    }
}
