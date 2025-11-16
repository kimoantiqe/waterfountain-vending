package com.waterfountainmachine.app

import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.initialize
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.analytics.FirebaseAnalytics
import com.waterfountainmachine.app.di.AuthModule
import com.waterfountainmachine.app.security.SecurityModule
import com.waterfountainmachine.app.hardware.WaterFountainManager
import com.waterfountainmachine.app.admin.AdminPinManager
import com.waterfountainmachine.app.utils.AppLog
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application class for Water Fountain Vending Machine
 * Manages global hardware state and initialization
 * 
 * @HiltAndroidApp - Triggers Hilt code generation and enables DI
 */
@HiltAndroidApp
class WaterFountainApplication : Application() {
    
    companion object {
        private const val TAG = "WaterFountainApp"
    }
    
    // Application-wide coroutine scope
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // Hardware manager instance (initialized once)
    lateinit var hardwareManager: WaterFountainManager
        private set
    
    // Hardware state
    var hardwareState: HardwareState = HardwareState.UNINITIALIZED
        private set
    
    // State observers
    private val stateObservers = mutableListOf<(HardwareState) -> Unit>()
    
    enum class HardwareState {
        UNINITIALIZED,
        INITIALIZING,
        READY,
        ERROR,
        MAINTENANCE_MODE,
        DISCONNECTED
    }
    
    override fun onCreate() {
        super.onCreate()
        AppLog.i(TAG, "═══════════════════════════════════════════════")
        AppLog.i(TAG, "Water Fountain Vending Machine Application Starting")
        AppLog.i(TAG, "═══════════════════════════════════════════════")
        
        // Migrate SharedPreferences to encrypted storage (one-time migration)
        com.waterfountainmachine.app.utils.SecurePreferences.migrateSystemSettings(this)
        
        // Initialize admin PIN system
        com.waterfountainmachine.app.admin.AdminPinManager.initialize(this)
        
        // Initialize Firebase
        Firebase.initialize(context = this)
        
        // Initialize Firebase App Check with debug provider for development
        // In production, replace with DeviceCheckProviderFactory or SafetyNetAppCheckProviderFactory
        Firebase.appCheck.installAppCheckProviderFactory(
            DebugAppCheckProviderFactory.getInstance()
        )
        AppLog.i(TAG, "Firebase App Check initialized with Debug Provider")
        AppLog.i(TAG, "⚠️ Check logcat for App Check debug token - register it in Firebase Console")
        
        // Initialize Firebase Crashlytics
        initializeCrashlytics()
        
        // Initialize Firebase Analytics
        initializeAnalytics()
        
        // Initialize security module
        initializeSecurityModule()
        
        // Initialize authentication module
        initializeAuthModule()
        
        // Initialize hardware manager (but don't connect yet)
        hardwareManager = WaterFountainManager.getInstance(this)
        
        AppLog.i(TAG, "Hardware manager created")
        AppLog.i(TAG, "Initial state: ${hardwareState.name}")
    }
    
