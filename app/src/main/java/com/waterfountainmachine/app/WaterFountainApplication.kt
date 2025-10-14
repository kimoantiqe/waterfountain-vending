package com.waterfountainmachine.app

import android.app.Application
import com.waterfountainmachine.app.hardware.WaterFountainManager
import com.waterfountainmachine.app.utils.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application class for Water Fountain Vending Machine
 * Manages global hardware state and initialization
 */
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
        
        // Initialize hardware manager (but don't connect yet)
        hardwareManager = WaterFountainManager.getInstance(this)
        
        AppLog.i(TAG, "Hardware manager created")
        AppLog.i(TAG, "Initial state: ${hardwareState.name}")
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
