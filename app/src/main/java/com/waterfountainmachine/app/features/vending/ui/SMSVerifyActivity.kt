package com.waterfountainmachine.app.activities

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.waterfountainmachine.app.R
import com.waterfountainmachine.app.databinding.ActivitySmsVerifyBinding
import com.waterfountainmachine.app.hardware.WaterFountainManager
import com.waterfountainmachine.app.utils.FullScreenUtils
import com.waterfountainmachine.app.utils.AnimationUtils
import com.waterfountainmachine.app.utils.InactivityTimer
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.utils.SoundManager
import com.waterfountainmachine.app.utils.UserErrorMessages
import com.waterfountainmachine.app.viewmodels.SMSVerifyViewModel
import com.waterfountainmachine.app.viewmodels.SMSVerifyUiState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@AndroidEntryPoint
class SMSVerifyActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySmsVerifyBinding
    private val viewModel: SMSVerifyViewModel by viewModels()

    // Helper properties to access ViewModel state (for backward compatibility with existing code)
    private val otpCode: String
        get() = viewModel.otpCode.value
    private val phoneNumber: String
        get() = viewModel.phoneNumber.value

    private lateinit var inactivityTimer: InactivityTimer
    private var questionMarkAnimator: AnimatorSet? = null
    private lateinit var soundManager: SoundManager

    // Water fountain hardware manager
    private lateinit var waterFountainManager: WaterFountainManager
    
    // Debounce flag for question mark button
    private var isQuestionMarkClickable = true
    
    // Flag to prevent box updates during error animation
    private var isShowingError = false
    
    // Runnable references for error restore callbacks
    private var errorRestoreRunnable: Runnable? = null
    
    // Flag to prevent double verification
    private var isVerifying = false
    private var autoVerifyRunnable: Runnable? = null
    
    companion object {
        private const val TAG = "SMSVerifyActivity"
        private const val MAX_OTP_LENGTH = 6
        private const val INACTIVITY_TIMEOUT_MS = 300_000L // 5 minutes
        private const val ANIMATION_DURATION_MS = 300L
        private const val QUESTION_MARK_DEBOUNCE_MS = 400L
        const val EXTRA_PHONE_NUMBER = "phoneNumber"
        const val EXTRA_PHONE_VISIBILITY = "phoneVisibility"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set window background to black to prevent white flash during transitions
        window.setBackgroundDrawableResource(android.R.color.black)
        
        binding = ActivitySmsVerifyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get phone number and visibility state from intent
        val phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: ""
        val isPhoneNumberVisible = intent.getBooleanExtra(EXTRA_PHONE_VISIBILITY, true)

        // Initialize ViewModel with phone number
        viewModel.initialize(phoneNumber)

        FullScreenUtils.setupFullScreen(window, binding.root)
        
        // Initialize sound manager
        soundManager = SoundManager(this)
        soundManager.loadSound(R.raw.click)
        soundManager.loadSound(R.raw.correct)
        soundManager.loadSound(R.raw.questionmark)
        soundManager.loadSound(R.raw.error)
        
        initializeViews()
        setupKeypadListeners()
        setupQuestionMarkAnimation()
        setupModalFunctionality()
        
        // Initialize inactivity timer BEFORE setting up ViewModel observers
        // (observers may immediately try to pause/resume the timer)
        inactivityTimer = InactivityTimer(INACTIVITY_TIMEOUT_MS) { returnToMainScreen() }
        inactivityTimer.start()
        
        // Setup ViewModel observers after timer is initialized
        setupViewModelObservers()
        
        // Initialize water fountain hardware
        setupHardware()
        
        // Setup initial UI
        setupVerificationUI(isPhoneNumberVisible)
    }

    /**
     * Setup observers for ViewModel state changes
     */
    private fun setupViewModelObservers() {
        // Observe UI state changes
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                handleUiState(state)
            }
        }

        // Observe OTP code changes
        lifecycleScope.launch {
            viewModel.otpCode.collect { code ->
                updateOtpDisplay(code)
            }
        }

        // Observe OTP timer
        lifecycleScope.launch {
            viewModel.otpTimeRemaining.collect { timeRemaining ->
                updateTimerDisplay(timeRemaining)
            }
        }

        // Observe critical state for inactivity timer
        lifecycleScope.launch {
            viewModel.isInCriticalState.collect { isCritical ->
                if (isCritical) {
                    inactivityTimer.pause()
                } else {
                    inactivityTimer.resume()
                }
            }
        }
    }

    /**
     * Handle UI state changes from ViewModel
     */
    private fun handleUiState(state: SMSVerifyUiState) {
        when (state) {
            is SMSVerifyUiState.EnteringOtp -> {
                isVerifying = false
                setLoadingState(false)
            }
            is SMSVerifyUiState.IncompleteOtp -> {
                isVerifying = false
                showIncompleteOtpHint()
            }
            is SMSVerifyUiState.Verifying -> {
                // isVerifying flag already set in verifyAndProceed()
                setLoadingState(true)
            }
            is SMSVerifyUiState.VerificationSuccess -> {
                isVerifying = false
                navigateToVendingAnimation()
            }
            is SMSVerifyUiState.IncorrectOtp -> {
                isVerifying = false
                // Hide loading spinner and button animations only
                // Don't re-enable keypad yet - showIncorrectCodeHint will manage it during error animation
                hideLoadingSpinner()
                showIncorrectCodeHint(state.attemptsRemaining)
            }
            is SMSVerifyUiState.Error -> {
                isVerifying = false
                // Navigate to error screen for all errors (including too many attempts)
                navigateToErrorScreen(state.message)
            }
            is SMSVerifyUiState.ResendingOtp -> {
                // Show resending state if needed
                AppLog.d(TAG, "Resending OTP...")
            }
            is SMSVerifyUiState.OtpResent -> {
                showOtpResentMessage()
            }
            is SMSVerifyUiState.ResendError -> {
                showResendError(state.message)
            }
            is SMSVerifyUiState.OtpExpired -> {
                // Don't show error - just let the timer display handle it
                // The updateTimerDisplay() function will show the resend button
                // when timeRemaining reaches 0
                isVerifying = false
                setLoadingState(false)
            }
        }
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
        // Use SINGLE_TOP to reuse existing MainActivity instance for smooth transition
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun setupQuestionMarkAnimation() {
        // PNG image already contains the question mark, no separate animation needed
        // Just apply subtle floating animation to the button itself
        questionMarkAnimator = AnimationUtils.setupQuestionMarkAnimation(
            button = binding.questionMarkButton,
            icon = null,  // No separate icon, using PNG
            rootView = binding.root
        )
    }

    private fun setupModalFunctionality() {
        // Question mark button click - toggle modal open/close with debounce
        binding.questionMarkButton.setOnClickListener {
            // Debounce to prevent spam clicking
            if (!isQuestionMarkClickable) {
                return@setOnClickListener
            }
            
            soundManager.playSound(R.raw.questionmark, 0.6f)
            inactivityTimer.reset()
            
            // Disable clicking temporarily
            isQuestionMarkClickable = false
            binding.questionMarkButton.postDelayed({
                isQuestionMarkClickable = true
            }, QUESTION_MARK_DEBOUNCE_MS)
            
            // Toggle info modal: close if open, open if closed
            if (binding.modalOverlay.visibility == View.VISIBLE) {
                hideModal()
            } else {
                showModal()
            }
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

        // Setup FAQ accordion functionality
        setupFaqAccordion()

        // Show QR Code button click - transition from info modal to QR modal
        binding.showQrCodeButton.setOnClickListener {
            soundManager.playSound(R.raw.click, 0.6f)
            inactivityTimer.reset()
            
            // Hide info modal first, then show QR modal to avoid double overlay
            AnimationUtils.hideModalAnimation(binding.modalContent) {
                binding.modalOverlay.visibility = View.GONE
                // Show QR modal after info modal is hidden
                showQrCodeModal()
            }
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

    private fun setupFaqAccordion() {
        // FAQ Item 1
        binding.faq1Header.setOnClickListener {
            soundManager.playSound(R.raw.click, 0.6f)
            inactivityTimer.reset()
            toggleFaqItem(binding.faq1Answer, binding.faq1Chevron)
        }

        // FAQ Item 2
        binding.faq2Header.setOnClickListener {
            soundManager.playSound(R.raw.click, 0.6f)
            inactivityTimer.reset()
            toggleFaqItem(binding.faq2Answer, binding.faq2Chevron)
        }

        // FAQ Item 3
        binding.faq3Header.setOnClickListener {
            soundManager.playSound(R.raw.click, 0.6f)
            inactivityTimer.reset()
            toggleFaqItem(binding.faq3Answer, binding.faq3Chevron)
        }

        // FAQ Item 4
        binding.faq4Header.setOnClickListener {
            soundManager.playSound(R.raw.click, 0.6f)
            inactivityTimer.reset()
            toggleFaqItem(binding.faq4Answer, binding.faq4Chevron)
        }

        // FAQ Item 5
        binding.faq5Header.setOnClickListener {
            soundManager.playSound(R.raw.click, 0.6f)
            inactivityTimer.reset()
            toggleFaqItem(binding.faq5Answer, binding.faq5Chevron)
        }
    }

    private fun toggleFaqItem(answerView: TextView, chevronView: android.widget.ImageView) {
        if (answerView.visibility == View.GONE) {
            // Expand: Show answer with fade in animation and rotate chevron
            answerView.visibility = View.VISIBLE
            answerView.animate()
                .alpha(1f)
                .setDuration(200)
                .start()
            
            chevronView.animate()
                .rotation(180f)
                .setDuration(200)
                .start()
        } else {
            // Collapse: Fade out answer and rotate chevron back
            answerView.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    answerView.visibility = View.GONE
                }
                .start()
            
            chevronView.animate()
                .rotation(0f)
                .setDuration(200)
                .start()
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
        // Note: Phone number display logic removed since subtitle text view doesn't exist in layout
        // The layout uses titleText for "Enter verification code" and an info message above
        
        // Initialize verify button - always enabled for click feedback
        binding.verifyOtpButton.isEnabled = true
        updateButtonDisabledState(binding.verifyOtpButton, false)
        
        // Simulate sending SMS (in real app, integrate with SMS service)
        simulateSendSMS()
    }

    private fun simulateSendSMS() {
        // In a real implementation, integrate with SMS service like Twilio
        // For demo purposes, we'll just show a simulated code
        val phoneNumber = viewModel.phoneNumber.value
        AppLog.i(TAG, "Sending verification code to $phoneNumber")
    }

    private fun verifyAndProceed() {
        // Prevent double verification
        if (isVerifying) {
            AppLog.d(TAG, "Already verifying, ignoring duplicate call")
            return
        }
        
        isVerifying = true
        
        // Delegate to ViewModel
        viewModel.verifyOtp()
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
            
            // Keypad is always enabled - no failed attempts tracking here (handled by ViewModel)
            setKeypadEnabled(true)
        }
    }
    
    /**
     * Hide loading spinner without re-enabling keypad
     * Used when we want to show error animation instead of returning to normal state
     */
    private fun hideLoadingSpinner() {
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
        
        // NOTE: Keypad state is NOT changed here - caller is responsible for managing it
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
    
    // Note: handleVerificationError removed - error handling now in ViewModel via handleUiState
    
    private fun showVerificationError(errorMessage: String = "Incorrect PIN") {
        // Set all boxes to error state (red borders)
        setOtpBoxesErrorState()
        
        // Smoothly fade out and slide up keypad during error
        animateKeypadError()
        
        lifecycleScope.launch {
            // Wait for animations
            delay(600)
            
            // Clear the OTP code and boxes
            viewModel.clearOtp()
            
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
     * Show error message in subtitle
     */
    private fun showError(message: String) {
        AppLog.e(TAG, "Showing error in subtitle: $message")
        // Update subtitle to show error message
        binding.subtitleText.text = message
        binding.subtitleText.setTextColor(resources.getColor(android.R.color.holo_red_light, null))
        
        // Reset subtitle after 3 seconds
        binding.subtitleText.postDelayed({
            binding.subtitleText.text = "Check your messages for an OTP code"
            binding.subtitleText.setTextColor(resources.getColor(android.R.color.darker_gray, null))
        }, 3000)
    }
    
    /**
     * Navigate to error screen for critical errors only
     */
    private fun navigateToErrorScreen(message: String) {
        AppLog.e(TAG, "Error occurred - navigating to error screen: $message")
        val intent = Intent(this, ErrorActivity::class.java)
        intent.putExtra(ErrorActivity.EXTRA_MESSAGE, message)
        startActivity(intent)
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
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
        viewModel.addDigit(digit)
        
        // Auto-verify when all 6 digits are entered
        if (viewModel.otpCode.value.length == MAX_OTP_LENGTH && !isVerifying) {
            AppLog.d(TAG, "OTP complete, auto-verifying...")
            
            // Cancel any previous auto-verify that might be pending
            autoVerifyRunnable?.let { binding.otpBox1.removeCallbacks(it) }
            
            // Small delay for better UX (user sees the last digit appear)
            autoVerifyRunnable = Runnable {
                if (!isVerifying) {
                    verifyAndProceed()
                }
            }
            binding.otpBox1.postDelayed(autoVerifyRunnable!!, 300)
        }
    }

    private fun removeLastDigit() {
        // Cancel auto-verify when user deletes a digit
        autoVerifyRunnable?.let { binding.otpBox1.removeCallbacks(it) }
        viewModel.deleteDigit()
    }

    private fun clearInput() {
        // Cancel auto-verify when user clears
        autoVerifyRunnable?.let { binding.otpBox1.removeCallbacks(it) }
        viewModel.clearOtp()
    }
    
    /**
     * Update OTP display based on ViewModel state
     */
    private fun updateOtpDisplay(code: String) {
        // Don't update boxes if we're showing an error animation
        if (isShowingError) {
            return
        }
        updateOtpBoxesWithAnimation()
        val isComplete = code.length == MAX_OTP_LENGTH
        updateButtonDisabledState(binding.verifyOtpButton, isComplete)
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
        val otpCode = viewModel.otpCode.value
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
        // Cancel any pending error restore callbacks
        errorRestoreRunnable?.let { binding.otpBox1.removeCallbacks(it) }
        
        // Set error flag to prevent box updates during animation
        isShowingError = true
        
        // Turn empty boxes red and pulse the next one
        if (otpCode.length < MAX_OTP_LENGTH) {
            val boxes = getOtpBoxes()
            
            // First, cancel any existing animations and reset properties to ensure animation works
            for (i in otpCode.length until boxes.size) {
                boxes[i].animate().cancel()
                boxes[i].translationX = 0f
                boxes[i].scaleX = 1f
                boxes[i].scaleY = 1f
                boxes[i].setBackgroundResource(R.drawable.otp_box_background)
            }
            
            // Then set all empty boxes to error state (red border) after a tiny delay
            boxes[otpCode.length].postDelayed({
                for (i in otpCode.length until boxes.size) {
                    boxes[i].setBackgroundResource(R.drawable.otp_box_error)
                }
            }, 50)
            
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
        errorRestoreRunnable = Runnable {
            isShowingError = false
            updateOtpBoxes()
        }
        binding.otpBox1.postDelayed(errorRestoreRunnable!!, 2500)
    }
    
    /**
     * Show hint when OTP code is incorrect with remaining attempts
     * Clears the code and turns empty boxes red simultaneously
     */
    private fun showIncorrectCodeHint(attemptsRemaining: Int) {
        AppLog.d(TAG, "Showing incorrect code hint. Attempts remaining: $attemptsRemaining")
        
        // Cancel any pending error restore callbacks
        errorRestoreRunnable?.let { binding.otpBox1.removeCallbacks(it) }
        
        // Set error flag to prevent box updates
        isShowingError = true
        
        // Disable keypad during error animation
        setKeypadEnabled(false)
        
        // Play error sound
        soundManager.playSound(R.raw.error, 0.7f)
        
        // Clear the OTP code in ViewModel (but don't update display yet)
        viewModel.clearOtp()
        
        // Now set all boxes to error state (shake and turn red)
        val boxes = getOtpBoxes()
        
        for (box in boxes) {
            // Cancel any existing animations and reset position
            box.animate().cancel()
            box.translationX = 0f
            
            // Clear the text
            box.text = ""
            // Set red background
            box.setBackgroundResource(R.drawable.otp_box_error)
            
            // Shake animation
            val shake = ObjectAnimator.ofFloat(box, "translationX", 0f, -15f, 15f, -10f, 10f, -5f, 5f, 0f)
            shake.duration = 600
            shake.interpolator = AccelerateDecelerateInterpolator()
            shake.start()
        }
        
        // Auto-restore to normal state after 1.5 seconds
        errorRestoreRunnable = Runnable {
            isShowingError = false
            updateOtpBoxes()
            updateButtonDisabledState(binding.verifyOtpButton, false)
            // Re-enable keypad after animation completes
            setKeypadEnabled(true)
        }
        binding.otpBox1.postDelayed(errorRestoreRunnable!!, 1500)
    }
    
    /**
     * Clear the OTP code and reset UI
     */
    private fun clearOtpCode() {
        AppLog.d(TAG, "Clearing OTP code")
        viewModel.clearOtp()
        updateOtpBoxes()
        updateButtonDisabledState(binding.verifyOtpButton, false)
    }
    
    /**
     * Update button appearance to make disabled state very obvious with smooth fade
     * Button is always enabled for click feedback, but changes appearance based on state
     */
    private fun updateButtonDisabledState(button: androidx.appcompat.widget.AppCompatButton, isEnabled: Boolean) {
        // Keep button always enabled so we can show feedback when user clicks incomplete OTP
        button.isEnabled = true
        
        // Swap background drawable between enabled and disabled purple
        val backgroundRes = if (isEnabled) {
            R.drawable.glass_action_button_purple
        } else {
            R.drawable.glass_action_button_purple_disabled
        }
        button.setBackgroundResource(backgroundRes)
        
        // Keep text white always - no color animation needed
        button.setTextColor(0xFFFFFFFF.toInt())
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
        // Delegate to ViewModel
        viewModel.resendOtp()
    }

    /**
     * Update timer display
     */
    private fun updateTimerDisplay(timeRemaining: Int) {
        val minutes = timeRemaining / 60
        val seconds = timeRemaining % 60
        
        if (timeRemaining > 0) {
            // Show timer, hide resend button
            binding.timerLayout.visibility = View.VISIBLE
            binding.resendCodeButton.visibility = View.GONE
            binding.timerText.text = String.format("%d:%02d", minutes, seconds)
        } else {
            // Hide timer, show resend button
            binding.timerLayout.visibility = View.GONE
            binding.resendCodeButton.visibility = View.VISIBLE
            binding.resendCodeButton.isEnabled = true
        }
    }

    /**
     * Show message that OTP was resent successfully
     */
    private fun showOtpResentMessage() {
        // Reset UI state after short delay
        lifecycleScope.launch {
            kotlinx.coroutines.delay(500)
            viewModel.resetUiState()
        }
    }

    /**
     * Show resend error message
     */
    private fun showResendError(message: String) {
        showError(message)
    }

    /**
     * Show OTP expired message
     */
    private fun showOtpExpiredMessage() {
        showError(UserErrorMessages.SESSION_EXPIRED)
    }

    override fun onDestroy() {
        super.onDestroy()
        inactivityTimer.cleanup()
        
        // Clean up pending callbacks
        autoVerifyRunnable?.let { binding.otpBox1.removeCallbacks(it) }
        errorRestoreRunnable?.let { binding.otpBox1.removeCallbacks(it) }
        
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
        // Note: OTP timer is now managed by ViewModel, no manual cancellation needed
    }
}
