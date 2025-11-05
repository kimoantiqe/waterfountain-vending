package com.waterfountainmachine.app.activities

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.waterfountainmachine.app.R
import com.waterfountainmachine.app.auth.AuthModule
import com.waterfountainmachine.app.auth.IAuthenticationRepository
import com.waterfountainmachine.app.databinding.ActivitySmsBinding
import com.waterfountainmachine.app.hardware.WaterFountainManager
import com.waterfountainmachine.app.utils.FullScreenUtils
import com.waterfountainmachine.app.utils.AnimationUtils
import com.waterfountainmachine.app.utils.InactivityTimer
import com.waterfountainmachine.app.utils.AppLog
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

class SMSActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySmsBinding
    private var phoneNumber = ""
    
    // Authentication repository
    private lateinit var authRepository: IAuthenticationRepository
    
    // Phone number masking
    private var isPhoneNumberVisible = false

    private lateinit var inactivityTimer: InactivityTimer
    private var questionMarkAnimator: AnimatorSet? = null
    
    // Loading state for API calls
    private var isLoading = false
    
    companion object {
        private const val TAG = "SMSActivity"
        private const val MAX_PHONE_LENGTH = 10
        private const val INACTIVITY_TIMEOUT_MS = 60_000L // 1 minute
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySmsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        FullScreenUtils.setupFullScreen(window, binding.root)
        
        // Initialize authentication repository
        try {
            authRepository = AuthModule.getRepository()
            AppLog.d(TAG, "AuthRepository initialized")
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to initialize AuthRepository", e)
            showError("Authentication system not available")
            return
        }
        
        initializeViews()
        setupKeypadListeners()
        setupPhoneNumberStep()
        setupQuestionMarkAnimation()
        setupModalFunctionality()
        
        inactivityTimer = InactivityTimer(INACTIVITY_TIMEOUT_MS) { returnToMainScreen() }
        inactivityTimer.start()
    }

    private fun initializeViews() {
        // Views are now accessed through binding
        setupBackButton()
    }

    private fun setupBackButton() {
        binding.backButton.setOnClickListener {
            returnToMainScreen()
        }
    }

    private fun returnToMainScreen() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun setupQuestionMarkAnimation() {
        questionMarkAnimator = AnimationUtils.setupQuestionMarkAnimation(
            button = binding.questionMarkButton,
            icon = binding.questionMarkIcon,
            rootView = binding.root
        )
    }

    private fun setupModalFunctionality() {
        // Question mark button click
        binding.questionMarkButton.setOnClickListener {
            inactivityTimer.reset()
            showModal()
        }

        // Close modal button click
        binding.closeModalButton.setOnClickListener {
            hideModal()
        }

        // Click outside modal to close
        binding.modalOverlay.setOnClickListener { view ->
            if (view == binding.modalOverlay) {
                hideModal()
            }
        }

        // Prevent modal content clicks from closing modal
        binding.modalContent.setOnClickListener {
            // Do nothing - prevent click from bubbling up
        }
    }

    private fun showModal() {
        binding.modalOverlay.visibility = View.VISIBLE
        AnimationUtils.showModalAnimation(binding.modalContent)
    }

    private fun hideModal() {
        AnimationUtils.hideModalAnimation(binding.modalContent) {
            binding.modalOverlay.visibility = View.GONE
        }
    }

    private fun setupPhoneNumberStep() {
        phoneNumber = ""
        
        // Reset visibility state
        isPhoneNumberVisible = false
        // Show toggle button on phone entry screen
        binding.togglePhoneVisibility.visibility = View.VISIBLE
        updateToggleIcon()
        
        // Initialize button
        binding.verifyButton.visibility = View.VISIBLE
        binding.verifyButton.isEnabled = false
        binding.verifyButton.text = "Send Code"
        updateButtonDisabledState(binding.verifyButton, false)
        binding.verifyButton.alpha = 1f

        // Hide OTP-related UI
        binding.otpButtonsLayout.visibility = View.GONE
        binding.timerLayout.visibility = View.GONE
        binding.resendCodeButton.visibility = View.GONE

        updatePhoneDisplay()
        
        // Setup toggle phone visibility button
        binding.togglePhoneVisibility.setOnClickListener {
            inactivityTimer.reset()
            isPhoneNumberVisible = !isPhoneNumberVisible
            updatePhoneDisplay()
            updateToggleIcon()
        }
    }

    private fun sendCodeAndNavigate() {
        if (phoneNumber.length == MAX_PHONE_LENGTH && !isLoading) {
            // Show loading state
            showLoading()
            
            // Format phone with country code (+1 for US)
            val formattedPhone = "+1$phoneNumber"
            
            lifecycleScope.launch {
                try {
                    AppLog.d(TAG, "Requesting OTP for phone: +1***-***-${phoneNumber.takeLast(4)}")
                    
                    // Request OTP via repository
                    val result = authRepository.requestOtp(formattedPhone)
                    
                    result.onSuccess { response ->
                        hideLoading()
                        AppLog.i(TAG, "OTP requested successfully: ${response.message}")
                        
                        // Navigate to verification activity
                        val intent = Intent(this@SMSActivity, SMSVerifyActivity::class.java)
                        intent.putExtra(SMSVerifyActivity.EXTRA_PHONE_NUMBER, phoneNumber)
                        startActivity(intent)
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                        // Don't finish() here - let user come back if needed
                    }.onFailure { error ->
                        hideLoading()
                        val errorMessage = error.message ?: "Failed to send verification code"
                        AppLog.e(TAG, "OTP request failed: $errorMessage", error)
                        showError(errorMessage)
                    }
                } catch (e: Exception) {
                    hideLoading()
                    AppLog.e(TAG, "Unexpected error requesting OTP", e)
                    showError("An unexpected error occurred. Please try again.")
                }
            }
        }
    }
    
    /**
     * Show loading state (disable inputs, show progress)
     */
    private fun showLoading() {
        isLoading = true
        binding.verifyButton.isEnabled = false
        binding.verifyButton.text = "Sending..."
        // Disable keypad during loading
        setKeypadEnabled(false)
        AppLog.d(TAG, "Loading state: enabled")
    }
    
    /**
     * Hide loading state (enable inputs, hide progress)
     */
    private fun hideLoading() {
        isLoading = false
        binding.verifyButton.text = "Send Code"
        val isComplete = phoneNumber.length == MAX_PHONE_LENGTH
        binding.verifyButton.isEnabled = isComplete
        updateButtonDisabledState(binding.verifyButton, isComplete)
        // Re-enable keypad
        setKeypadEnabled(true)
        AppLog.d(TAG, "Loading state: disabled")
    }
    
    /**
     * Enable or disable all keypad buttons
     */
    private fun setKeypadEnabled(enabled: Boolean) {
        // This will be implemented when we add keypad listeners
        // For now, just log
        AppLog.d(TAG, "Keypad enabled: $enabled")
    }
    
    /**
     * Show error message to user
     */
    private fun showError(message: String) {
        // TODO: Show a proper error dialog or toast
        // For now, use a toast
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        AppLog.e(TAG, "Error shown to user: $message")
    }

    private fun addDigit(digit: String) {
        if (phoneNumber.length < MAX_PHONE_LENGTH) {
            phoneNumber += digit
            updatePhoneDisplayWithAnimation()
            val isComplete = phoneNumber.length == MAX_PHONE_LENGTH
            binding.verifyButton.isEnabled = isComplete
            updateButtonDisabledState(binding.verifyButton, isComplete)
        }
    }

    private fun removeLastDigit() {
        if (phoneNumber.isNotEmpty()) {
            // Animate disappear
            binding.phoneDisplay.startAnimation(
                android.view.animation.AnimationUtils.loadAnimation(this, R.anim.number_disappear)
            )
            
            lifecycleScope.launch {
                delay(100)
                phoneNumber = phoneNumber.dropLast(1)
                updatePhoneDisplay()
                val isComplete = phoneNumber.length == MAX_PHONE_LENGTH
                binding.verifyButton.isEnabled = isComplete
                updateButtonDisabledState(binding.verifyButton, isComplete)
            }
        }
    }

    private fun clearInput() {
        phoneNumber = ""
        updatePhoneDisplay()
        binding.verifyButton.isEnabled = false
        updateButtonDisabledState(binding.verifyButton, false)
    }
    
    /**
     * Update button appearance to make disabled state very obvious with smooth fade
     */
    private fun updateButtonDisabledState(button: androidx.appcompat.widget.AppCompatButton, isEnabled: Boolean) {
        // Smooth alpha transition
        button.animate()
            .alpha(if (isEnabled) 1.0f else 0.6f)
            .setDuration(200)
            .start()
        
        // Smooth color transition
        val startColor = button.currentTextColor
        val endColor = if (isEnabled) {
            0xFF888888.toInt()  // Lighter gray when enabled (more visible on glass background)
        } else {
            0xFF555555.toInt()  // Dark gray when disabled (less prominent)
        }
        
        // Animate color change
        val colorAnimator = android.animation.ValueAnimator.ofArgb(startColor, endColor)
        colorAnimator.duration = 200
        colorAnimator.addUpdateListener { animator ->
            button.setTextColor(animator.animatedValue as Int)
        }
        colorAnimator.start()
    }

    private fun updatePhoneDisplay() {
        val formatted = if (isPhoneNumberVisible) {
            formatSmartPhoneNumber(phoneNumber)
        } else {
            maskPhoneNumber(phoneNumber)
        }
        binding.phoneDisplay.text = formatted
    }

    private fun updatePhoneDisplayWithAnimation() {
        val formatted = if (isPhoneNumberVisible) {
            formatSmartPhoneNumber(phoneNumber)
        } else {
            maskPhoneNumber(phoneNumber)
        }
        
        // Animate the text change
        binding.phoneDisplay.startAnimation(
            android.view.animation.AnimationUtils.loadAnimation(this, R.anim.number_appear)
        )
        binding.phoneDisplay.text = formatted
    }
    
    private fun maskPhoneNumber(number: String): String {
        // Show only last 2 digits, mask the rest with dots
        if (number.isEmpty()) return ""
        
        return when (number.length) {
            0 -> ""
            1, 2 -> number  // Show all if less than or equal to 2
            3 -> "• ${number.substring(1)}"  // • XX (show last 2)
            4 -> "(••) ${number.substring(2)}"  // (••) XX (show last 2)
            5 -> "(•••) ${number.substring(3)}"  // (•••) XX (show last 2)
            6 -> "(•••) •${number.substring(4)}"  // (•••) •XX (show last 2)
            7 -> "(•••) ••${number.substring(5)}"  // (•••) ••XX (show last 2)
            8 -> "(•••) •••-${number.substring(6)}"  // (•••) •••-XX (show last 2)
            9 -> "(•••) •••-•${number.substring(7)}"  // (•••) •••-•XX (show last 2)
            10 -> "(•••) •••-••${number.substring(8)}"  // (•••) •••-••XX (show last 2)
            else -> number
        }
    }
    
    private fun updateToggleIcon() {
        // Update alpha to indicate state (more visible when showing numbers)
        binding.togglePhoneVisibility.alpha = if (isPhoneNumberVisible) 1.0f else 0.7f
    }

    private fun formatSmartPhoneNumber(number: String): String {
        // Smart formatting: only show formatting symbols when they make sense
        return when (number.length) {
            0 -> ""
            1, 2, 3 -> number  // Just show digits, no parentheses yet
            4, 5, 6 -> "(${number.substring(0, 3)}) ${number.substring(3)}"  // Add parentheses and space
            7, 8, 9, 10 -> "(${number.substring(0, 3)}) ${number.substring(3, 6)}-${number.substring(6)}"  // Full format
            else -> number
        }
    }

    private fun setupKeypadListeners() {
        // Set up send code button listener
        binding.verifyButton.setOnClickListener {
            inactivityTimer.reset()
            performButtonAnimation(binding.verifyButton) {
                if (phoneNumber.length == MAX_PHONE_LENGTH) {
                    sendCodeAndNavigate()
                }
            }
        }

        // Set up number button listeners with feedback animations
        binding.btn0.setOnClickListener { inactivityTimer.reset(); performKeypadAnimation(binding.btn0) { addDigit("0") } }
        binding.btn1.setOnClickListener { inactivityTimer.reset(); performKeypadAnimation(binding.btn1) { addDigit("1") } }
        binding.btn2.setOnClickListener { inactivityTimer.reset(); performKeypadAnimation(binding.btn2) { addDigit("2") } }
        binding.btn3.setOnClickListener { inactivityTimer.reset(); performKeypadAnimation(binding.btn3) { addDigit("3") } }
        binding.btn4.setOnClickListener { inactivityTimer.reset(); performKeypadAnimation(binding.btn4) { addDigit("4") } }
        binding.btn5.setOnClickListener { inactivityTimer.reset(); performKeypadAnimation(binding.btn5) { addDigit("5") } }
        binding.btn6.setOnClickListener { inactivityTimer.reset(); performKeypadAnimation(binding.btn6) { addDigit("6") } }
        binding.btn7.setOnClickListener { inactivityTimer.reset(); performKeypadAnimation(binding.btn7) { addDigit("7") } }
        binding.btn8.setOnClickListener { inactivityTimer.reset(); performKeypadAnimation(binding.btn8) { addDigit("8") } }
        binding.btn9.setOnClickListener { inactivityTimer.reset(); performKeypadAnimation(binding.btn9) { addDigit("9") } }

        // Set up control button listeners with feedback
        binding.btnBackspace.setOnClickListener { inactivityTimer.reset(); performKeypadAnimation(binding.btnBackspace) { removeLastDigit() } }
        binding.btnClear.setOnClickListener { inactivityTimer.reset(); performKeypadAnimation(binding.btnClear) { clearInput() } }
    }

    private fun performButtonAnimation(button: View, onComplete: () -> Unit) {
        // Enhanced animation for main action buttons (Send Code, Verify, Retry)
        val scaleDownX = ObjectAnimator.ofFloat(button, "scaleX", 1f, 0.92f)
        val scaleDownY = ObjectAnimator.ofFloat(button, "scaleY", 1f, 0.92f)
        val scaleUpX = ObjectAnimator.ofFloat(button, "scaleX", 0.92f, 1.05f)
        val scaleUpY = ObjectAnimator.ofFloat(button, "scaleY", 0.92f, 1.05f)
        val scaleNormalX = ObjectAnimator.ofFloat(button, "scaleX", 1.05f, 1f)
        val scaleNormalY = ObjectAnimator.ofFloat(button, "scaleY", 1.05f, 1f)

        val pressSet = AnimatorSet().apply {
            play(scaleDownX).with(scaleDownY)
            duration = 120
            interpolator = AccelerateDecelerateInterpolator()
        }

        val bounceSet = AnimatorSet().apply {
            play(scaleUpX).with(scaleUpY)
            duration = 180
            interpolator = AccelerateDecelerateInterpolator()
        }

        val normalizeSet = AnimatorSet().apply {
            play(scaleNormalX).with(scaleNormalY)
            duration = 120
            interpolator = AccelerateDecelerateInterpolator()
        }

        val fullAnimation = AnimatorSet().apply {
            playSequentially(pressSet, bounceSet, normalizeSet)
        }

        fullAnimation.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                onComplete()
            }
        })

        fullAnimation.start()
    }

    private fun performKeypadAnimation(button: View, onComplete: () -> Unit) {
        // Quick, responsive animation for keypad buttons
        val scaleDown = ObjectAnimator.ofFloat(button, "scaleX", 1f, 0.95f)
        val scaleDownY = ObjectAnimator.ofFloat(button, "scaleY", 1f, 0.95f)
        val scaleUp = ObjectAnimator.ofFloat(button, "scaleX", 0.95f, 1f)
        val scaleUpY = ObjectAnimator.ofFloat(button, "scaleY", 0.95f, 1f)

        val pressSet = AnimatorSet().apply {
            play(scaleDown).with(scaleDownY)
            duration = 80
            interpolator = AccelerateDecelerateInterpolator()
        }

        val releaseSet = AnimatorSet().apply {
            play(scaleUp).with(scaleUpY)
            duration = 100
            interpolator = AccelerateDecelerateInterpolator()
        }

        val fullAnimation = AnimatorSet().apply {
            playSequentially(pressSet, releaseSet)
        }

        fullAnimation.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                onComplete()
            }
        })

        fullAnimation.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        inactivityTimer.cleanup()
        
        // Clean up animations
        questionMarkAnimator?.apply {
            removeAllListeners()
            cancel()
        }
        questionMarkAnimator = null
    }

    override fun onResume() {
        super.onResume()
        FullScreenUtils.reapplyFullScreen(window, binding.root)
        inactivityTimer.reset()
    }

    override fun onPause() {
        super.onPause()
        inactivityTimer.stop()
    }
}
