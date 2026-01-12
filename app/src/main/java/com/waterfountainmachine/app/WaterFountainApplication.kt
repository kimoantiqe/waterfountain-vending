package com.waterfountainmachine.app

import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.initialize
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.analytics.FirebaseAnalytics
import com.waterfountainmachine.app.di.AuthModule
import com.waterfountainmachine.app.security.SecurityModule
import com.waterfountainmachine.app.hardware.WaterFountainManager
import com.waterfountainmachine.app.admin.AdminPinManager
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.core.slot.SlotInventoryManager
import com.waterfountainmachine.app.core.backend.IBackendSlotService
import com.waterfountainmachine.app.di.BackendModule
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Application class - manages hardware state and Firebase initialization
 */
@HiltAndroidApp
class WaterFountainApplication : Application() {
    
    companion object {
        private const val TAG = "WaterFountainApp"
        
        // Journey tracking
        var journeyStartTime: Long = 0
            private set
        
        fun startJourney() {
            journeyStartTime = System.currentTimeMillis()
        }
        
        fun getJourneyDuration(): Long {
            return if (journeyStartTime > 0) {
                System.currentTimeMillis() - journeyStartTime
            } else 0
        }
    }
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    lateinit var hardwareManager: WaterFountainManager
        private set

    private lateinit var healthMonitor: com.waterfountainmachine.app.analytics.IMachineHealthMonitor

    lateinit var slotInventoryManager: SlotInventoryManager
        private set
    
    private lateinit var backendSlotService: IBackendSlotService
    
    // Use StateFlow instead of observer list (fixes memory leak)
    private val _hardwareStateFlow = MutableStateFlow(HardwareState.UNINITIALIZED)
    val hardwareStateFlow: StateFlow<HardwareState> = _hardwareStateFlow.asStateFlow()
    
    // Keep for backward compatibility (deprecated)
    @Deprecated("Use hardwareStateFlow instead", ReplaceWith("hardwareStateFlow.value"))
    var hardwareState: HardwareState
        get() = _hardwareStateFlow.value
        private set(value) {
            _hardwareStateFlow.value = value
        }
    
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
        AppLog.i(TAG, "Water Fountain Vending Machine Application Starting")
        AppLog.i(TAG, "Environment: ${BuildConfig.ENVIRONMENT}")
        AppLog.i(TAG, "Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        AppLog.i(TAG, "Build Type: ${if (BuildConfig.DEBUG) "DEBUG" else "RELEASE"}")
        
        com.waterfountainmachine.app.utils.SecurePreferences.migrateSystemSettings(this)
        com.waterfountainmachine.app.admin.AdminPinManager.initialize(this)
        
        Firebase.initialize(context = this)
        
        // Initialize SecurityModule FIRST before using getMachineId()
        initializeSecurityModule()
        
        // Initialize analytics with machine context
        val machineId = SecurityModule.getMachineId()
        val analyticsManager = com.waterfountainmachine.app.analytics.AnalyticsManager.getInstance(this)
        analyticsManager.setMachineContext(machineId)
        AppLog.i(TAG, "Analytics machine context set: ${machineId?.let { "****${it.takeLast(4)}" } ?: "null"}")
        
        val appCheckProviderFactory = if (BuildConfig.DEBUG) {
            AppLog.i(TAG, "Firebase App Check: DEBUG Provider")
            AppLog.i(TAG, "Check logcat for debug token and register in Firebase Console")
            DebugAppCheckProviderFactory.getInstance()
        } else {
            AppLog.i(TAG, "Firebase App Check: PLAY INTEGRITY Provider")
            PlayIntegrityAppCheckProviderFactory.getInstance()
        }
        
        Firebase.appCheck.installAppCheckProviderFactory(appCheckProviderFactory)
        
        if (BuildConfig.IS_PRODUCTION) {
            AppLog.i(TAG, "Running in PRODUCTION environment")
        } else {
            AppLog.i(TAG, "Running in DEVELOPMENT environment")
        }
        
        initializeCrashlytics()
        initializeAnalytics()
        initializeAuthModule()
        
        hardwareManager = WaterFountainManager.getInstance(this)
        healthMonitor = com.waterfountainmachine.app.di.HealthMonitorModule.getMachineHealthMonitor(this)
        
        // Initialize slot inventory management
        slotInventoryManager = SlotInventoryManager.getInstance(this)
        backendSlotService = BackendModule.getBackendSlotService(this)
        AppLog.i(TAG, "Slot inventory manager initialized")
        
        // Start health monitor once machine is enrolled
        if (com.waterfountainmachine.app.security.SecurityModule.isEnrolled()) {
            val machineId = com.waterfountainmachine.app.security.SecurityModule.getMachineId()
            if (machineId != null) {
                healthMonitor.start(machineId)
                AppLog.i(TAG, "Health monitor started for machine: ****${machineId.takeLast(4)}")
                
                // Sync slot inventory with backend on startup
                applicationScope.launch(Dispatchers.IO) {
                    AppLog.d(TAG, "Syncing slot inventory with backend...")
                    backendSlotService.syncInventoryWithBackend(machineId).fold(
                        onSuccess = { slots: List<SlotInventoryManager.SlotInventory> ->
                            AppLog.i(TAG, "✅ Slot inventory synced: ${slots.size} slots loaded")
                            val totalBottles = slotInventoryManager.getTotalInventory()
                            val fillRate = slotInventoryManager.getInventoryFillRate()
                            AppLog.i(TAG, "Current inventory: $totalBottles bottles (${String.format("%.1f", fillRate)}% capacity)")
                        },
                        onFailure = { error: Throwable ->
                            AppLog.e(TAG, "⚠️ Failed to sync slot inventory: ${error.message}")
                            AppLog.i(TAG, "Using local cache until next sync")
                        }
                    )
                }
            }
        } else {
            AppLog.i(TAG, "Health monitor not started - machine not enrolled")
        }
        
        AppLog.i(TAG, "Hardware manager created")
        AppLog.i(TAG, "Initial state: ${hardwareState.name}")
    }
    
