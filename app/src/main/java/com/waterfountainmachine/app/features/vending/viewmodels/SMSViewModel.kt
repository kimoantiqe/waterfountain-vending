package com.waterfountainmachine.app.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waterfountainmachine.app.auth.IAuthenticationRepository
import com.waterfountainmachine.app.config.WaterFountainConfig
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.utils.UserErrorMessages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for SMS/Phone Number Entry
 * 
 * Handles:
 * - Phone number entry and validation
 * - Phone number formatting and masking
 * - OTP request with error handling
 * - Mock mode (phone 1111111111)
 * - Network error categorization
 * - Critical state management (prevents inactivity timeout during OTP request)
 * 
 * States:
 * - PhoneEntry: User is entering phone number
 * - InvalidPhoneNumber: Phone number is invalid
 * - RequestingOtp: OTP request in progress
 * - OtpRequestSuccess: OTP sent successfully
 * - DailyLimitReached: User has reached daily OTP limit
 * - Error: Network or other error occurred
 */
@HiltViewModel
class SMSViewModel @Inject constructor(
    private val authRepository: IAuthenticationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<SMSUiState>(SMSUiState.PhoneEntry)
    val uiState: StateFlow<SMSUiState> = _uiState.asStateFlow()

    private val _phoneNumber = MutableStateFlow("")
    val phoneNumber: StateFlow<String> = _phoneNumber.asStateFlow()

    private val _isPhoneVisible = MutableStateFlow(false)  // Default to hidden
    val isPhoneVisible: StateFlow<Boolean> = _isPhoneVisible.asStateFlow()

    private val _isInCriticalState = MutableStateFlow(false)
    val isInCriticalState: StateFlow<Boolean> = _isInCriticalState.asStateFlow()

    companion object {
        private const val TAG = "SMSViewModel"
        private const val MOCK_PHONE = "1111111111"
        private const val PHONE_LENGTH = 10
    }

    /**
     * Add a digit to the phone number
     */
    fun addDigit(digit: String) {
        if (_phoneNumber.value.length < PHONE_LENGTH && digit.matches(Regex("\\d"))) {
            _phoneNumber.value += digit
            _uiState.value = SMSUiState.PhoneEntry
            AppLog.d(TAG, "Added digit, phone length: ${_phoneNumber.value.length}")
        }
    }

    /**
     * Remove the last digit from the phone number
     */
    fun removeLastDigit() {
        if (_phoneNumber.value.isNotEmpty()) {
            _phoneNumber.value = _phoneNumber.value.dropLast(1)
            _uiState.value = SMSUiState.PhoneEntry
            AppLog.d(TAG, "Removed last digit, phone length: ${_phoneNumber.value.length}")
        }
    }

    /**
     * Clear the entire phone number
     */
    fun clearPhoneNumber() {
        _phoneNumber.value = ""
        _uiState.value = SMSUiState.PhoneEntry
        AppLog.d(TAG, "Phone number cleared")
    }

    /**
     * Toggle phone number visibility (masked/visible)
     */
    fun togglePhoneVisibility() {
        _isPhoneVisible.value = !_isPhoneVisible.value
        AppLog.d(TAG, "Phone visibility toggled: ${_isPhoneVisible.value}")
    }

    /**
     * Get formatted phone number for display
     * Format: (123) 456-7890
     */
    fun getFormattedPhoneNumber(): String {
        val phone = _phoneNumber.value
        return when {
            phone.length <= 3 -> phone
            phone.length <= 6 -> "(${phone.substring(0, 3)}) ${phone.substring(3)}"
            else -> "(${phone.substring(0, 3)}) ${phone.substring(3, 6)}-${phone.substring(6)}"
        }
    }

    /**
     * Get masked phone number for display
     * Format: (***) ***-7890
     */
    fun getMaskedPhoneNumber(): String {
        val phone = _phoneNumber.value
        return when {
            phone.length < 7 -> "*".repeat(phone.length)
            else -> "(***) ***-${phone.substring(6)}"
        }
    }

    /**
     * Request OTP for the entered phone number
     */
    fun requestOtp() {
        val phone = _phoneNumber.value

        // Validate phone number length
        if (phone.length != PHONE_LENGTH) {
            _uiState.value = SMSUiState.InvalidPhoneNumber
            AppLog.w(TAG, "Invalid phone number length: ${phone.length} (expected $PHONE_LENGTH)")
            return
        }

        viewModelScope.launch {
            try {
                // Enter critical state to prevent inactivity timeout
                _isInCriticalState.value = true
                _uiState.value = SMSUiState.RequestingOtp
                AppLog.i(TAG, "Requesting OTP for phone: ${getMaskedPhoneNumber()}")

                // Check for mock mode
                if (phone == MOCK_PHONE) {
                    AppLog.w(TAG, "Mock phone number detected, triggering daily limit")
                    _uiState.value = SMSUiState.DailyLimitReached
                    return@launch
                }

                // Format phone with country code (+1 for US)
                val formattedPhone = "+1$phone"

                // Request OTP from repository
                val result = authRepository.requestOtp(formattedPhone)

                if (result.isSuccess) {
                    AppLog.i(TAG, "OTP requested successfully")
                    _uiState.value = SMSUiState.OtpRequestSuccess(
                        phoneNumber = phone,
                        isPhoneVisible = _isPhoneVisible.value
                    )
                } else {
                    val error = result.exceptionOrNull()
                    // Log technical error details for admin review
                    AppLog.e(TAG, "OTP request failed: ${error?.message}", error)
                    
                    // Categorize error and show user-friendly message
                    when {
                        error?.message?.contains("daily limit", ignoreCase = true) == true -> {
                            AppLog.w(TAG, "Daily limit reached for phone: ${getMaskedPhoneNumber()}")
                            _uiState.value = SMSUiState.DailyLimitReached
                            return@launch
                        }
                        error?.message?.contains("network", ignoreCase = true) == true -> {
                            AppLog.w(TAG, "Network error during OTP request")
                            _uiState.value = SMSUiState.Error(UserErrorMessages.NETWORK_ERROR)
                        }
                        error?.message?.contains("timeout", ignoreCase = true) == true -> {
                            AppLog.w(TAG, "Request timeout during OTP request")
                            _uiState.value = SMSUiState.Error(UserErrorMessages.NETWORK_ERROR)
                        }
                        else -> {
                            AppLog.e(TAG, "Unexpected OTP request error: ${error?.javaClass?.simpleName}")
                            _uiState.value = SMSUiState.Error(UserErrorMessages.GENERIC_ERROR)
                        }
                    }
                }

            } catch (e: Exception) {
                // Log technical error details for admin review
                AppLog.e(TAG, "Unexpected exception requesting OTP: ${e.javaClass.simpleName}", e)
                // Show generic user-friendly error
                _uiState.value = SMSUiState.Error(UserErrorMessages.GENERIC_ERROR)
            } finally {
                // Exit critical state
                _isInCriticalState.value = false
            }
        }
    }

    /**
     * Reset to phone entry state (used after errors)
     */
    fun resetToPhoneEntry() {
        _uiState.value = SMSUiState.PhoneEntry
        AppLog.d(TAG, "Reset to phone entry state")
    }
}

/**
 * UI States for SMS/Phone Entry
 */
sealed class SMSUiState {
    object PhoneEntry : SMSUiState()
    object InvalidPhoneNumber : SMSUiState()
    object RequestingOtp : SMSUiState()
    data class OtpRequestSuccess(
        val phoneNumber: String,
        val isPhoneVisible: Boolean
    ) : SMSUiState()
    object DailyLimitReached : SMSUiState()
    data class Error(val message: String) : SMSUiState()
}
