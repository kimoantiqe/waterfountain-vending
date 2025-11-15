package com.waterfountainmachine.app.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waterfountainmachine.app.admin.AdminPinManager
import com.waterfountainmachine.app.config.WaterFountainConfig
import com.waterfountainmachine.app.utils.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Admin Authentication
 * 
 * Handles:
 * - PIN code entry and validation
 * - Rate limiting (3 attempts, 1-hour lockout)
 * - Lockout state persistence
 * - Critical state management (prevents inactivity timeout during validation)
 * 
 * States:
 * - EnteringPin: User is entering PIN
 * - Validating: PIN validation in progress
 * - Authenticated: PIN is correct
 * - InvalidPin: PIN is incorrect (shows remaining attempts)
 * - MaxAttemptsReached: 3rd failed attempt, triggers lockout
 * - LockedOut: User is locked out (shows remaining time)
 * - Error: Unexpected error occurred
 */
@HiltViewModel
class AdminViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow<AdminUiState>(AdminUiState.EnteringPin)
    val uiState: StateFlow<AdminUiState> = _uiState.asStateFlow()

    private val _pinDisplay = MutableStateFlow("")
    val pinDisplay: StateFlow<String> = _pinDisplay.asStateFlow()

    private val _isInCriticalState = MutableStateFlow(false)
    val isInCriticalState: StateFlow<Boolean> = _isInCriticalState.asStateFlow()

    private var failedAttempts = 0
    private var lockoutUntil: Long = 0

    companion object {
        private const val TAG = "AdminViewModel"
        private const val MAX_PIN_LENGTH = 8
    }

    init {
        // Load rate limit state on initialization
        checkLockoutStatus()
    }

    /**
     * Check if user is currently locked out
     */
    fun checkLockoutStatus() {
        val (attempts, lockout) = AdminPinManager.getRateLimitState(context)
        failedAttempts = attempts
        lockoutUntil = lockout

        val now = System.currentTimeMillis()
        if (failedAttempts >= WaterFountainConfig.ADMIN_MAX_ATTEMPTS && lockoutUntil > now) {
            // Still locked out
            val remainingMinutes = ((lockoutUntil - now) / 60000).toInt()
            _uiState.value = AdminUiState.LockedOut(remainingMinutes)
            AppLog.w(TAG, "User locked out for $remainingMinutes more minutes")
        } else if (failedAttempts >= WaterFountainConfig.ADMIN_MAX_ATTEMPTS && lockoutUntil <= now) {
            // Lockout expired, reset
            failedAttempts = 0
            lockoutUntil = 0
            AdminPinManager.saveRateLimitState(context, 0, 0L)
            _uiState.value = AdminUiState.EnteringPin
            AppLog.i(TAG, "Lockout expired, reset attempts")
        }
    }

    /**
     * Add a digit to the PIN code
     */
    fun addDigit(digit: String) {
        if (_pinDisplay.value.length < MAX_PIN_LENGTH && digit.matches(Regex("\\d"))) {
            _pinDisplay.value += digit
            AppLog.d(TAG, "Added digit, PIN length: ${_pinDisplay.value.length}")

            // Auto-verify when max length reached
            if (_pinDisplay.value.length == MAX_PIN_LENGTH) {
                verifyPin()
            }
        }
    }

    /**
     * Remove the last digit from the PIN code
     */
    fun removeLastDigit() {
        if (_pinDisplay.value.isNotEmpty()) {
            _pinDisplay.value = _pinDisplay.value.dropLast(1)
            AppLog.d(TAG, "Removed last digit, PIN length: ${_pinDisplay.value.length}")
        }
    }

    /**
     * Clear the entire PIN code
     */
    fun clearPin() {
        _pinDisplay.value = ""
        AppLog.d(TAG, "PIN cleared")
    }

    /**
     * Verify the entered PIN code
     */
    fun verifyPin() {
        val pin = _pinDisplay.value
        if (pin.isEmpty()) {
            AppLog.w(TAG, "Cannot verify empty PIN")
            return
        }

        viewModelScope.launch {
            try {
                // Enter critical state to prevent inactivity timeout
                _isInCriticalState.value = true
                _uiState.value = AdminUiState.Validating
                AppLog.i(TAG, "Validating PIN...")

                val isValid = AdminPinManager.validatePin(context, pin)

                if (isValid) {
                    // Success - reset attempts and authenticate
                    failedAttempts = 0
                    lockoutUntil = 0
                    AdminPinManager.saveRateLimitState(context, 0, 0L)
                    _uiState.value = AdminUiState.Authenticated
                    AppLog.i(TAG, "PIN validated successfully")
                } else {
                    // Failed attempt
                    failedAttempts++
                    AppLog.w(TAG, "Invalid PIN, attempt $failedAttempts/${WaterFountainConfig.ADMIN_MAX_ATTEMPTS}")

                    if (failedAttempts >= WaterFountainConfig.ADMIN_MAX_ATTEMPTS) {
                        // Max attempts reached - trigger lockout
                        lockoutUntil = System.currentTimeMillis() + (WaterFountainConfig.ADMIN_LOCKOUT_MINUTES * 60 * 1000)
                        AdminPinManager.saveRateLimitState(context, failedAttempts, lockoutUntil)
                        _uiState.value = AdminUiState.MaxAttemptsReached(WaterFountainConfig.ADMIN_LOCKOUT_MINUTES)
                        AppLog.w(TAG, "Max attempts reached, locked out for ${WaterFountainConfig.ADMIN_LOCKOUT_MINUTES} minutes")
                    } else {
                        // Still have attempts remaining
                        val remainingAttempts = WaterFountainConfig.ADMIN_MAX_ATTEMPTS - failedAttempts
                        AdminPinManager.saveRateLimitState(context, failedAttempts, lockoutUntil)
                        _uiState.value = AdminUiState.InvalidPin(remainingAttempts)
                        AppLog.w(TAG, "Invalid PIN, $remainingAttempts attempts remaining")
                    }
                }

                // Clear PIN after validation
                _pinDisplay.value = ""

            } catch (e: Exception) {
                AppLog.e(TAG, "Error validating PIN", e)
                _uiState.value = AdminUiState.Error("Validation error: ${e.message}")
            } finally {
                // Exit critical state
                _isInCriticalState.value = false
            }
        }
    }

    /**
     * Reset to entering PIN state (used after errors)
     */
    fun resetToEnteringPin() {
        _uiState.value = AdminUiState.EnteringPin
        _pinDisplay.value = ""
        AppLog.d(TAG, "Reset to entering PIN state")
    }
}

/**
 * UI States for Admin Authentication
 */
sealed class AdminUiState {
    object EnteringPin : AdminUiState()
    object Validating : AdminUiState()
    object Authenticated : AdminUiState()
    data class InvalidPin(val remainingAttempts: Int) : AdminUiState()
    data class MaxAttemptsReached(val lockoutMinutes: Long) : AdminUiState()
    data class LockedOut(val remainingMinutes: Int) : AdminUiState()
    data class Error(val message: String) : AdminUiState()
}
