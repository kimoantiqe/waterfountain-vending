package com.waterfountainmachine.app.di

import android.content.Context
import com.waterfountainmachine.app.core.backend.BackendSlotService
import com.waterfountainmachine.app.core.backend.IBackendSlotService
import com.waterfountainmachine.app.core.backend.MockBackendSlotService
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.utils.SecurePreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for backend slot service dependencies
 * 
 * Also provides static accessor for non-Hilt classes
 */
@Module
@InstallIn(SingletonComponent::class)
object BackendModule {
    
    private const val TAG = "BackendModule"
    private const val KEY_USE_MOCK_SLOT_SERVICE = "use_mock_slot_service"
    
    @Volatile
    private var cachedService: IBackendSlotService? = null
    
    /**
     * Initialize slot service mode (called from Admin panel)
     * @param context Application context
     * @param useMockMode true for mock, false for real backend
     */
    fun initialize(context: Context, useMockMode: Boolean) {
        AppLog.i(TAG, "initialize() called with useMockMode=$useMockMode")
        
        // Use EncryptedSharedPreferences for consistency
        val prefs = SecurePreferences.getSystemSettings(context)
        prefs.edit()
            .putBoolean(KEY_USE_MOCK_SLOT_SERVICE, useMockMode)
            .apply()
        
        // Verify it was written
        val savedValue = prefs.getBoolean(KEY_USE_MOCK_SLOT_SERVICE, false)
        AppLog.i(TAG, "EncryptedSharedPreferences written: useMockMode=$savedValue")
        
        val mode = if (useMockMode) "Mock" else "Real Backend"
        AppLog.i(TAG, "Backend slot service mode set to: $mode")
    }
    
    /**
     * Load current slot service mode preference
     * @param context Application context
     * @return true if using mock mode, false if using real backend
     */
    fun loadSlotServiceModePreference(context: Context): Boolean {
        // Use EncryptedSharedPreferences for consistency
        val prefs = SecurePreferences.getSystemSettings(context)
        val useMockMode = prefs.getBoolean(KEY_USE_MOCK_SLOT_SERVICE, false) // Default to real for production
        AppLog.i(TAG, "loadSlotServiceModePreference() reading from EncryptedSharedPreferences: useMockMode=$useMockMode")
        return useMockMode
    }
    
    /**
     * Check if currently using live backend (not mock)
     * @param context Application context
     * @return true if using live backend, false if mock
     */
    fun isUsingLiveBackend(context: Context): Boolean {
        return !loadSlotServiceModePreference(context)
    }
    
    /**
     * Provide Backend Slot Service
     * 
     * Injects the correct service (Mock or Real) based on SharedPreferences
     * at app startup. Changes require app restart to take effect.
     */
    @Provides
    @Singleton
    fun provideBackendSlotService(
        @ApplicationContext context: Context
    ): IBackendSlotService {
        if (cachedService != null) {
            return cachedService!!
        }
        
        // Read preference once at app startup
        val useMockMode = loadSlotServiceModePreference(context)
        val mode = if (useMockMode) "Mock" else "Real Backend"
        
        AppLog.i(TAG, "Providing BackendSlotService: $mode")
        
        val service = if (useMockMode) {
            AppLog.i(TAG, "✅ Injecting MockBackendSlotService")
            MockBackendSlotService(context)
        } else {
            AppLog.i(TAG, "✅ Injecting BackendSlotService (Real)")
            BackendSlotService.getInstance(context)
        }
        
        cachedService = service
        return service
    }
    
    /**
     * Get backend slot service instance for non-Hilt classes
     * 
     * This allows classes that don't use Hilt to get the correct service
     * based on the current mode setting.
     */
    fun getBackendSlotService(context: Context): IBackendSlotService {
        if (cachedService != null) {
            val serviceType = if (cachedService is MockBackendSlotService) "Mock" else "Real"
            AppLog.d(TAG, "Returning cached service: $serviceType")
            return cachedService!!
        }
        
        val useMockMode = loadSlotServiceModePreference(context)
        val mode = if (useMockMode) "Mock" else "Real Backend"
        AppLog.i(TAG, "Creating new BackendSlotService: $mode")
        
        val service = if (useMockMode) {
            AppLog.i(TAG, "🔴 Using MockBackendSlotService - NO REAL API CALLS")
            MockBackendSlotService(context)
        } else {
            AppLog.i(TAG, "🟢 Using BackendSlotService - REAL API CALLS TO FIREBASE")
            BackendSlotService.getInstance(context)
        }
        
        cachedService = service
        return service
    }
}