    /**
     * Initialize Crashlytics with PII redaction
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
            
            // PII Redaction: Set up custom key filters
            // These keys will never contain PII - only metadata
            crashlytics.setCustomKey("hardware_state", "initialized")
            
            // Safely check if SecurityModule is enrolled
            try {
                crashlytics.setCustomKey("security_enrolled", SecurityModule.isEnrolled())
                
                // Machine ID is partially masked (show last 4 chars only)
                val machineId = SecurityModule.getMachineId()
                if (machineId != null) {
                    crashlytics.setCustomKey("machine_id_suffix", machineId.takeLast(4))
                }
            } catch (e: Exception) {
                // SecurityModule not yet initialized, skip enrollment keys
                AppLog.d(TAG, "SecurityModule not yet initialized, skipping enrollment keys")
            }
            
            AppLog.i(TAG, "Firebase Crashlytics initialized with PII redaction")
            AppLog.i(TAG, "Crash reporting: ENABLED (for testing)")
            AppLog.i(TAG, "PII Protection: Phone numbers and full machine IDs masked")
            AppLog.i(TAG, "Crashes will be sent on next app launch after crash")
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
            AppLog.i(TAG, "================================================")
            AppLog.i(TAG, "Initializing AuthModule...")
            
            val useMockMode = AuthModule.loadApiModePreference(this)
            AppLog.i(TAG, "Loaded API mode preference: useMockMode=$useMockMode")
            
            AuthModule.initialize(this, useMockMode)
            
            val mode = if (useMockMode) "MOCK" else "REAL API"
            AppLog.i(TAG, "AuthModule initialized in $mode mode")
            AppLog.i(TAG, "================================================")
        } catch (e: Exception) {
            AppLog.e(TAG, "Error initializing AuthModule, falling back to mock mode", e)
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
                    AppLog.i(TAG, "Hardware initialization SUCCESS")
                    onComplete(true)
                } else {
                    updateState(HardwareState.ERROR)
                    AppLog.e(TAG, "Hardware initialization FAILED")
                    onComplete(false)
                }
            } catch (e: Exception) {
                updateState(HardwareState.ERROR)
                AppLog.e(TAG, "Hardware initialization EXCEPTION", e)
                onComplete(false)
            }
        }
    }
    
    /**
     * Get the health monitor instance
     */
    fun getHealthMonitor(): com.waterfountainmachine.app.analytics.IMachineHealthMonitor {
        return healthMonitor
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
     * @deprecated Use hardwareStateFlow.collect() in a lifecycle-aware scope instead
     * This method is kept for backward compatibility but will cause memory leaks if not properly unregistered
     */
    @Deprecated(
        message = "Use hardwareStateFlow.collect() instead to avoid memory leaks",
        replaceWith = ReplaceWith("lifecycleScope.launch { hardwareStateFlow.collect { state -> /* handle state */ } }")
    )
    fun observeHardwareState(observer: (HardwareState) -> Unit) {
        AppLog.w(TAG, "observeHardwareState() is deprecated and may cause memory leaks. Use hardwareStateFlow instead.")
        // Immediately notify of current state
        observer(hardwareState)
    }
    
    /**
     * Update hardware state (now updates StateFlow)
     */
    private fun updateState(newState: HardwareState) {
        val oldState = hardwareState
        hardwareState = newState  // Uses the setter which updates _hardwareStateFlow
        AppLog.i(TAG, "Hardware state changed: $oldState → $newState")
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