    /**
     * Initialize Firebase Crashlytics for crash reporting
     */
    private fun initializeCrashlytics() {
        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            
            // ENABLE crash reporting for testing (set to true)
            // In production, you can keep this enabled or disable for debug builds
            crashlytics.setCrashlyticsCollectionEnabled(true)
            
            // Set custom keys for better crash analysis
            crashlytics.setCustomKey("app_version", BuildConfig.VERSION_NAME)
            crashlytics.setCustomKey("build_type", BuildConfig.BUILD_TYPE)
            crashlytics.setCustomKey("debug_build", BuildConfig.DEBUG)
            
            AppLog.i(TAG, "✅ Firebase Crashlytics initialized")
            AppLog.i(TAG, "📊 Crash reporting: ENABLED (for testing)")
            AppLog.i(TAG, "💡 Crashes will be sent on next app launch after crash")
        } catch (e: Exception) {
            AppLog.e(TAG, "Error initializing Crashlytics", e)
        }
    }
    
    /**
     * Initialize Firebase Analytics
     */
    private fun initializeAnalytics() {
        try {
            val analytics = FirebaseAnalytics.getInstance(this)
            
            // Enable analytics collection
            analytics.setAnalyticsCollectionEnabled(true)
            
            // Set default properties
            analytics.setUserProperty("app_version", BuildConfig.VERSION_NAME)
            
            AppLog.i(TAG, "Firebase Analytics initialized")
        } catch (e: Exception) {
            AppLog.e(TAG, "Error initializing Analytics", e)
        }
    }
    
    /**
     * Initialize the security module for certificate management
     */
    private fun initializeSecurityModule() {
        try {
            SecurityModule.initialize(this)
            
            if (SecurityModule.isEnrolled()) {
                val machineId = SecurityModule.getMachineId()
                AppLog.i(TAG, "SecurityModule initialized - Machine enrolled: ****${machineId?.takeLast(4)}")
                
                // Check certificate expiry
                if (SecurityModule.isCertificateExpiringSoon()) {
                    val daysRemaining = SecurityModule.getDaysUntilExpiry()
                    AppLog.w(TAG, "Certificate expiring in $daysRemaining days!")
                }
            } else {
                AppLog.i(TAG, "SecurityModule initialized - Machine not enrolled")
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Error initializing SecurityModule", e)
        }
    }
    
    /**
     * Initialize the authentication module with saved preferences
     */
    private fun initializeAuthModule() {
        try {
            AppLog.i(TAG, "═══════════════════════════════════════════════")
            AppLog.i(TAG, "Initializing AuthModule...")
            
            // Load saved API mode preference (default to mock mode for development)
            val useMockMode = AuthModule.loadApiModePreference(this)
            AppLog.i(TAG, "Loaded API mode preference: useMockMode=$useMockMode")
            
            // Initialize AuthModule
            AuthModule.initialize(this, useMockMode)
            
            val mode = if (useMockMode) "MOCK" else "REAL API"
            AppLog.i(TAG, "✅ AuthModule initialized in $mode mode")
            AppLog.i(TAG, "═══════════════════════════════════════════════")
        } catch (e: Exception) {
            // Fallback to mock mode if initialization fails
            AppLog.e(TAG, "❌ Error initializing AuthModule, falling back to mock mode", e)
            AuthModule.initialize(this, useMockMode = true)
        }
    }
    
    /**
     * Initialize hardware connection
     * Call this from splash screen or main activity
     */
    fun initializeHardware(onComplete: (Boolean) -> Unit = {}) {
        if (hardwareState == HardwareState.INITIALIZING || hardwareState == HardwareState.READY) {
            AppLog.d(TAG, "Hardware already initialized or initializing")
            onComplete(hardwareState == HardwareState.READY)
            return
        }
        
        updateState(HardwareState.INITIALIZING)
        
        applicationScope.launch {
            try {
                AppLog.i(TAG, "Starting hardware initialization...")
                val success = hardwareManager.initialize()
                
                if (success) {
                    updateState(HardwareState.READY)
                    AppLog.i(TAG, "✅ Hardware initialization SUCCESS")
                    onComplete(true)
                } else {
                    updateState(HardwareState.ERROR)
                    AppLog.e(TAG, "❌ Hardware initialization FAILED")
                    onComplete(false)
                }
            } catch (e: Exception) {
                updateState(HardwareState.ERROR)
                AppLog.e(TAG, "❌ Hardware initialization EXCEPTION", e)
                onComplete(false)
            }
        }
    }
    
    /**
     * Shutdown hardware
     */
    fun shutdownHardware() {
        applicationScope.launch {
            try {
                AppLog.i(TAG, "Shutting down hardware...")
                hardwareManager.shutdown()
                updateState(HardwareState.DISCONNECTED)
                AppLog.i(TAG, "Hardware shut down successfully")
            } catch (e: Exception) {
                AppLog.e(TAG, "Error shutting down hardware", e)
            }
        }
    }
    
    /**
     * Reinitialize hardware (for admin panel)
     */
    fun reinitializeHardware(onComplete: (Boolean) -> Unit = {}) {
        shutdownHardware()
        // Wait a bit before reinitializing
        applicationScope.launch {
            kotlinx.coroutines.delay(500)
            initializeHardware(onComplete)
        }
    }
    
    /**
     * Check if hardware is ready
     */
    fun isHardwareReady(): Boolean {
        return hardwareState == HardwareState.READY && hardwareManager.isReady()
    }
    
    /**
     * Register observer for hardware state changes
     */
    fun observeHardwareState(observer: (HardwareState) -> Unit) {
        stateObservers.add(observer)
        // Immediately notify of current state
        observer(hardwareState)
    }
    
    /**
     * Unregister observer
     */
    fun removeHardwareStateObserver(observer: (HardwareState) -> Unit) {
        stateObservers.remove(observer)
    }
    
    /**
     * Update hardware state and notify observers
     */
    private fun updateState(newState: HardwareState) {
        val oldState = hardwareState
        hardwareState = newState
        AppLog.i(TAG, "Hardware state changed: $oldState → $newState")
        
        // Notify all observers
        stateObservers.forEach { it(newState) }
    }
    
    /**
     * Get hardware state description
     */
    fun getHardwareStateDescription(): String {
        return when (hardwareState) {
            HardwareState.UNINITIALIZED -> "Not initialized"
            HardwareState.INITIALIZING -> "Initializing..."
            HardwareState.READY -> "Ready"
            HardwareState.ERROR -> "Error - Check logs"
            HardwareState.MAINTENANCE_MODE -> "Maintenance mode"
            HardwareState.DISCONNECTED -> "Disconnected"
        }
    }
}
