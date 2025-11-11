package com.waterfountainmachine.app.activities

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
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
import com.waterfountainmachine.app.utils.SoundManager
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
    private lateinit var soundManager: SoundManager

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
        const val EXTRA_PHONE_VISIBILITY = "phoneVisibility"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySmsVerifyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get phone number and visibility state from intent
        phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: ""
        val isPhoneNumberVisible = intent.getBooleanExtra(EXTRA_PHONE_VISIBILITY, true)

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
        setupVerificationUI(isPhoneNumberVisible)
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

        // Show QR Code button click
        binding.showQrCodeButton.setOnClickListener {
            soundManager.playSound(R.raw.click, 0.6f)
            inactivityTimer.reset()
            hideModal()
            showQrCodeModal()
        }

        // Close QR modal button click
        binding.closeQrModalButton.setOnClickListener {
            soundManager.playSound(R.raw.click, 0.6f)
            hideQrCodeModal()
        }

        // Click outside QR modal to close
        binding.qrModalOverlay.setOnClickListener { view ->
            if (view == binding.qrModalOverlay) {
                hideQrCodeModal()
            }
        }

        // Prevent QR modal content clicks from closing modal
        binding.qrModalContent.setOnClickListener {
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

    private fun showQrCodeModal() {
        // Generate QR code for https://www.waterfountain.io
        try {
            val qrBitmap = generateQRCode("https://www.waterfountain.io", 400, 400)
            binding.qrCodeImage.setImageBitmap(qrBitmap)
        } catch (e: Exception) {
            AppLog.e(TAG, "Error generating QR code", e)
        }
        
        binding.qrModalOverlay.visibility = View.VISIBLE
        AnimationUtils.showModalAnimation(binding.qrModalContent)
    }

    private fun hideQrCodeModal() {
        AnimationUtils.hideModalAnimation(binding.qrModalContent) {
            binding.qrModalOverlay.visibility = View.GONE
        }
    }

    private fun generateQRCode(text: String, width: Int, height: Int): android.graphics.Bitmap {
        val qrCodeWriter = com.google.zxing.qrcode.QRCodeWriter()
        val bitMatrix = qrCodeWriter.encode(text, com.google.zxing.BarcodeFormat.QR_CODE, width, height)
        
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        
        return bitmap
    }

    private fun setupVerificationUI(isPhoneNumberVisible: Boolean) {
        otpCode = ""
        
        // Show phone number in subtitle - masked or visible based on user's choice from SMS screen
        val displayPhone = if (isPhoneNumberVisible) {
            // Show full number formatted
            "(${phoneNumber.substring(0, 3)}) ${phoneNumber.substring(3, 6)}-${phoneNumber.substring(6)}"
        } else {
            // Show only last 4 digits
            "••• •••-${phoneNumber.substring(6)}"
        }
        
        // Initialize verify button - always enabled for click feedback
        binding.verifyOtpButton.isEnabled = true
        updateButtonDisabledState(binding.verifyOtpButton, false)
        
        // Clear OTP boxes
        updateOtpBoxes()
        
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
        
        // Check if OTP is incomplete
        if (otpCode.length != MAX_OTP_LENGTH) {
            AppLog.d(TAG, "Incomplete OTP: ${otpCode.length} digits")
            showIncompleteOtpHint()
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
                
                // Add minimum display time for spinner
                val startTime = System.currentTimeMillis()
                val minDisplayTime = 800L
                
                // Verify OTP via repository
                val result = authRepository.verifyOtp(formattedPhone, otpCode)
                
                // Ensure minimum spinner visibility
                val elapsedTime = System.currentTimeMillis() - startTime
                val remainingTime = minDisplayTime - elapsedTime
                if (remainingTime > 0) {
                    delay(remainingTime)
                }
                
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
                AppLog.e(TAG, "Verification error", e)
                handleVerificationError("Verification failed. Please try again.")
            }
        }
    }
    
    private fun setLoadingState(loading: Boolean) {
        if (loading) {
            // Disable keypad during loading
            setKeypadEnabled(false)
            
            // Fade out button
            binding.verifyOtpButton.animate()
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
        } else {
            // Fade out and hide spinner
            binding.loadingSpinner.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    binding.loadingSpinner.visibility = View.GONE
                    
                    // Fade button back in
                    binding.verifyOtpButton.animate()
                        .alpha(1f)
                        .setDuration(300)
                        .start()
                }
                .start()
            
            val isComplete = otpCode.length == MAX_OTP_LENGTH
            // Update button visual state after fade in
            binding.verifyOtpButton.postDelayed({
                updateButtonDisabledState(binding.verifyOtpButton, isComplete)
            }, 300)
            
            // Re-enable keypad if not max attempts
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
        // Set all boxes to error state (red borders)
        setOtpBoxesErrorState()
        
        // Smoothly fade out and slide up keypad during error
        animateKeypadError()
        
        lifecycleScope.launch {
            // Wait for animations
            delay(600)
            
            // Clear the OTP code and boxes
            otpCode = ""
            updateOtpBoxes()
            
            // Reset button state
            setLoadingState(false)
            
            // Wait before restoring keypad
            delay(2000)
            
            // Smoothly fade in and slide down keypad after error clears
            delay(200)
            animateKeypadRestore()
        }
    }
    
    private fun showErrorAndReturnToMain(errorMessage: String = "Too many failed attempts") {
        // Set all boxes to error state
        setOtpBoxesErrorState()
        
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
    
    private fun animateKeypadError() {
        // Smoother fade out with gentle scale and translation
        binding.keypadLayout.animate()
            .alpha(0.3f)
            .scaleX(0.96f)
            .scaleY(0.96f)
            .translationY(15f)
            .setDuration(400)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }
    
    private fun animateKeypadRestore() {
        // Smoother fade in and restore with spring-like effect
        binding.keypadLayout.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(500)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }
    
    /**
     * Navigate to vending animation screen after successful OTP verification
     */
    private fun navigateToVendingAnimation() {
        AppLog.i(TAG, "OTP verified - navigating to vending animation")
        
        // Play success sound
        soundManager.playSound(R.raw.correct, 0.7f)
        
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
            updateOtpBoxesWithAnimation()
            val isComplete = otpCode.length == MAX_OTP_LENGTH
            updateButtonDisabledState(binding.verifyOtpButton, isComplete)
        }
    }

    private fun removeLastDigit() {
        if (otpCode.isNotEmpty()) {
            otpCode = otpCode.dropLast(1)
            updateOtpBoxesWithAnimation()
            val isComplete = otpCode.length == MAX_OTP_LENGTH
            updateButtonDisabledState(binding.verifyOtpButton, isComplete)
        }
    }

    private fun clearInput() {
        otpCode = ""
        updateOtpBoxes()
        updateButtonDisabledState(binding.verifyOtpButton, false)
    }
    
    /**
     * Get list of OTP box views
     */
    private fun getOtpBoxes(): List<TextView> {
        return listOf(
            binding.otpBox1,
            binding.otpBox2,
            binding.otpBox3,
            binding.otpBox4,
            binding.otpBox5,
            binding.otpBox6
        )
    }
    
    /**
     * Update all OTP boxes with current code
     */
    private fun updateOtpBoxes() {
        val boxes = getOtpBoxes()
        for (i in boxes.indices) {
            if (i < otpCode.length) {
                boxes[i].text = otpCode[i].toString()
                boxes[i].setBackgroundResource(R.drawable.otp_box_background)
            } else {
                boxes[i].text = ""
                boxes[i].setBackgroundResource(R.drawable.otp_box_background)
            }
        }
        
        // Highlight the next empty box
        if (otpCode.length < MAX_OTP_LENGTH) {
            boxes[otpCode.length].setBackgroundResource(R.drawable.otp_box_highlighted)
        }
    }
    
    /**
     * Update OTP boxes with animation
     */
    private fun updateOtpBoxesWithAnimation() {
        val boxes = getOtpBoxes()
        
        // Animate the box that just changed
        val changedIndex = otpCode.length - 1
        if (changedIndex >= 0 && changedIndex < boxes.size) {
            val box = boxes[changedIndex]
            box.text = otpCode[changedIndex].toString()
            
            // Scale animation for the new digit
            box.scaleX = 0.7f
            box.scaleY = 0.7f
            box.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
        
        // Update all boxes
        for (i in boxes.indices) {
            if (i < otpCode.length) {
                boxes[i].text = otpCode[i].toString()
                boxes[i].setBackgroundResource(R.drawable.otp_box_background)
            } else {
                boxes[i].text = ""
                boxes[i].setBackgroundResource(R.drawable.otp_box_background)
            }
        }
        
        // Highlight the next empty box
        if (otpCode.length < MAX_OTP_LENGTH) {
            boxes[otpCode.length].setBackgroundResource(R.drawable.otp_box_highlighted)
        }
    }
    
    /**
     * Set all boxes to error state (red borders)
     */
    private fun setOtpBoxesErrorState() {
        val boxes = getOtpBoxes()
        
        // Shake and turn red
        for (box in boxes) {
            box.setBackgroundResource(R.drawable.otp_box_error)
            
            // Shake animation
            val shake = ObjectAnimator.ofFloat(box, "translationX", 0f, -15f, 15f, -10f, 10f, -5f, 5f, 0f)
            shake.duration = 600
            shake.interpolator = AccelerateDecelerateInterpolator()
            shake.start()
        }
    }
    
    /**
     * Show hint when user clicks verify with incomplete OTP
     */
    private fun showIncompleteOtpHint() {
        // Turn empty boxes red and pulse the next one
        if (otpCode.length < MAX_OTP_LENGTH) {
            val boxes = getOtpBoxes()
            
            // Set all empty boxes to error state (red border)
            for (i in otpCode.length until boxes.size) {
                boxes[i].setBackgroundResource(R.drawable.otp_box_error)
            }
            
            // Pulse the next empty box
            val emptyBox = boxes[otpCode.length]
            emptyBox.animate()
                .scaleX(1.15f)
                .scaleY(1.15f)
                .setDuration(200)
                .withEndAction {
                    emptyBox.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(200)
                        .start()
                }
                .start()
        }
        
        // Auto-restore box backgrounds after 2.5s
        binding.otpBox1.postDelayed({
            updateOtpBoxes()
        }, 2500)
    }
    
    /**
     * Update button appearance to make disabled state very obvious with smooth fade
     */
    private fun updateButtonDisabledState(button: androidx.appcompat.widget.AppCompatButton, isEnabled: Boolean) {
        // Smooth alpha transition
        button.animate()
            .alpha(if (isEnabled) 1.0f else 0.4f)
            .setDuration(200)
            .start()
        
        // Smooth color transition
        val startColor = button.currentTextColor
        val endColor = if (isEnabled) {
            0xFF888888.toInt()  // Lighter gray when enabled (more visible on glass background)
        } else {
            0xFF333333.toInt()  // Very dark gray when disabled (less prominent)
        }
        
        // Animate color change
        val colorAnimator = android.animation.ValueAnimator.ofArgb(startColor, endColor)
        colorAnimator.duration = 200
        colorAnimator.addUpdateListener { animator ->
            button.setTextColor(animator.animatedValue as Int)
        }
        colorAnimator.start()
    }

    private fun setupKeypadListeners() {
        // Set up verify OTP button listener
        binding.verifyOtpButton.setOnClickListener {
            inactivityTimer.reset()
            performButtonAnimation(binding.verifyOtpButton) {
                // Always call verifyAndProceed to show feedback
                verifyAndProceed()
            }
        }

        // Set up resend code button listener with proper animation
        binding.resendCodeButton.setOnClickListener {
            soundManager.playSound(R.raw.click, 0.5f)
            inactivityTimer.reset()
            performKeypadAnimation(binding.resendCodeButton) {
                resendVerificationCode()
            }
        }

        // Set up number button listeners with feedback animations
        binding.btn0.setOnClickListener { soundManager.playSound(R.raw.click, 0.5f); inactivityTimer.reset(); performKeypadAnimation(binding.btn0) { addDigit("0") } }
        binding.btn1.setOnClickListener { soundManager.playSound(R.raw.click, 0.5f); inactivityTimer.reset(); performKeypadAnimation(binding.btn1) { addDigit("1") } }
        binding.btn2.setOnClickListener { soundManager.playSound(R.raw.click, 0.5f); inactivityTimer.reset(); performKeypadAnimation(binding.btn2) { addDigit("2") } }
        binding.btn3.setOnClickListener { soundManager.playSound(R.raw.click, 0.5f); inactivityTimer.reset(); performKeypadAnimation(binding.btn3) { addDigit("3") } }
        binding.btn4.setOnClickListener { soundManager.playSound(R.raw.click, 0.5f); inactivityTimer.reset(); performKeypadAnimation(binding.btn4) { addDigit("4") } }
        binding.btn5.setOnClickListener { soundManager.playSound(R.raw.click, 0.5f); inactivityTimer.reset(); performKeypadAnimation(binding.btn5) { addDigit("5") } }
        binding.btn6.setOnClickListener { soundManager.playSound(R.raw.click, 0.5f); inactivityTimer.reset(); performKeypadAnimation(binding.btn6) { addDigit("6") } }
        binding.btn7.setOnClickListener { soundManager.playSound(R.raw.click, 0.5f); inactivityTimer.reset(); performKeypadAnimation(binding.btn7) { addDigit("7") } }
        binding.btn8.setOnClickListener { soundManager.playSound(R.raw.click, 0.5f); inactivityTimer.reset(); performKeypadAnimation(binding.btn8) { addDigit("8") } }
        binding.btn9.setOnClickListener { soundManager.playSound(R.raw.click, 0.5f); inactivityTimer.reset(); performKeypadAnimation(binding.btn9) { addDigit("9") } }

        // Set up control button listeners with feedback
        binding.btnBackspace.setOnClickListener { soundManager.playSound(R.raw.click, 0.5f); inactivityTimer.reset(); performKeypadAnimation(binding.btnBackspace) { removeLastDigit() } }
        binding.btnClear.setOnClickListener { soundManager.playSound(R.raw.click, 0.5f); inactivityTimer.reset(); performKeypadAnimation(binding.btnClear) { clearInput() } }
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
        updateOtpBoxes()
        updateButtonDisabledState(binding.verifyOtpButton, false)

        // Format phone with country code (+1 for US)
        val formattedPhone = "+1$phoneNumber"
        
        lifecycleScope.launch {
            try {
                AppLog.d(TAG, "Resending OTP for phone: +1***-***-${phoneNumber.takeLast(4)}")
                
                // Request OTP via repository
                val result = authRepository.requestOtp(formattedPhone)
                
                result.onSuccess { response ->
                    AppLog.i(TAG, "OTP resent successfully: ${response.message}")
                    
                    // Restart the timer
                    startOtpTimer()
                }.onFailure { error ->
                    val errorMessage = error.message ?: "Failed to resend code"
                    AppLog.e(TAG, "OTP resend failed: $errorMessage", error)
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "Unexpected error resending OTP", e)
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
        
        // Clean up sound manager
        soundManager.release()
        
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
