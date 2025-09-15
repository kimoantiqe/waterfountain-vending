package com.waterfountainmachine.app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback
import com.waterfountainmachine.app.databinding.ActivitySmsBinding

class SMSActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySmsBinding
    private var currentStep = 1
    private var phoneNumber = ""
    private var otpCode = ""
    private val maxPhoneLength = 10
    private val maxOtpLength = 6

    private var inactivityHandler = Handler(Looper.getMainLooper())
    private var inactivityRunnable: Runnable? = null
    private val inactivityTimeout = 60000L // 60 seconds
    private var questionMarkAnimator: AnimatorSet? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySmsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupFullScreen()
        initializeViews()
        setupKeypadListeners()
        setupPhoneNumberStep()
        setupQuestionMarkAnimation()
        setupModalFunctionality()
        setupInactivityTimer()
    }

    private fun setupFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Hide system UI completely
        binding.root.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
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
        // Create a more frequent and engaging shake animation
        val shakeX = ObjectAnimator.ofFloat(binding.questionMarkButton, "translationX", 0f, -12f, 12f, -8f, 8f, -4f, 4f, 0f)
        shakeX.duration = 1000 // Shorter duration

        val rotate = ObjectAnimator.ofFloat(binding.questionMarkIcon, "rotation", 0f, 20f, -20f, 15f, -15f, 8f, -8f, 0f)
        rotate.duration = 1000

        val scale = ObjectAnimator.ofFloat(binding.questionMarkButton, "scaleX", 1f, 1.15f, 1f)
        scale.duration = 1000

        val scaleY = ObjectAnimator.ofFloat(binding.questionMarkButton, "scaleY", 1f, 1.15f, 1f)
        scaleY.duration = 1000

        questionMarkAnimator = AnimatorSet().apply {
            playTogether(shakeX, rotate, scale, scaleY)
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = 1000 // Start sooner
        }

        // Create repeating animation with much shorter intervals
        questionMarkAnimator?.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                // Much more frequent shaking
                binding.root.postDelayed({
                    if (questionMarkAnimator != null) {
                        questionMarkAnimator?.start()
                    }
                }, 1500) // Only 1.5 second pause between animations
            }
        })

        questionMarkAnimator?.start()
    }

    private fun setupModalFunctionality() {
        // Question mark button click
        binding.questionMarkButton.setOnClickListener {
            resetInactivityTimer()
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

        // Animate modal appearance
        binding.modalContent.alpha = 0f
        binding.modalContent.scaleX = 0.8f
        binding.modalContent.scaleY = 0.8f

        binding.modalContent.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun hideModal() {
        binding.modalContent.animate()
            .alpha(0f)
            .scaleX(0.8f)
            .scaleY(0.8f)
            .setDuration(250)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                binding.modalOverlay.visibility = View.GONE
            }
            .start()
    }

    private fun setupPhoneNumberStep() {
        currentStep = 1
        phoneNumber = ""
        binding.titleText.text = getString(R.string.enter_phone_number)
        binding.subtitleText.text = getString(R.string.verification_code_message)

        // Show only the Send Code button, hide OTP buttons
        binding.verifyButton.visibility = View.VISIBLE
        binding.verifyButton.isEnabled = false
        binding.verifyButton.text = "Send Code"
        binding.otpButtonsLayout.visibility = View.GONE

        updatePhoneDisplay()
    }

    private fun setupOTPStep() {
        currentStep = 2
        otpCode = ""
        binding.titleText.text = "Enter verification code"
        binding.subtitleText.text = "We sent a code to (${phoneNumber.substring(0, 3)}) ${phoneNumber.substring(3, 6)}-${phoneNumber.substring(6)}"

        // Hide Send Code button, show Verify and Resend buttons
        binding.verifyButton.visibility = View.GONE
        binding.otpButtonsLayout.visibility = View.VISIBLE
        binding.verifyOtpButton.isEnabled = false

        updateOtpDisplay()

        // Simulate sending SMS (in real app, integrate with SMS service)
        simulateSendSMS()
    }

    private fun simulateSendSMS() {
        // In a real implementation, integrate with SMS service like Twilio
        // For demo purposes, we'll just show a simulated code
        val demoCode = "123456"

        // Auto-fill for demo (remove in production)
        Handler(Looper.getMainLooper()).postDelayed({
            // This is just for testing - remove in production
            // otpCode = demoCode
            // updateOtpDisplay()
            // continueButton.isEnabled = true
        }, 2000)
    }

    private fun verifyAndProceed() {
        // In real implementation, verify OTP with SMS service
        // For demo, we'll accept any 6-digit code

        if (otpCode.length == maxOtpLength) {
            // Create Material Design transition intent
            val intent = Intent(this, VendingAnimationActivity::class.java)
            intent.putExtra("phoneNumber", phoneNumber)

            // Get screen center for zoom animation
            val centerX = binding.root.width / 2
            val centerY = binding.root.height / 2

            // Create zoom from center transition - perfect for explosion theme!
            val options = ActivityOptionsCompat.makeScaleUpAnimation(
                binding.root,      // View to animate from
                centerX,           // X coordinate of center
                centerY,           // Y coordinate of center
                0,                 // Initial width (starts from point)
                0                  // Initial height (starts from point)
            )

            // Start activity with zoom transition
            startActivity(intent, options.toBundle())
            finish()
        }
    }

    private fun setupInactivityTimer() {
        resetInactivityTimer()
    }

    private fun resetInactivityTimer() {
        inactivityRunnable?.let { inactivityHandler.removeCallbacks(it) }

        inactivityRunnable = Runnable {
            // Return to main activity after inactivity
            finish()
        }

        inactivityHandler.postDelayed(inactivityRunnable!!, inactivityTimeout)
    }

    private fun addDigit(digit: String) {
        when (currentStep) {
            1 -> {
                if (phoneNumber.length < maxPhoneLength) {
                    phoneNumber += digit
                    updatePhoneDisplay()
                    binding.verifyButton.isEnabled = phoneNumber.length == maxPhoneLength
                }
            }
            2 -> {
                if (otpCode.length < maxOtpLength) {
                    otpCode += digit
                    updateOtpDisplay()
                    binding.verifyOtpButton.isEnabled = otpCode.length == maxOtpLength
                }
            }
        }
    }

    private fun removeLastDigit() {
        when (currentStep) {
            1 -> {
                if (phoneNumber.isNotEmpty()) {
                    phoneNumber = phoneNumber.dropLast(1)
                    updatePhoneDisplay()
                    binding.verifyButton.isEnabled = phoneNumber.length == maxPhoneLength
                }
            }
            2 -> {
                if (otpCode.isNotEmpty()) {
                    otpCode = otpCode.dropLast(1)
                    updateOtpDisplay()
                    binding.verifyOtpButton.isEnabled = otpCode.length == maxOtpLength
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
        val formatted = formatCanadianPhoneNumber(phoneNumber)
        binding.phoneDisplay.text = formatted
    }

    private fun updateOtpDisplay() {
        val formatted = formatOtpCode(otpCode)
        binding.phoneDisplay.text = formatted
    }

    private fun formatCanadianPhoneNumber(number: String): String {
        return when (number.length) {
            0 -> "(___) ___-____"
            1 -> "(${number}__) ___-____"
            2 -> "(${number}_) ___-____"
            3 -> "($number) ___-____"
            4 -> "(${number.substring(0, 3)}) ${number.substring(3)}__-____"
            5 -> "(${number.substring(0, 3)}) ${number.substring(3)}_-____"
            6 -> "(${number.substring(0, 3)}) ${number.substring(3)}-____"
            7 -> "(${number.substring(0, 3)}) ${number.substring(3, 6)}-${number.substring(6)}___"
            8 -> "(${number.substring(0, 3)}) ${number.substring(3, 6)}-${number.substring(6)}__"
            9 -> "(${number.substring(0, 3)}) ${number.substring(3, 6)}-${number.substring(6)}_"
            10 -> "(${number.substring(0, 3)}) ${number.substring(3, 6)}-${number.substring(6)}"
            else -> number
        }
    }

    private fun formatOtpCode(code: String): String {
        val placeholder = "_ _ _ _ _ _"
        val digits = code.toCharArray()
        val formatted = StringBuilder()

        for (i in 0..5) {
            if (i < digits.size) {
                formatted.append(digits[i])
            } else {
                formatted.append("_")
            }
            if (i < 5) formatted.append(" ")
        }

        return formatted.toString()
    }

    private fun setupKeypadListeners() {
        // Set up send code button listener (step 1)
        binding.verifyButton.setOnClickListener {
            resetInactivityTimer()
            performButtonAnimation(binding.verifyButton) {
                if (phoneNumber.length == maxPhoneLength) {
                    setupOTPStep()
                }
            }
        }

        // Set up verify OTP button listener (step 2)
        binding.verifyOtpButton.setOnClickListener {
            resetInactivityTimer()
            performButtonAnimation(binding.verifyOtpButton) {
                if (otpCode.length == maxOtpLength) {
                    verifyAndProceed()
                }
            }
        }

        // Set up resend code button listener
        binding.resendCodeButton.setOnClickListener {
            resetInactivityTimer()
            performButtonAnimation(binding.resendCodeButton) {
                resendVerificationCode()
            }
        }

        // Set up number button listeners with feedback animations
        binding.btn0.setOnClickListener { resetInactivityTimer(); performKeypadAnimation(binding.btn0) { addDigit("0") } }
        binding.btn1.setOnClickListener { resetInactivityTimer(); performKeypadAnimation(binding.btn1) { addDigit("1") } }
        binding.btn2.setOnClickListener { resetInactivityTimer(); performKeypadAnimation(binding.btn2) { addDigit("2") } }
        binding.btn3.setOnClickListener { resetInactivityTimer(); performKeypadAnimation(binding.btn3) { addDigit("3") } }
        binding.btn4.setOnClickListener { resetInactivityTimer(); performKeypadAnimation(binding.btn4) { addDigit("4") } }
        binding.btn5.setOnClickListener { resetInactivityTimer(); performKeypadAnimation(binding.btn5) { addDigit("5") } }
        binding.btn6.setOnClickListener { resetInactivityTimer(); performKeypadAnimation(binding.btn6) { addDigit("6") } }
        binding.btn7.setOnClickListener { resetInactivityTimer(); performKeypadAnimation(binding.btn7) { addDigit("7") } }
        binding.btn8.setOnClickListener { resetInactivityTimer(); performKeypadAnimation(binding.btn8) { addDigit("8") } }
        binding.btn9.setOnClickListener { resetInactivityTimer(); performKeypadAnimation(binding.btn9) { addDigit("9") } }

        // Set up control button listeners with feedback
        binding.btnBackspace.setOnClickListener { resetInactivityTimer(); performKeypadAnimation(binding.btnBackspace) { removeLastDigit() } }
        binding.btnClear.setOnClickListener { resetInactivityTimer(); performKeypadAnimation(binding.btnClear) { clearInput() } }
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

        // Simulate resend SMS (in real app, integrate with SMS service)
        simulateSendSMS()
    }

    override fun onDestroy() {
        super.onDestroy()
        inactivityRunnable?.let { inactivityHandler.removeCallbacks(it) }
    }

    override fun onResume() {
        super.onResume()
        setupFullScreen()
        resetInactivityTimer()
    }

    override fun onPause() {
        super.onPause()
        inactivityRunnable?.let { inactivityHandler.removeCallbacks(it) }
    }
}
