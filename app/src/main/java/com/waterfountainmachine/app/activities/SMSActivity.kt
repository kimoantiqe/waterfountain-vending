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
    private var currentStep = 1
    private var phoneNumber = ""
    private var otpCode = ""
    
    // Phone number masking
    private var isPhoneNumberVisible = false
    private var isOtpVisible = false

    private lateinit var inactivityTimer: InactivityTimer
    private var questionMarkAnimator: AnimatorSet? = null

    // Timer for OTP resend - using coroutines instead of Handler to prevent memory leaks
    private var otpTimerJob: Job? = null
    private var otpTimeRemaining = OTP_TIMEOUT_SECONDS

    // Water fountain hardware manager
    private lateinit var waterFountainManager: WaterFountainManager
    
    companion object {
        private const val TAG = "SMSActivity"
        private const val MAX_PHONE_LENGTH = 10
        private const val MAX_OTP_LENGTH = 6
        private const val OTP_TIMEOUT_SECONDS = 120
        private const val INACTIVITY_TIMEOUT_MS = 360_000L // 6 minutes
        private const val ANIMATION_DURATION_MS = 300L
        private const val SHAKE_ANIMATION_DURATION_MS = 500L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySmsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        FullScreenUtils.setupFullScreen(window, binding.root)
        initializeViews()
        setupKeypadListeners()
        setupPhoneNumberStep()
        setupQuestionMarkAnimation()
        setupModalFunctionality()
        
        inactivityTimer = InactivityTimer(INACTIVITY_TIMEOUT_MS) { finish() }
        inactivityTimer.start()
        
        // Initialize water fountain hardware
        setupHardware()
    }

    private fun initializeViews() {
        // Views are now accessed through binding
        setupBackButton()
    }

    private fun setupBackButton() {
        binding.backButton.setOnClickListener {
            returnToVendingScreen()
        }
    }

    private fun returnToVendingScreen() {
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
        currentStep = 1
        phoneNumber = ""
        
        // Reset visibility state
        isPhoneNumberVisible = false
        // Show toggle button on phone entry screen
        binding.togglePhoneVisibility.visibility = View.VISIBLE
        updateToggleIcon()
        
        // Smooth fade transition for text changes
        binding.titleText.animate()
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                binding.titleText.text = getString(R.string.enter_phone_number)
                binding.titleText.animate().alpha(1f).setDuration(300).start()
            }
            .start()
        
        binding.subtitleText.animate()
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                binding.subtitleText.text = getString(R.string.verification_code_message)
                binding.subtitleText.animate().alpha(1f).setDuration(300).start()
            }
            .start()

        // Smooth transition for buttons
        if (binding.otpButtonsLayout.visibility == View.VISIBLE) {
            binding.otpButtonsLayout.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    binding.otpButtonsLayout.visibility = View.GONE
                    
                    // Fade in Send Code button
                    binding.verifyButton.alpha = 0f
                    binding.verifyButton.visibility = View.VISIBLE
                    binding.verifyButton.isEnabled = false
                    binding.verifyButton.text = "Send Code"
                    binding.verifyButton.animate()
                        .alpha(1f)
                        .setDuration(300)
                        .start()
                }
                .start()
        } else {
            binding.verifyButton.visibility = View.VISIBLE
            binding.verifyButton.isEnabled = false
            binding.verifyButton.text = "Send Code"
            binding.verifyButton.alpha = 1f
        }

        updatePhoneDisplay()
        
        // Setup toggle phone visibility button
        binding.togglePhoneVisibility.setOnClickListener {
            inactivityTimer.reset()
            if (currentStep == 1) {
                isPhoneNumberVisible = !isPhoneNumberVisible
                updatePhoneDisplay()
                updateToggleIcon()
            }
        }
    }

    private fun setupOTPStep() {
        currentStep = 2
        otpCode = ""
        
        // Smoothly fade out phone entry button
        binding.verifyButton.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                binding.verifyButton.visibility = View.GONE
                
                // Fade out title and subtitle for content change
                val titleFade = binding.titleText.animate()
                    .alpha(0f)
                    .setDuration(150)
                    
                val subtitleFade = binding.subtitleText.animate()
                    .alpha(0f)
                    .setDuration(150)
                    
                // Wait for both to fade out
                subtitleFade.withEndAction {
                    // Update content
                    binding.titleText.text = "Enter verification code"
                    // Show masked phone number in subtitle (only show last 4 digits)
                    val maskedPhone = "••• •••-${phoneNumber.substring(6)}"
                    binding.subtitleText.text = "We sent a code to $maskedPhone"
                    
                    // Reset visibility state for OTP - no masking for OTP input
                    isOtpVisible = true
                    // Hide the toggle button on verification screen
                    binding.togglePhoneVisibility.visibility = View.GONE
                    
                    // Prepare OTP UI (hidden initially)
                    binding.otpButtonsLayout.alpha = 0f
                    binding.otpButtonsLayout.visibility = View.VISIBLE
                    binding.verifyOtpButton.isEnabled = false
                    
                    // Ensure resend button is hidden initially
                    binding.resendCodeButton.visibility = View.GONE
                    binding.resendCodeButton.alpha = 1f
                    
                    // Clear phone display for OTP entry
                    updateOtpDisplay()
                    
                    // Smooth cascading fade-in animation
                    binding.titleText.animate()
                        .alpha(1f)
                        .setDuration(400)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                    
                    binding.subtitleText.animate()
                        .alpha(1f)
                        .setDuration(400)
                        .setStartDelay(100)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                    
                    // Fade in OTP verify button with slight delay
                    binding.otpButtonsLayout.animate()
                        .alpha(1f)
                        .setDuration(400)
                        .setStartDelay(200)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                    
                    // Show and start the timer with fade (after other elements)
                    lifecycleScope.launch {
                        delay(ANIMATION_DURATION_MS)
                        startOtpTimer()
                    }
                    
                    // Simulate sending SMS (in real app, integrate with SMS service)
                    simulateSendSMS()
                }
                
                titleFade.start()
                subtitleFade.start()
            }
            .start()
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
        // val demoCode = "123456" // Commented out to avoid unused variable warning

        // Auto-fill for demo (remove in production)
        // Removed Handler - would use coroutines if needed:
        // lifecycleScope.launch {
        //     delay(2000)
        //     otpCode = demoCode
        //     updateOtpDisplay()
        //     binding.verifyOtpButton.isEnabled = true
        // }
    }

    private fun verifyAndProceed() {
        // In real implementation, verify OTP with SMS service
        // For demo, we'll accept any 6-digit code

        if (otpCode.length == MAX_OTP_LENGTH) {
            // SMS verification successful - now dispense water
            dispenseWaterAfterVerification()
        }
    }
    
    private fun setupHardware() {
        waterFountainManager = WaterFountainManager.getInstance(this)
        
        lifecycleScope.launch {
            try {
                if (waterFountainManager.initialize()) {
                    AppLog.i("SMSActivity", "Hardware initialized successfully")
                } else {
                    AppLog.e("SMSActivity", "Hardware initialization failed")
                }
            } catch (e: Exception) {
                AppLog.e("SMSActivity", "Hardware error", e)
            }
        }
    }
    
    private fun dispenseWaterAfterVerification() {
        lifecycleScope.launch {
            try {
                AppLog.i("SMSActivity", "Verification successful - dispensing water")
                
                val result = waterFountainManager.dispenseWater()
                
                if (result.success) {
                    AppLog.i("SMSActivity", "Water dispensed successfully")
                    navigateToAnimation(result.dispensingTimeMs)
                } else {
                    AppLog.e("SMSActivity", "Water dispensing failed: ${result.errorMessage}")
                }
            } catch (e: Exception) {
                AppLog.e("SMSActivity", "Dispensing error", e)
            }
        }
    }
    
    private fun navigateToAnimation(dispensingTime: Long) {
        // Create transition intent
        val intent = Intent(this, VendingAnimationActivity::class.java)
        intent.putExtra("phoneNumber", phoneNumber)
        intent.putExtra("dispensingTime", dispensingTime)
        intent.putExtra("slot", waterFountainManager.getCurrentSlot())

        // Use standard fade transition (same as other screens)
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }



    private fun addDigit(digit: String) {
        when (currentStep) {
            1 -> {
                if (phoneNumber.length < MAX_PHONE_LENGTH) {
                    phoneNumber += digit
                    updatePhoneDisplayWithAnimation()
                    binding.verifyButton.isEnabled = phoneNumber.length == MAX_PHONE_LENGTH
                }
            }
            2 -> {
                if (otpCode.length < MAX_OTP_LENGTH) {
                    otpCode += digit
                    updateOtpDisplayWithAnimation()
                    binding.verifyOtpButton.isEnabled = otpCode.length == MAX_OTP_LENGTH
                }
            }
        }
    }

    private fun removeLastDigit() {
        when (currentStep) {
            1 -> {
                if (phoneNumber.isNotEmpty()) {
                    // Animate disappear
                    binding.phoneDisplay.startAnimation(
                        android.view.animation.AnimationUtils.loadAnimation(this, R.anim.number_disappear)
                    )
                    
                    lifecycleScope.launch {
                        delay(100)
                        phoneNumber = phoneNumber.dropLast(1)
                        updatePhoneDisplay()
                        binding.verifyButton.isEnabled = phoneNumber.length == MAX_PHONE_LENGTH
                    }
                }
            }
            2 -> {
                if (otpCode.isNotEmpty()) {
                    // Animate disappear
                    binding.phoneDisplay.startAnimation(
                        android.view.animation.AnimationUtils.loadAnimation(this, R.anim.number_disappear)
                    )
                    
                    lifecycleScope.launch {
                        delay(100)
                        otpCode = otpCode.dropLast(1)
                        updateOtpDisplay()
                        binding.verifyOtpButton.isEnabled = otpCode.length == MAX_OTP_LENGTH
                    }
                }
            }
        }
    }

    private fun clearInput() {
        when (currentStep) {
            1 -> {
                phoneNumber = ""
                updatePhoneDisplay()
                binding.verifyButton.isEnabled = false
            }
            2 -> {
                otpCode = ""
                updateOtpDisplay()
                binding.verifyOtpButton.isEnabled = false
            }
        }
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

    private fun updateOtpDisplay() {
        // Smooth transition when display updates - no masking for OTP
        val formatted = formatOtpCode(otpCode)
        binding.phoneDisplay.animate()
            .alpha(0.7f)
            .setDuration(100)
            .withEndAction {
                binding.phoneDisplay.text = formatted
                binding.phoneDisplay.animate()
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
        // Set up send code button listener (step 1)
        binding.verifyButton.setOnClickListener {
            inactivityTimer.reset()
            performButtonAnimation(binding.verifyButton) {
                if (phoneNumber.length == MAX_PHONE_LENGTH) {
                    setupOTPStep()
                }
            }
        }

        // Set up verify OTP button listener (step 2)
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

    private fun resendVerificationCode() {
        // Logic to resend the verification code
        // This could involve calling the SMS service again and updating the UI accordingly

        // For demo, we'll just reset the OTP field
        otpCode = ""
        updateOtpDisplay()
        binding.verifyOtpButton.isEnabled = false

        // Restart the timer
        startOtpTimer()

        // Simulate resend SMS (in real app, integrate with SMS service)
        simulateSendSMS()
    }

    override fun onDestroy() {
        super.onDestroy()
        inactivityTimer.cleanup()
        otpTimerJob?.cancel()
        
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
