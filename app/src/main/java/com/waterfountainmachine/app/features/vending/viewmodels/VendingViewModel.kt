package com.waterfountainmachine.app.features.vending.viewmodels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waterfountainmachine.app.core.di.IODispatcher
import com.waterfountainmachine.app.core.hardware.WaterFountainManager
import com.waterfountainmachine.app.core.utils.AppLog
import com.waterfountainmachine.app.core.utils.UserErrorMessages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

        /**
         * Hard wall-clock cap for a single [WaterFountainManager.dispenseWater]
         * call. If the hardware does not return inside this window we report a
         * timeout error rather than hanging the dispense screen indefinitely.
         */
        const val DISPENSE_TIMEOUT_MS: Long = 60_000L
        const val TIMEOUT_ERROR_CODE: String = "TIMEOUT"
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
                    AppLog.i(TAG, "Hardware connected and ready")
                    _uiState.value = VendingUiState.Ready
                } else {
                    AppLog.w(TAG, "Hardware not connected")
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

                // Hard cap on the hardware call. If the motor/serial bus
                // hangs, we will NOT wait forever — the user will see a
                // DispensingError("TIMEOUT") instead of a frozen UI.
                val result = withTimeoutOrNull(DISPENSE_TIMEOUT_MS) {
                    waterFountainManager.dispenseWater()
                }

                when {
                    result == null -> {
                        AppLog.e(TAG, "dispenseWater() timed out after ${DISPENSE_TIMEOUT_MS}ms")
                        _uiState.value = VendingUiState.DispensingError(
                            message = UserErrorMessages.DISPENSING_FAILED,
                            slot = -1,
                            errorCode = TIMEOUT_ERROR_CODE
                        )
                    }
                    result.success -> {
                        AppLog.i(TAG, "✅ Water dispensing completed successfully from slot ${result.slot}")
                        _progress.value = 100
                        _uiState.value = VendingUiState.DispensingComplete(
                            slot = result.slot,
                            dispensingTimeMs = result.dispensingTimeMs
                        )
                    }
                    else -> {
                        // Log technical error details for admin review
                        AppLog.e(TAG, "❌ Water dispensing failed: ${result.errorMessage} (slot: ${result.slot})")
                        // Show user-friendly error
                        val errorCode = result.errorCode?.toString(16)
                        _uiState.value = VendingUiState.DispensingError(
                            message = UserErrorMessages.DISPENSING_FAILED,
                            slot = result.slot,
                            errorCode = errorCode
                        )
                    }
                }
            } catch (e: CancellationException) {
                // ViewModel cancellation (e.g. screen closed mid-dispense)
                // must propagate so the coroutine actually unwinds.
                throw e
            } catch (e: Exception) {
                // Log technical error details for admin review
                AppLog.e(TAG, "Exception during water dispensing: ${e.javaClass.simpleName} - ${e.message}", e)
                // Show user-friendly error
                _uiState.value = VendingUiState.DispensingError(
                    message = UserErrorMessages.DISPENSING_FAILED,
                    slot = -1, // No slot determined (exception occurred)
                    errorCode = null
                )
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
        _uiState.value = VendingUiState.DispensingComplete(
            slot = -1, // No slot determined (forced continue after error)
            dispensingTimeMs = 0L
        )
    }
}

/**
 * UI States for Vending/Dispensing
 */
sealed class VendingUiState {
    object Initializing : VendingUiState()
    object Ready : VendingUiState()
    object Dispensing : VendingUiState()
    data class DispensingComplete(val slot: Int, val dispensingTimeMs: Long) : VendingUiState()
    object Complete : VendingUiState()
    data class HardwareError(val message: String) : VendingUiState()
    data class DispensingError(val message: String, val slot: Int, val errorCode: String?) : VendingUiState()
}
