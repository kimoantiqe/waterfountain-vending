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
import com.waterfountainmachine.app.utils.SoundManager
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
    private lateinit var soundManager: SoundManager
    
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
        
        // Initialize sound manager
        soundManager = SoundManager(this)
        soundManager.loadSound(R.raw.click)
        soundManager.loadSound(R.raw.correct)
        
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
            soundManager.playSound(R.raw.click, 0.6f)
            inactivityTimer.reset()
            showModal()
        }

        // Close modal button click
        binding.closeModalButton.setOnClickListener {
            soundManager.playSound(R.raw.click, 0.6f)
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
        
        // Reset visibility state - default to VISIBLE
        isPhoneNumberVisible = true
        // Show toggle button on phone entry screen
        binding.togglePhoneVisibility.visibility = View.VISIBLE
        updateToggleIcon()
        
        // Initialize button - keep it ENABLED so it's always clickable
        binding.verifyButton.visibility = View.VISIBLE
        binding.verifyButton.isEnabled = true  // Keep enabled for click feedback
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
            soundManager.playSound(R.raw.click, 0.5f)
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
                    
                    // Start timer to ensure minimum spinner display time
                    val startTime = System.currentTimeMillis()
                    val minDisplayTime = 800L // Minimum 800ms to see spinner
                    
                    // Request OTP via repository
                    val result = authRepository.requestOtp(formattedPhone)
                    
                    // Calculate remaining time to show spinner
                    val elapsedTime = System.currentTimeMillis() - startTime
                    val remainingTime = minDisplayTime - elapsedTime
                    if (remainingTime > 0) {
                        delay(remainingTime)
                    }
                    
                    result.onSuccess { response ->
                        hideLoading()
                        AppLog.i(TAG, "OTP requested successfully: ${response.message}")
                        
                        // Play success sound
                        soundManager.playSound(R.raw.correct, 0.7f)
                        
                        // Navigate to verification activity
                        val intent = Intent(this@SMSActivity, SMSVerifyActivity::class.java)
                        intent.putExtra(SMSVerifyActivity.EXTRA_PHONE_NUMBER, phoneNumber)
                        intent.putExtra(SMSVerifyActivity.EXTRA_PHONE_VISIBILITY, isPhoneNumberVisible)
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
        
        // Disable keypad during loading
        setKeypadEnabled(false)
        
        // Fade out button
        binding.verifyButton.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                // Show spinner and fade it in (overlaid on button)
                binding.loadingSpinner.visibility = View.VISIBLE
                binding.loadingSpinner.alpha = 0f
                binding.loadingSpinner.animate()
                    .alpha(1f)
                    .setDuration(300)
                    .start()
            }
            .start()
        
        AppLog.d(TAG, "Loading state: enabled")
    }
    
    /**
     * Hide loading state (enable inputs, hide progress)
     */
    private fun hideLoading() {
        isLoading = false
        
        // Fade out and hide spinner
        binding.loadingSpinner.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                binding.loadingSpinner.visibility = View.GONE
                
                // Fade button back in
                binding.verifyButton.animate()
                    .alpha(1f)
                    .setDuration(300)
                    .start()
            }
            .start()
        
        val isComplete = phoneNumber.length == MAX_PHONE_LENGTH
        // Update button visual state after fade in
        binding.verifyButton.postDelayed({
            updateButtonDisabledState(binding.verifyButton, isComplete)
        }, 300)
        
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
            // Don't change isEnabled, just visual state
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
                // Don't change isEnabled, just visual state
                updateButtonDisabledState(binding.verifyButton, isComplete)
            }
        }
    }

    private fun clearInput() {
        phoneNumber = ""
        updatePhoneDisplay()
        // Don't change isEnabled, just visual state
        updateButtonDisabledState(binding.verifyButton, false)
    }
    
    /**
     * Update button appearance to make disabled state very obvious with smooth fade
     */
    private fun updateButtonDisabledState(button: androidx.appcompat.widget.AppCompatButton, isEnabled: Boolean) {
        // Smooth alpha transition - more dramatic difference
        button.animate()
            .alpha(if (isEnabled) 1.0f else 0.4f)  // Changed from 0.6f to 0.4f for darker appearance
            .setDuration(200)
            .start()
        
        // Smooth color transition
        val startColor = button.currentTextColor
        val endColor = if (isEnabled) {
            0xFF888888.toInt()  // Lighter gray when enabled (more visible on glass background)
        } else {
            0xFF333333.toInt()  // Much darker gray when disabled (changed from 0xFF555555)
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
        // Update icon and alpha based on visibility state
        // When visible, show hide icon (eye-off) at full opacity
        // When hidden, show show icon (eye) at reduced opacity
        val iconRes = if (isPhoneNumberVisible) {
            R.drawable.ic_eye_off // Hide icon when numbers are visible
        } else {
            R.drawable.ic_eye // Show icon when numbers are hidden
        }
        binding.togglePhoneVisibility.setImageResource(iconRes)
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
        // Set up send code button listener - show hint if incomplete
        binding.verifyButton.setOnClickListener {
            inactivityTimer.reset()
            
            if (phoneNumber.length == MAX_PHONE_LENGTH) {
                performButtonAnimation(binding.verifyButton) {
                    sendCodeAndNavigate()
                }
            } else {
                // Show hint that number is incomplete
                showIncompleteNumberHint()
            }
        }

        // Set up number button listeners with feedback animations and sound
        binding.btn0.setOnClickListener { inactivityTimer.reset(); soundManager.playSound(R.raw.click, 0.5f); performKeypadAnimation(binding.btn0) { addDigit("0") } }
        binding.btn1.setOnClickListener { inactivityTimer.reset(); soundManager.playSound(R.raw.click, 0.5f); performKeypadAnimation(binding.btn1) { addDigit("1") } }
        binding.btn2.setOnClickListener { inactivityTimer.reset(); soundManager.playSound(R.raw.click, 0.5f); performKeypadAnimation(binding.btn2) { addDigit("2") } }
        binding.btn3.setOnClickListener { inactivityTimer.reset(); soundManager.playSound(R.raw.click, 0.5f); performKeypadAnimation(binding.btn3) { addDigit("3") } }
        binding.btn4.setOnClickListener { inactivityTimer.reset(); soundManager.playSound(R.raw.click, 0.5f); performKeypadAnimation(binding.btn4) { addDigit("4") } }
        binding.btn5.setOnClickListener { inactivityTimer.reset(); soundManager.playSound(R.raw.click, 0.5f); performKeypadAnimation(binding.btn5) { addDigit("5") } }
        binding.btn6.setOnClickListener { inactivityTimer.reset(); soundManager.playSound(R.raw.click, 0.5f); performKeypadAnimation(binding.btn6) { addDigit("6") } }
        binding.btn7.setOnClickListener { inactivityTimer.reset(); soundManager.playSound(R.raw.click, 0.5f); performKeypadAnimation(binding.btn7) { addDigit("7") } }
        binding.btn8.setOnClickListener { inactivityTimer.reset(); soundManager.playSound(R.raw.click, 0.5f); performKeypadAnimation(binding.btn8) { addDigit("8") } }
        binding.btn9.setOnClickListener { inactivityTimer.reset(); soundManager.playSound(R.raw.click, 0.5f); performKeypadAnimation(binding.btn9) { addDigit("9") } }

        // Set up control button listeners with feedback
        binding.btnBackspace.setOnClickListener { inactivityTimer.reset(); soundManager.playSound(R.raw.click, 0.5f); performKeypadAnimation(binding.btnBackspace) { removeLastDigit() } }
        binding.btnClear.setOnClickListener { inactivityTimer.reset(); soundManager.playSound(R.raw.click, 0.5f); performKeypadAnimation(binding.btnClear) { clearInput() } }
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

    private fun showIncompleteNumberHint() {
        // Fade out current subtitle text
        binding.subtitleText.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                // Change to error message
                binding.subtitleText.text = "Please enter a 10-digit phone number"
                binding.subtitleText.setTextColor(0xFFFF6B6B.toInt()) // Red color
                
                // Fade in error message
                binding.subtitleText.animate()
                    .alpha(0.9f)
                    .setDuration(300)
                    .start()
                
                // Auto-restore after 2.5 seconds
                binding.subtitleText.postDelayed({
                    binding.subtitleText.animate()
                        .alpha(0f)
                        .setDuration(200)
                        .withEndAction {
                            // Restore original message and color
                            binding.subtitleText.text = "We'll send you a verification code"
                            binding.subtitleText.setTextColor(0xFF555555.toInt()) // Original gray
                            
                            // Fade back in
                            binding.subtitleText.animate()
                                .alpha(0.7f)
                                .setDuration(300)
                                .start()
                        }
                        .start()
                }, 2500)
            }
            .start()
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
        
        // Release sound resources
        soundManager.release()
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
