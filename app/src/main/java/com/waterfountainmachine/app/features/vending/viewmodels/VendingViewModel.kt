package com.waterfountainmachine.app.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waterfountainmachine.app.di.IODispatcher
import com.waterfountainmachine.app.hardware.WaterFountainManager
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.utils.UserErrorMessages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Water Dispensing/Vending Animation
 * 
 * Handles:
 * - Hardware initialization check
 * - Water dispensing command
 * - Dispensing progress tracking
 * - Hardware error handling
 * - Threading management (IO operations)
 * - Critical state management (prevents inactivity timeout during dispensing)
 * 
 * States:
 * - Initializing: Checking hardware connection
 * - Ready: Hardware ready, waiting for animation
 * - Dispensing: Water is being dispensed
 * - DispensingComplete: Dispensing finished successfully
 * - Complete: All operations complete
 * - HardwareError: Hardware connection error
 * - DispensingError: Error during dispensing
 */
@HiltViewModel
class VendingViewModel @Inject constructor(
    private val waterFountainManager: WaterFountainManager,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow<VendingUiState>(VendingUiState.Initializing)
    val uiState: StateFlow<VendingUiState> = _uiState.asStateFlow()

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    private val _isInCriticalState = MutableStateFlow(false)
    val isInCriticalState: StateFlow<Boolean> = _isInCriticalState.asStateFlow()

    companion object {
        private const val TAG = "VendingViewModel"
    }

    init {
        // Check hardware connection on initialization
        checkHardwareConnection()
    }

    /**
     * Check if hardware is connected and ready
     */
    private fun checkHardwareConnection() {
        viewModelScope.launch(ioDispatcher) {
            try {
                AppLog.i(TAG, "Checking hardware connection...")
                
                val isConnected = waterFountainManager.isConnected()
                
                if (isConnected) {
                    AppLog.i(TAG, "✅ Hardware connected and ready")
                    _uiState.value = VendingUiState.Ready
                } else {
                    AppLog.w(TAG, "⚠️ Hardware not connected")
                    _uiState.value = VendingUiState.HardwareError(UserErrorMessages.HARDWARE_NOT_READY)
                }
            } catch (e: Exception) {
                // Log technical error details for admin review
                AppLog.e(TAG, "Hardware connection error: ${e.javaClass.simpleName} - ${e.message}", e)
                // Show user-friendly error
                _uiState.value = VendingUiState.HardwareError(UserErrorMessages.HARDWARE_NOT_READY)
            }
        }
    }

    /**
     * Start water dispensing
     * Should be called when animation reaches the dispensing phase
     */
    fun startDispensing() {
        if (_uiState.value != VendingUiState.Ready) {
            AppLog.w(TAG, "Cannot start dispensing, hardware not ready")
            return
        }

        viewModelScope.launch(ioDispatcher) {
            try {
                // Enter critical state to prevent inactivity timeout
                _isInCriticalState.value = true
                _uiState.value = VendingUiState.Dispensing
                _progress.value = 0
                AppLog.i(TAG, "Starting water dispensing...")

                // Dispense water through hardware manager
                val result = waterFountainManager.dispenseWater()
                
                // Simulate progress updates during dispensing
                for (i in 0..100 step 10) {
                    _progress.value = i
                    AppLog.d(TAG, "Dispensing progress: $i%")
                    kotlinx.coroutines.delay(100) // Small delay for progress animation
                }

                if (result.success) {
                    AppLog.i(TAG, "✅ Water dispensing completed successfully from slot ${result.slot}")
                    _progress.value = 100
                    _uiState.value = VendingUiState.DispensingComplete
                } else {
                    // Log technical error details for admin review
                    AppLog.e(TAG, "❌ Water dispensing failed: ${result.errorMessage} (slot: ${result.slot})")
                    // Show user-friendly error
                    _uiState.value = VendingUiState.DispensingError(UserErrorMessages.DISPENSING_FAILED)
                }

            } catch (e: Exception) {
                // Log technical error details for admin review
                AppLog.e(TAG, "Exception during water dispensing: ${e.javaClass.simpleName} - ${e.message}", e)
                // Show user-friendly error
                _uiState.value = VendingUiState.DispensingError(UserErrorMessages.DISPENSING_FAILED)
            } finally {
                // Exit critical state
                _isInCriticalState.value = false
            }
        }
    }

    /**
     * Called when the animation sequence completes
     * Transitions to Complete state
     */
    fun onAnimationComplete() {
        AppLog.i(TAG, "Animation sequence completed")
        _uiState.value = VendingUiState.Complete
    }

    /**
     * Retry hardware connection
     * Used after hardware errors
     */
    fun retryConnection() {
        AppLog.i(TAG, "Retrying hardware connection...")
        _uiState.value = VendingUiState.Initializing
        checkHardwareConnection()
    }

    /**
     * Force continue to completion
     * Used when hardware errors occur but we want to continue the UX flow
     */
    fun forceContinue() {
        AppLog.w(TAG, "Forcing continue despite hardware error")
        _uiState.value = VendingUiState.DispensingComplete
    }
}

/**
 * UI States for Vending/Dispensing
 */
sealed class VendingUiState {
    object Initializing : VendingUiState()
    object Ready : VendingUiState()
    object Dispensing : VendingUiState()
    object DispensingComplete : VendingUiState()
    object Complete : VendingUiState()
    data class HardwareError(val message: String) : VendingUiState()
    data class DispensingError(val message: String) : VendingUiState()
}
