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
import com.waterfountainmachine.app.databinding.ActivitySmsVerifyBinding
import com.waterfountainmachine.app.hardware.WaterFountainManager
import com.waterfountainmachine.app.utils.FullScreenUtils
import com.waterfountainmachine.app.utils.AnimationUtils
import com.waterfountainmachine.app.utils.InactivityTimer
import com.waterfountainmachine.app.utils.AppLog
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SMSVerifyActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySmsVerifyBinding
    private var phoneNumber = ""
    private var otpCode = ""
    
    // Authentication repository
    private lateinit var authRepository: IAuthenticationRepository

    private lateinit var inactivityTimer: InactivityTimer
    private var questionMarkAnimator: AnimatorSet? = null

    // Timer for OTP resend - using coroutines instead of Handler to prevent memory leaks
    private var otpTimerJob: Job? = null
    private var otpTimeRemaining = OTP_TIMEOUT_SECONDS

    // Water fountain hardware manager
    private lateinit var waterFountainManager: WaterFountainManager
    
    // Verification state
    private var isVerifying = false
    private var failedAttempts = 0
    
    companion object {
        private const val TAG = "SMSVerifyActivity"
        private const val MAX_OTP_LENGTH = 6
        private const val OTP_TIMEOUT_SECONDS = 120
        private const val INACTIVITY_TIMEOUT_MS = 300_000L // 5 minutes
        private const val ANIMATION_DURATION_MS = 300L
        private const val MAX_FAILED_ATTEMPTS = 3
        private const val CORRECT_OTP = "111111" // Deprecated - now using AuthModule
        const val EXTRA_PHONE_NUMBER = "phoneNumber"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySmsVerifyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get phone number from intent
        phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: ""

        FullScreenUtils.setupFullScreen(window, binding.root)
        
        // Initialize authentication repository
        try {
            authRepository = AuthModule.getRepository()
            AppLog.d(TAG, "AuthRepository initialized")
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to initialize AuthRepository", e)
            showError("Authentication system not available")
            finish()
            return
        }
        
        initializeViews()
        setupKeypadListeners()
        setupQuestionMarkAnimation()
        setupModalFunctionality()
        
        inactivityTimer = InactivityTimer(INACTIVITY_TIMEOUT_MS) { returnToMainScreen() }
        inactivityTimer.start()
        
        // Initialize water fountain hardware
        setupHardware()
        
        // Setup initial UI
        setupVerificationUI()
    }

    private fun initializeViews() {
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

    private fun setupVerificationUI() {
        otpCode = ""
        
        // Show masked phone number in subtitle (only show last 4 digits)
        val maskedPhone = "••• •••-${phoneNumber.substring(6)}"
        binding.subtitleText.text = "We sent a code to $maskedPhone"
        
        // Initialize verify button
        binding.verifyOtpButton.isEnabled = false
        updateButtonDisabledState(binding.verifyOtpButton, false)
        
        // Clear OTP display
        updateOtpDisplay()
        
        // Start the timer
        startOtpTimer()
        
        // Simulate sending SMS (in real app, integrate with SMS service)
        simulateSendSMS()
    }

    private fun startOtpTimer() {
        // Cancel any existing timer
        otpTimerJob?.cancel()
        otpTimeRemaining = OTP_TIMEOUT_SECONDS // Reset to 2 minutes
        
        // Fade out resend button if visible, fade in timer
        if (binding.resendCodeButton.visibility == View.VISIBLE) {
            binding.resendCodeButton.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    binding.resendCodeButton.visibility = View.GONE
                    binding.resendCodeButton.alpha = 1f
                    
                    // Now fade in timer
                    binding.timerLayout.alpha = 0f
                    binding.timerLayout.visibility = View.VISIBLE
                    binding.timerLayout.animate()
                        .alpha(1f)
                        .setDuration(ANIMATION_DURATION_MS)
                        .start()
                }
                .start()
        } else {
            // Just show timer
            binding.timerLayout.alpha = 0f
            binding.timerLayout.visibility = View.VISIBLE
            binding.timerLayout.animate()
                .alpha(1f)
                .setDuration(ANIMATION_DURATION_MS)
                .start()
        }
        
        // Start coroutine-based timer to prevent memory leaks
        otpTimerJob = lifecycleScope.launch {
            while (otpTimeRemaining > 0) {
                // Update timer display
                val minutes = otpTimeRemaining / 60
                val seconds = otpTimeRemaining % 60
                binding.timerText.text = String.format("%d:%02d", minutes, seconds)
                
                delay(1000) // Wait 1 second
                otpTimeRemaining--
            }
            
            // Timer expired - fade out timer, fade in resend button
            binding.timerLayout.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    binding.timerLayout.visibility = View.GONE
                    binding.timerLayout.alpha = 1f
                    
                    // Now fade in resend button
                    binding.resendCodeButton.alpha = 0f
                    binding.resendCodeButton.visibility = View.VISIBLE
                    binding.resendCodeButton.animate()
                        .alpha(1f)
                        .setDuration(ANIMATION_DURATION_MS)
                        .start()
                }
                .start()
        }
    }

    private fun simulateSendSMS() {
        // In a real implementation, integrate with SMS service like Twilio
        // For demo purposes, we'll just show a simulated code
        AppLog.i(TAG, "Sending verification code to $phoneNumber")
    }

    private fun verifyAndProceed() {
        // Prevent spam clicking
        if (isVerifying) {
            AppLog.d(TAG, "Already verifying, ignoring click")
            return
        }
        
        if (otpCode.length != MAX_OTP_LENGTH) {
            AppLog.e(TAG, "Invalid OTP length: ${otpCode.length}")
            return
        }
        
        // Set loading state
        isVerifying = true
        setLoadingState(true)
        
        // Format phone with country code (+1 for US)
        val formattedPhone = "+1$phoneNumber"
        
        lifecycleScope.launch {
            try {
                AppLog.d(TAG, "Verifying OTP for phone: +1***-***-${phoneNumber.takeLast(4)}")
                
                // Verify OTP via repository
                val result = authRepository.verifyOtp(formattedPhone, otpCode)
                
                result.onSuccess { response ->
                    AppLog.i(TAG, "OTP verification successful: ${response.message}")
                    // SMS verification successful - navigate to vending animation
                    navigateToVendingAnimation()
                }.onFailure { error ->
                    val errorMessage = error.message ?: "Verification failed"
                    AppLog.w(TAG, "OTP verification failed: $errorMessage", error)
                    handleVerificationError(errorMessage)
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "Unexpected verification error", e)
                handleVerificationError("An unexpected error occurred")
            }
        }
    }
    
    private fun setLoadingState(loading: Boolean) {
        if (loading) {
            // Smoothly transition to loading state
            binding.verifyOtpButton.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction {
                    binding.verifyOtpButton.text = "Verifying..."
                    binding.verifyOtpButton.isEnabled = false
                    binding.verifyOtpButton.animate()
                        .alpha(0.7f)
                        .setDuration(200)
                        .start()
                }
                .start()
            
            // Smoothly dim and scale down keypad
            binding.keypadLayout.animate()
                .alpha(0.5f)
                .scaleX(0.98f)
                .scaleY(0.98f)
                .setDuration(300)
                .start()
            
            // Disable keypad
            setKeypadEnabled(false)
        } else {
            // Smoothly restore button
            binding.verifyOtpButton.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction {
                    binding.verifyOtpButton.text = "Verify"
                    
                    // Update button state based on OTP completion
                    val isComplete = otpCode.length == MAX_OTP_LENGTH
                    binding.verifyOtpButton.isEnabled = isComplete
                    
                    val targetAlpha = if (isComplete) 1.0f else 0.6f
                    binding.verifyOtpButton.animate()
                        .alpha(targetAlpha)
                        .setDuration(200)
                        .start()
                    
                    updateButtonDisabledState(binding.verifyOtpButton, isComplete)
                }
                .start()
            
            // Smoothly restore keypad (will be animated separately if error)
            if (failedAttempts < MAX_FAILED_ATTEMPTS) {
                setKeypadEnabled(true)
            }
        }
    }
    
    private fun setKeypadEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1.0f else 0.5f
        binding.btn0.isEnabled = enabled
        binding.btn1.isEnabled = enabled
        binding.btn2.isEnabled = enabled
        binding.btn3.isEnabled = enabled
        binding.btn4.isEnabled = enabled
        binding.btn5.isEnabled = enabled
        binding.btn6.isEnabled = enabled
        binding.btn7.isEnabled = enabled
        binding.btn8.isEnabled = enabled
        binding.btn9.isEnabled = enabled
        binding.btnBackspace.isEnabled = enabled
        binding.btnClear.isEnabled = enabled
        
        binding.btn0.alpha = alpha
        binding.btn1.alpha = alpha
        binding.btn2.alpha = alpha
        binding.btn3.alpha = alpha
        binding.btn4.alpha = alpha
        binding.btn5.alpha = alpha
        binding.btn6.alpha = alpha
        binding.btn7.alpha = alpha
        binding.btn8.alpha = alpha
        binding.btn9.alpha = alpha
        binding.btnBackspace.alpha = alpha
        binding.btnClear.alpha = alpha
    }
    
    private fun handleVerificationError(errorMessage: String = "Incorrect PIN") {
        isVerifying = false
        failedAttempts++
        
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            // Max attempts reached - show error and return to main screen
            showErrorAndReturnToMain(errorMessage)
        } else {
            // Show error and allow retry
            showVerificationError(errorMessage)
        }
    }
    
    private fun showVerificationError(errorMessage: String = "Incorrect PIN") {
        // Update subtitle to show error
        binding.subtitleText.text = errorMessage
        
        // Animate subtitle to red
        val originalColor = binding.subtitleText.currentTextColor
        binding.subtitleText.setTextColor(0xFFFF4444.toInt()) // Red color
        
        // Shake animation for OTP display
        val shake = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.shake)
        binding.otpDisplay.startAnimation(shake)
        
        // Smoothly fade out and slide up keypad during error
        animateKeypadError()
        
        lifecycleScope.launch {
            // Wait for shake animation
            delay(500)
            
            // Clear the OTP code
            otpCode = ""
            updateOtpDisplay()
            
            // Reset button state
            setLoadingState(false)
            
            // Fade subtitle back to original color after delay
            delay(2000)
            binding.subtitleText.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    binding.subtitleText.setTextColor(originalColor)
                    val maskedPhone = "••• •••-${phoneNumber.substring(6)}"
                    binding.subtitleText.text = "We sent a code to $maskedPhone"
                    binding.subtitleText.animate()
                        .alpha(0.7f)
                        .setDuration(200)
                        .start()
                }
                .start()
            
            // Smoothly fade in and slide down keypad after error clears
            delay(200)
            animateKeypadRestore()
        }
    }
    
    private fun showErrorAndReturnToMain(errorMessage: String = "Too many failed attempts") {
        // Update subtitle
        binding.subtitleText.text = errorMessage
        binding.subtitleText.setTextColor(0xFFFF4444.toInt()) // Red color
        
        // Shake animation for OTP display
        val shake = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.shake)
        binding.otpDisplay.startAnimation(shake)
        
        // Return to main screen after brief delay
        lifecycleScope.launch {
            delay(2000) // Show error message for 2 seconds
            returnToMainScreen()
        }
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

    private fun showErrorAndReturnToMain() {
        // Shake animation for OTP display
        val shake = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.shake)
        binding.otpDisplay.startAnimation(shake)
        
        // Immediately return to main screen without showing message
        lifecycleScope.launch {
            delay(500) // Just wait for shake animation
            returnToMainScreen()
        }
    }
    
    private fun animateKeypadError() {
        // Smoothly fade out and slightly scale down keypad
        binding.keypadLayout.animate()
            .alpha(0.4f)
            .scaleX(0.98f)
            .scaleY(0.98f)
            .translationY(10f)
            .setDuration(300)
            .start()
    }
    
    private fun animateKeypadRestore() {
        // Smoothly fade in and restore keypad
        binding.keypadLayout.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(400)
            .start()
    }
    
    /**
     * Navigate to vending animation screen after successful OTP verification
     */
    private fun navigateToVendingAnimation() {
        AppLog.i(TAG, "OTP verified - navigating to vending animation")
        
        // Create transition intent
        val intent = Intent(this, VendingAnimationActivity::class.java)
        intent.putExtra("phoneNumber", phoneNumber)

        // Use standard fade transition
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
    
    private fun setupHardware() {
        waterFountainManager = WaterFountainManager.getInstance(this)
        
        lifecycleScope.launch {
            try {
                if (waterFountainManager.initialize()) {
                    AppLog.i(TAG, "Hardware initialized successfully")
                } else {
                    AppLog.e(TAG, "Hardware initialization failed")
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "Hardware error", e)
            }
        }
    }

    private fun addDigit(digit: String) {
        if (otpCode.length < MAX_OTP_LENGTH) {
            otpCode += digit
            updateOtpDisplayWithAnimation()
            val isComplete = otpCode.length == MAX_OTP_LENGTH
            binding.verifyOtpButton.isEnabled = isComplete
            updateButtonDisabledState(binding.verifyOtpButton, isComplete)
        }
    }

    private fun removeLastDigit() {
        if (otpCode.isNotEmpty()) {
            // Animate disappear
            binding.otpDisplay.startAnimation(
                android.view.animation.AnimationUtils.loadAnimation(this, R.anim.number_disappear)
            )
            
            lifecycleScope.launch {
                delay(100)
                otpCode = otpCode.dropLast(1)
                updateOtpDisplay()
                val isComplete = otpCode.length == MAX_OTP_LENGTH
                binding.verifyOtpButton.isEnabled = isComplete
                updateButtonDisabledState(binding.verifyOtpButton, isComplete)
            }
        }
    }

    private fun clearInput() {
        otpCode = ""
        updateOtpDisplay()
        binding.verifyOtpButton.isEnabled = false
        updateButtonDisabledState(binding.verifyOtpButton, false)
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

    private fun updateOtpDisplay() {
        // Smooth transition when display updates - no masking for OTP
        val formatted = formatOtpCode(otpCode)
        binding.otpDisplay.animate()
            .alpha(0.7f)
            .setDuration(100)
            .withEndAction {
                binding.otpDisplay.text = formatted
                binding.otpDisplay.animate()
                    .alpha(1f)
                    .setDuration(150)
                    .start()
            }
            .start()
    }

    private fun updateOtpDisplayWithAnimation() {
        // No masking for OTP
        val formatted = formatOtpCode(otpCode)
        
        // Animate the text change
        binding.otpDisplay.startAnimation(
            android.view.animation.AnimationUtils.loadAnimation(this, R.anim.number_appear)
        )
        binding.otpDisplay.text = formatted
    }

    private fun formatOtpCode(code: String): String {
        // Smart formatting: just show the digits with spaces, no underscores
        if (code.isEmpty()) return ""
        
        val digits = code.toCharArray()
        val formatted = StringBuilder()

        for (i in digits.indices) {
            formatted.append(digits[i])
            if (i < digits.size - 1) formatted.append(" ")
        }

        return formatted.toString()
    }

    private fun setupKeypadListeners() {
        // Set up verify OTP button listener
        binding.verifyOtpButton.setOnClickListener {
            inactivityTimer.reset()
            performButtonAnimation(binding.verifyOtpButton) {
                if (otpCode.length == MAX_OTP_LENGTH) {
                    verifyAndProceed()
                }
            }
        }

        // Set up resend code button listener with proper animation
        binding.resendCodeButton.setOnClickListener {
            inactivityTimer.reset()
            performKeypadAnimation(binding.resendCodeButton) {
                resendVerificationCode()
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
        // Enhanced animation for main action buttons (Verify, Retry)
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

    private fun resendVerificationCode() {
        // Reset failed attempts on resend
        failedAttempts = 0
        
        // Clear the OTP field
        otpCode = ""
        updateOtpDisplay()
        binding.verifyOtpButton.isEnabled = false
        updateButtonDisabledState(binding.verifyOtpButton, false)
        
        // Reset subtitle if showing error
        val maskedPhone = "••• •••-${phoneNumber.substring(6)}"
        binding.subtitleText.text = "Sending new code..."
        binding.subtitleText.setTextColor(0xB3555555.toInt()) // Reset to original color with alpha
        binding.subtitleText.alpha = 0.7f

        // Format phone with country code (+1 for US)
        val formattedPhone = "+1$phoneNumber"
        
        lifecycleScope.launch {
            try {
                AppLog.d(TAG, "Resending OTP for phone: +1***-***-${phoneNumber.takeLast(4)}")
                
                // Request OTP via repository
                val result = authRepository.requestOtp(formattedPhone)
                
                result.onSuccess { response ->
                    AppLog.i(TAG, "OTP resent successfully: ${response.message}")
                    
                    // Update subtitle
                    binding.subtitleText.text = "We sent a code to $maskedPhone"
                    
                    // Restart the timer
                    startOtpTimer()
                }.onFailure { error ->
                    val errorMessage = error.message ?: "Failed to resend code"
                    AppLog.e(TAG, "OTP resend failed: $errorMessage", error)
                    binding.subtitleText.text = errorMessage
                    binding.subtitleText.setTextColor(0xFFFF4444.toInt()) // Red
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "Unexpected error resending OTP", e)
                binding.subtitleText.text = "Failed to resend code"
                binding.subtitleText.setTextColor(0xFFFF4444.toInt()) // Red
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        inactivityTimer.cleanup()
        otpTimerJob?.cancel()
        
        // Clean up animations
        questionMarkAnimator?.apply {
            removeAllListeners()
            cancel()
        }
        questionMarkAnimator = null
        
        // Clean up hardware resources
        if (::waterFountainManager.isInitialized) {
            lifecycleScope.launch {
                waterFountainManager.shutdown()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        FullScreenUtils.reapplyFullScreen(window, binding.root)
        inactivityTimer.reset()
    }

    override fun onPause() {
        super.onPause()
        inactivityTimer.stop()
        otpTimerJob?.cancel()
    }
}
