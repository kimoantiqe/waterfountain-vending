package com.waterfountainmachine.app.features.vending.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waterfountainmachine.app.auth.AuthenticationException
import com.waterfountainmachine.app.auth.IAuthenticationRepository
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.utils.PhoneNumberUtils
import com.waterfountainmachine.app.utils.UserErrorMessages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for SMS OTP Verification screen
 * Handles OTP input, verification logic, and timer management
 */
@HiltViewModel
class SMSVerifyViewModel @Inject constructor(
    private val authRepository: IAuthenticationRepository
) : ViewModel() {

    companion object {
        private const val TAG = "SMSVerifyViewModel"
        private const val MAX_OTP_LENGTH = 6
        private const val OTP_TIMEOUT_SECONDS = 120
        private const val MAX_RETRY_ATTEMPTS = 2 // Allow 2 retries (3 total attempts)
        private const val MIN_LOADING_DISPLAY_TIME_MS = 800L
    }

    // Phone number being verified
    private val _phoneNumber = MutableStateFlow("")
    val phoneNumber: StateFlow<String> = _phoneNumber.asStateFlow()

    // OTP code entered by user
    private val _otpCode = MutableStateFlow("")
    val otpCode: StateFlow<String> = _otpCode.asStateFlow()

    // UI State
    private val _uiState = MutableStateFlow<SMSVerifyUiState>(SMSVerifyUiState.EnteringOtp)
    val uiState: StateFlow<SMSVerifyUiState> = _uiState.asStateFlow()

    // OTP timer
    private val _otpTimeRemaining = MutableStateFlow(OTP_TIMEOUT_SECONDS)
    val otpTimeRemaining: StateFlow<Int> = _otpTimeRemaining.asStateFlow()

    // Failed attempts tracking
    private val _failedAttempts = MutableStateFlow(0)
    // Critical state for inactivity timer
    private val _isInCriticalState = MutableStateFlow(false)
    val isInCriticalState: StateFlow<Boolean> = _isInCriticalState.asStateFlow()

    // Timer job
    private var timerJob: Job? = null

    // Request tracking for debouncing
    private var lastVerifyTime = 0L

    /**
     * Initialize with phone number from previous screen
     */
    fun initialize(phone: String) {
        _phoneNumber.value = phone
        startOtpTimer()
        AppLog.d(TAG, "Initialized with phone: ***-***-${phone.takeLast(4)}")
    }

    /**
     * Add a digit to the OTP code
     */
    fun addDigit(digit: String) {
        if (_otpCode.value.length < MAX_OTP_LENGTH) {
            _otpCode.value += digit
            AppLog.d(TAG, "Added digit, OTP length: ${_otpCode.value.length}")
        }
    }

    /**
     * Delete last digit from OTP code
     */
    fun deleteDigit() {
        if (_otpCode.value.isNotEmpty()) {
            _otpCode.value = _otpCode.value.dropLast(1)
            AppLog.d(TAG, "Deleted digit, OTP length: ${_otpCode.value.length}")
        }
    }

    /**
     * Clear the entire OTP code
     */
    fun clearOtp() {
        _otpCode.value = ""
        AppLog.d(TAG, "OTP cleared")
    }

    /**
     * Verify the entered OTP code
     * Includes debouncing to prevent spam verifications
     */
    fun verifyOtp() {
        if (_otpCode.value.length != MAX_OTP_LENGTH) {
            AppLog.d(TAG, "Incomplete OTP: ${_otpCode.value.length} digits")
            _uiState.value = SMSVerifyUiState.IncompleteOtp
            return
        }

        // Check debounce - prevent rapid repeated verifications
        val now = System.currentTimeMillis()
        if (now - lastVerifyTime < 1000) {
            AppLog.d(TAG, "Verify request debounced")
            return
        }

        viewModelScope.launch {
            try {
                _isInCriticalState.value = true
                _uiState.value = SMSVerifyUiState.Verifying

                // Normalize phone to E.164 format
                val formattedPhone = PhoneNumberUtils.normalizePhoneNumber(_phoneNumber.value)
                AppLog.d(TAG, "Verifying OTP for phone: ${PhoneNumberUtils.maskPhoneForLogging(formattedPhone)}")

                // Add minimum display time for loading spinner
                val startTime = System.currentTimeMillis()

                // Verify OTP
                val result = authRepository.verifyOtp(formattedPhone, _otpCode.value)

                // Ensure minimum spinner visibility
                val elapsedTime = System.currentTimeMillis() - startTime
                val remainingTime = MIN_LOADING_DISPLAY_TIME_MS - elapsedTime
                if (remainingTime > 0) {
                    delay(remainingTime)
                }

                result.onSuccess { response ->
                    AppLog.i(TAG, "✅ OTP verification successful: ${response.message}")
                    _failedAttempts.value = 0
                    
                    // Check if user has reached daily vend limit
                    if (response.vendsRemainingToday <= 0) {
                        AppLog.w(TAG, "Daily vend limit reached (${response.vendsUsedToday}/${response.dailyVendLimit})")
                        _uiState.value = SMSVerifyUiState.DailyLimitReached
                        return@onSuccess
                    }
                    
                    _uiState.value = SMSVerifyUiState.VerificationSuccess
                }.onFailure { error ->
                    // Log technical error details for admin review
                    AppLog.e(TAG, "OTP verification failed: ${error.message}", error)
                    handleVerificationError(error)
                }
            } catch (e: Exception) {
                // Log technical error details for admin review
                AppLog.e(TAG, "Unexpected exception during verification: ${e.javaClass.simpleName}", e)
                // Show generic user-friendly error
                _uiState.value = SMSVerifyUiState.Error(UserErrorMessages.GENERIC_ERROR)
            } finally {
                _isInCriticalState.value = false
            }
        }
    }

    /**
     * Handle verification errors with retry logic
     */
    private fun handleVerificationError(error: Throwable) {
        val errorMessage = error.message ?: "Verification failed"
        AppLog.w(TAG, "OTP verification failed: $errorMessage (${error.javaClass.simpleName})", error)

        // Increment failed attempts
        _failedAttempts.value++
        AppLog.d(TAG, "Failed attempt ${_failedAttempts.value} of ${MAX_RETRY_ATTEMPTS + 1}")

        // Check if this is an invalid code error that can be retried
        val isInvalidCodeError = error is AuthenticationException.InvalidOtpError ||
                errorMessage.contains("invalid", ignoreCase = true) ||
                errorMessage.contains("incorrect", ignoreCase = true) ||
                errorMessage.contains("wrong", ignoreCase = true)

        if (isInvalidCodeError && _failedAttempts.value <= MAX_RETRY_ATTEMPTS) {
            // Show retry message and allow user to try again
            val attemptsRemaining = MAX_RETRY_ATTEMPTS + 1 - _failedAttempts.value
            AppLog.i(TAG, "Allowing retry. Attempts remaining: $attemptsRemaining")
            _uiState.value = SMSVerifyUiState.IncorrectOtp(attemptsRemaining)
        } else {
            // Max attempts reached or non-retryable error - show user-friendly error
            val errorMsg = when (error) {
                is AuthenticationException.CertificateError -> {
                    AppLog.e(TAG, "Certificate error during verification")
                    UserErrorMessages.GENERIC_ERROR
                }
                is AuthenticationException.ServerError -> {
                    AppLog.e(TAG, "Server error during verification")
                    UserErrorMessages.SERVICE_UNAVAILABLE
                }
                is AuthenticationException.NetworkError -> {
                    AppLog.e(TAG, "Network error during verification")
                    UserErrorMessages.NETWORK_ERROR
                }
                else -> {
                    if (_failedAttempts.value > MAX_RETRY_ATTEMPTS) {
                        AppLog.w(TAG, "Max OTP attempts exceeded (${_failedAttempts.value})")
                        UserErrorMessages.TOO_MANY_OTP_ATTEMPTS
                    } else {
                        AppLog.e(TAG, "Unexpected verification error: ${error.javaClass.simpleName}")
                        UserErrorMessages.GENERIC_ERROR
                    }
                }
            }
            _uiState.value = SMSVerifyUiState.Error(errorMsg)
        }
    }

    /**
     * Request a new OTP code
     */
    fun resendOtp() {
        viewModelScope.launch {
            try {
                _isInCriticalState.value = true
                _uiState.value = SMSVerifyUiState.ResendingOtp

                val formattedPhone = PhoneNumberUtils.normalizePhoneNumber(_phoneNumber.value)
                AppLog.i(TAG, "Resending OTP to: ${PhoneNumberUtils.maskPhoneForLogging(formattedPhone)}")

                val result = authRepository.requestOtp(formattedPhone)

                result.onSuccess { response ->
                    AppLog.i(TAG, "✅ OTP resent successfully: ${response.message}")
                    _otpCode.value = ""
                    _failedAttempts.value = 0
                    _otpTimeRemaining.value = OTP_TIMEOUT_SECONDS
                    startOtpTimer()
                    _uiState.value = SMSVerifyUiState.OtpResent
                }.onFailure { error ->
                    // Log technical error details for admin review
                    AppLog.e(TAG, "Failed to resend OTP: ${error.message}", error)
                    // Show generic user-friendly error
                    _uiState.value = SMSVerifyUiState.ResendError(UserErrorMessages.GENERIC_ERROR)
                }
            } finally {
                _isInCriticalState.value = false
            }
        }
    }

    /**
     * Start the OTP expiration timer
     */
    private fun startOtpTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (_otpTimeRemaining.value > 0) {
                delay(1000)
                _otpTimeRemaining.value--
            }
            AppLog.i(TAG, "OTP timer expired")
            _uiState.value = SMSVerifyUiState.OtpExpired
        }
    }

    /**
     * Reset UI state to default
     */
    fun resetUiState() {
        _uiState.value = SMSVerifyUiState.EnteringOtp
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        AppLog.d(TAG, "ViewModel cleared")
    }
}

/**
 * UI States for SMS Verification screen
 */
sealed class SMSVerifyUiState {
    data object EnteringOtp : SMSVerifyUiState()
    data object IncompleteOtp : SMSVerifyUiState()
    data object Verifying : SMSVerifyUiState()
    data object VerificationSuccess : SMSVerifyUiState()
    data class IncorrectOtp(val attemptsRemaining: Int) : SMSVerifyUiState()
    data class Error(val message: String) : SMSVerifyUiState()
    data object ResendingOtp : SMSVerifyUiState()
    data object OtpResent : SMSVerifyUiState()
    data class ResendError(val message: String) : SMSVerifyUiState()
    data object OtpExpired : SMSVerifyUiState()
    data object DailyLimitReached : SMSVerifyUiState()
}
