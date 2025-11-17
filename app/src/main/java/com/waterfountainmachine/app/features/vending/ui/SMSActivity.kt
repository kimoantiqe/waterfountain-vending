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
import com.waterfountainmachine.app.utils.FullScreenUtils
import com.waterfountainmachine.app.utils.AnimationUtils
import com.waterfountainmachine.app.utils.InactivityTimer
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.utils.SoundManager
import com.waterfountainmachine.app.utils.UserErrorMessages
import com.waterfountainmachine.app.config.WaterFountainConfig
import com.waterfountainmachine.app.viewmodels.SMSViewModel
import com.waterfountainmachine.app.viewmodels.SMSUiState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import androidx.activity.viewModels

@AndroidEntryPoint
class SMSActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySmsBinding
    
    // Get ViewModel via Hilt ViewModelProvider
    private val viewModel: SMSViewModel by viewModels()

    private lateinit var inactivityTimer: InactivityTimer
    private var questionMarkAnimator: AnimatorSet? = null
    private lateinit var soundManager: SoundManager
    
    // QR code cache (simple in-memory cache)
    private var cachedQRCode: android.graphics.Bitmap? = null
    private var cachedQRCodeText: String? = null
    
    // Debounce flag for question mark button
    private var isQuestionMarkClickable = true
    
    companion object {
        private const val TAG = "SMSActivity"
        private const val QUESTION_MARK_DEBOUNCE_MS = 400L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set window background to black to prevent white flash during transitions
        window.setBackgroundDrawableResource(android.R.color.black)
        
        binding = ActivitySmsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        FullScreenUtils.setupFullScreen(window, binding.root)
        
        // Initialize sound manager
        soundManager = SoundManager(this)
        soundManager.loadSound(R.raw.click)
        soundManager.loadSound(R.raw.correct)
        
        // Initialize inactivity timer FIRST (needed by observers)
        inactivityTimer = InactivityTimer(WaterFountainConfig.INACTIVITY_TIMEOUT_MS) { returnToMainScreen() }
        inactivityTimer.start()
        
        // Setup UI
        initializeViews()
        setupKeypadListeners()
        setupPhoneNumberStep()
        setupQuestionMarkAnimation()
        setupModalFunctionality()
        
        // Setup ViewModel observers (after inactivityTimer is initialized)
        setupViewModelObservers()
    }
    
    /**
     * Setup observers for ViewModel StateFlows
     */
    private fun setupViewModelObservers() {
        // Observe UI state changes
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                handleUiState(state)
            }
        }
        
        // Observe phone number changes
        lifecycleScope.launch {
            viewModel.phoneNumber.collect { phoneNumber ->
                updatePhoneDisplay()
                updateVerifyButtonState()
            }
        }
        
        // Observe phone visibility changes
        lifecycleScope.launch {
            viewModel.isPhoneVisible.collect { isVisible ->
                updatePhoneDisplay()
                updateToggleIcon()
            }
        }
        
        // Observe critical state for inactivity timer control
        lifecycleScope.launch {
            viewModel.isInCriticalState.collect { isCritical ->
                if (isCritical) {
                    inactivityTimer.pause()
                    AppLog.d(TAG, "Critical state: Inactivity timer paused")
                } else {
                    inactivityTimer.resume()
                    AppLog.d(TAG, "Normal state: Inactivity timer resumed")
                }
            }
        }
    }
    
    /**
     * Handle UI state changes from ViewModel
     */
    private fun handleUiState(state: SMSUiState) {
        when (state) {
            is SMSUiState.PhoneEntry -> {
                // Normal phone entry state
                hideLoading()
            }
            is SMSUiState.InvalidPhoneNumber -> {
                // Show validation error
                showError(UserErrorMessages.INVALID_PHONE_NUMBER)
            }
            is SMSUiState.RequestingOtp -> {
                // Show loading state
                showLoading()
            }
            is SMSUiState.OtpRequestSuccess -> {
                // Navigate to verification screen
                hideLoading()
                soundManager.playSound(R.raw.correct, 0.7f)
                navigateToVerification(state.phoneNumber, state.isPhoneVisible)
            }
            is SMSUiState.DailyLimitReached -> {
                // Navigate to error screen with daily limit message
                hideLoading()
                navigateToError(UserErrorMessages.DAILY_LIMIT_REACHED)
            }
            is SMSUiState.Error -> {
                // Show error screen
                hideLoading()
                showError(state.message)
            }
        }
    }

    private fun initializeViews() {
        // Views are now accessed through binding
        setupBackButton()
    }

    private fun setupBackButton() {
        binding.backButton.setOnClickListener {
            soundManager.playSound(R.raw.click, 0.6f)
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
            
            soundManager.playSound(R.raw.click, 0.6f)
            inactivityTimer.reset()
            
            // Disable clicking temporarily
            isQuestionMarkClickable = false
            binding.questionMarkButton.postDelayed({
                isQuestionMarkClickable = true
            }, QUESTION_MARK_DEBOUNCE_MS)
            
            // Check if QR modal is open - if so, close it instead of toggling info modal
            if (binding.qrModalOverlay.visibility == View.VISIBLE) {
                hideQrCodeModal()
                return@setOnClickListener
            }
            
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
        // Generate QR code for https://www.waterfountain.io with caching
        try {
            val url = "https://www.waterfountain.io"
            
            // Check cache first
            val qrBitmap = if (cachedQRCodeText == url && cachedQRCode != null) {
                AppLog.d(TAG, "Using cached QR code")
                cachedQRCode!!
            } else {
                AppLog.d(TAG, "Generating new QR code")
                val newBitmap = generateQRCode(url, 400, 400)
                // Cache the result
                cachedQRCode = newBitmap
                cachedQRCodeText = url
                newBitmap
            }
            
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
        
        // Optimized: Pre-compute pixel array instead of setPixel() in nested loop
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
        }
        
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        
        return bitmap
    }

    private fun setupPhoneNumberStep() {
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
            viewModel.togglePhoneVisibility()
        }
    }

    /**
     * Request OTP via ViewModel
     */
    private fun sendCodeAndNavigate() {
        soundManager.playSound(R.raw.click, 0.6f)
        inactivityTimer.reset()
        viewModel.requestOtp()
    }
    
    /**
     * Navigate to verification screen
     */
    private fun navigateToVerification(phoneNumber: String, isPhoneVisible: Boolean) {
        val intent = Intent(this, SMSVerifyActivity::class.java)
        intent.putExtra(SMSVerifyActivity.EXTRA_PHONE_NUMBER, phoneNumber)
        intent.putExtra(SMSVerifyActivity.EXTRA_PHONE_VISIBILITY, isPhoneVisible)
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
    
    /**
     * Navigate to error screen
     */
    private fun navigateToError(message: String) {
        val intent = Intent(this, ErrorActivity::class.java)
        intent.putExtra(ErrorActivity.EXTRA_MESSAGE, message)
        startActivity(intent)
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
    
    /**
     * Show loading state (disable inputs, show progress)
     */
    private fun showLoading() {
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
        
        val isComplete = viewModel.phoneNumber.value.length == WaterFountainConfig.MAX_PHONE_LENGTH
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
     * Show error by navigating to error screen
     * All errors in SMS flow should show the error screen
     */
    private fun showError(message: String) {
        navigateToErrorScreen(message)
    }
    
    /**
     * Navigate to error screen for all errors
     */
    private fun navigateToErrorScreen(message: String) {
        AppLog.e(TAG, "Error occurred - navigating to error screen: $message")
        val intent = Intent(this, ErrorActivity::class.java)
        intent.putExtra(ErrorActivity.EXTRA_MESSAGE, message)
        startActivity(intent)
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun addDigit(digit: String) {
        viewModel.addDigit(digit)
        updatePhoneDisplayWithAnimation()
    }

    private fun removeLastDigit() {
        if (viewModel.phoneNumber.value.isNotEmpty()) {
            // Animate disappear
            binding.phoneDisplay.startAnimation(
                android.view.animation.AnimationUtils.loadAnimation(this, R.anim.number_disappear)
            )
            viewModel.removeLastDigit()
        }
    }

    private fun clearInput() {
        viewModel.clearPhoneNumber()
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
        val phoneNumber = viewModel.phoneNumber.value
        val isPhoneVisible = viewModel.isPhoneVisible.value
        
        val formatted = if (isPhoneVisible) {
            formatSmartPhoneNumber(phoneNumber)
        } else {
            maskPhoneNumber(phoneNumber)
        }
        binding.phoneDisplay.text = formatted
    }

    private fun updatePhoneDisplayWithAnimation() {
        val phoneNumber = viewModel.phoneNumber.value
        val isPhoneVisible = viewModel.isPhoneVisible.value
        
        val formatted = if (isPhoneVisible) {
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
    
    /**
     * Update verify button state based on phone number length
     */
    private fun updateVerifyButtonState() {
        val phoneNumber = viewModel.phoneNumber.value
        val isComplete = phoneNumber.length == WaterFountainConfig.MAX_PHONE_LENGTH
        updateButtonDisabledState(binding.verifyButton, isComplete)
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
        val isPhoneVisible = viewModel.isPhoneVisible.value
        // Update icon and alpha based on visibility state
        // When visible, show hide icon (eye-off) at full opacity
        // When hidden, show show icon (eye) at reduced opacity
        val iconRes = if (isPhoneVisible) {
            R.drawable.ic_eye_off // Hide icon when numbers are visible
        } else {
            R.drawable.ic_eye // Show icon when numbers are hidden
        }
        binding.togglePhoneVisibility.setImageResource(iconRes)
        binding.togglePhoneVisibility.alpha = if (isPhoneVisible) 1.0f else 0.7f
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
            
            if (viewModel.phoneNumber.value.length == WaterFountainConfig.MAX_PHONE_LENGTH) {
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
        
        // Clean up QR code cache
        cachedQRCode?.recycle()
        cachedQRCode = null
        cachedQRCodeText = null
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

    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}
