package com.waterfountainmachine.app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.waterfountainmachine.app.admin.AdminGestureDetector
import com.waterfountainmachine.app.databinding.ActivityMainBinding
import com.waterfountainmachine.app.utils.AppLog

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var questionMarkAnimator: AnimatorSet? = null
    private lateinit var adminGestureDetector: AdminGestureDetector
    private var isNavigating = false // Prevent multiple launches
    
    // Initialize USB receiver lazily to avoid race condition
    private val usbReceiver: BroadcastReceiver by lazy {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device = intent.getParcelableExtra<android.hardware.usb.UsbDevice>(UsbManager.EXTRA_DEVICE)
                        AppLog.i(TAG, "USB device attached: ${device?.productName ?: "Unknown"}")
                        AppLog.i(TAG, "  VID:PID: ${device?.vendorId?.let { String.format("0x%04X", it) }}:${device?.productId?.let { String.format("0x%04X", it) }}")
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val device = intent.getParcelableExtra<android.hardware.usb.UsbDevice>(UsbManager.EXTRA_DEVICE)
                        AppLog.w(TAG, "USB device detached: ${device?.productName ?: "Unknown"}")
                        AppLog.w(TAG, "  VID:PID: ${device?.vendorId?.let { String.format("0x%04X", it) }}:${device?.productId?.let { String.format("0x%04X", it) }}")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        AppLog.i(TAG, "Water Fountain Vending Machine starting...")
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        AppLog.i(TAG, "MainActivity created successfully")

        setupKioskMode()
        setupFullScreen()
        setupClickListener()
        setupQuestionMarkAnimation()
        setupModalFunctionality()
        setupPressAnimation()
        setupAdminGesture()
        
        // Initialize hardware on app launch
        initializeHardware()
        
        // Update mock mode indicator visibility
        updateMockModeIndicator()
    }
    
    /**
     * Show/hide mock mode indicator based on hardware mode
     */
    private fun updateMockModeIndicator() {
        val prefs = getSharedPreferences("system_settings", Context.MODE_PRIVATE)
        val useRealSerial = prefs.getBoolean("use_real_serial", false)
        
        // Show indicator only if in mock mode (not using real hardware)
        binding.mockModeIndicator.root.visibility = if (!useRealSerial) View.VISIBLE else View.GONE
        
        if (!useRealSerial) {
            AppLog.d(TAG, "Mock mode indicator displayed (hardware in test mode)")
        }
    }

    private fun setupKioskMode() {
        // Check if kiosk mode is enabled in settings
        val prefs = getSharedPreferences("system_settings", Context.MODE_PRIVATE)
        val kioskModeEnabled = prefs.getBoolean("kiosk_mode", true) // Default to enabled
        
        AppLog.i(TAG, "Kiosk mode setting: ${if (kioskModeEnabled) "ENABLED" else "DISABLED"}")
        
        if (kioskModeEnabled) {
            // Keep screen on
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            // Prevent task switching
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

            // Make this a launcher activity and clear task
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            
            AppLog.i(TAG, "Kiosk mode features applied")
        } else {
            AppLog.i(TAG, "Kiosk mode disabled - running in normal mode")
        }
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

    private fun setupClickListener() {
        binding.root.setOnClickListener {
            // Check if we're in the admin corner area
            // This will be handled by dispatchTouchEvent and AdminGestureDetector
            // Only navigate if modal is not visible and not already navigating
            if (binding.modalOverlay.visibility == View.GONE && !isNavigating) {
                AppLog.d(TAG, "Screen tapped, launching VendingActivity")
                isNavigating = true
                performPressAnimation {
                    val intent = Intent(this, VendingActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP // Prevent multiple instances
                    startActivity(intent)
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    
                    // Reset flag after delay
                    binding.root.postDelayed({
                        isNavigating = false
                    }, 1000)
                }
            }
        }
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

    private fun setupPressAnimation() {
        // Add breathing animation to instruction text
        setupBreathingAnimation()

        // Add touch feedback to the entire screen
        binding.root.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // Create haptic-like ripple effect
                    createRippleEffect(event.x, event.y)
                    // Animate ONLY the content, not the background
                    binding.mainContent.animate()
                        .scaleX(0.98f)
                        .scaleY(0.98f)
                        .setDuration(100)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    // Scale back to normal
                    binding.mainContent.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(150)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
            }
            false // Let the click listener handle the actual click
        }
    }

    private fun setupBreathingAnimation() {
        // Create a more engaging "pulse glow" animation instead of just breathing
        val glowIn = ObjectAnimator.ofFloat(binding.instructionText, "alpha", 0.7f, 1f)
        glowIn.duration = 1200

        val glowOut = ObjectAnimator.ofFloat(binding.instructionText, "alpha", 1f, 0.7f)
        glowOut.duration = 1200

        // Add a subtle color shift effect by changing text shadow/elevation
        val elevateIn = ObjectAnimator.ofFloat(binding.instructionText, "translationZ", 0f, 8f)
        elevateIn.duration = 1200

        val elevateOut = ObjectAnimator.ofFloat(binding.instructionText, "translationZ", 8f, 0f)
        elevateOut.duration = 1200

        // Add a gentle wave-like scale effect
        val scaleInX = ObjectAnimator.ofFloat(binding.instructionText, "scaleX", 1f, 1.03f)
        scaleInX.duration = 1200

        val scaleInY = ObjectAnimator.ofFloat(binding.instructionText, "scaleY", 1f, 1.03f)
        scaleInY.duration = 1200

        val scaleOutX = ObjectAnimator.ofFloat(binding.instructionText, "scaleX", 1.03f, 1f)
        scaleOutX.duration = 1200

        val scaleOutY = ObjectAnimator.ofFloat(binding.instructionText, "scaleY", 1.03f, 1f)
        scaleOutY.duration = 1200

        val pulseSet = AnimatorSet().apply {
            play(glowIn).with(elevateIn).with(scaleInX).with(scaleInY)
            play(glowOut).with(elevateOut).with(scaleOutX).with(scaleOutY).after(glowIn)
            interpolator = AccelerateDecelerateInterpolator()
        }

        // Make it repeat continuously with shorter intervals
        pulseSet.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                // Shorter pause between pulses for more engagement
                binding.root.postDelayed({
                    pulseSet.start()
                }, 500) // Only 0.5 second pause
            }
        })

        pulseSet.start()
    }

    private fun createRippleEffect(x: Float, y: Float) {
        // Only use the circular ripple effect - no background manipulation
        createCircularRipple(x, y)
    }

    private fun createCircularRipple(x: Float, y: Float) {
        // Create a circular ripple effect at the touch location
        val rippleView = View(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(200, 200)
            // Create a circular background programmatically
            background = createCircularDrawable()
            alpha = 0.6f
            scaleX = 0.1f
            scaleY = 0.1f
            translationX = x - 100f
            translationY = y - 100f
            elevation = 10f
        }

        binding.root.addView(rippleView)

        // Animate the ripple expanding and fading
        rippleView.animate()
            .scaleX(4f)
            .scaleY(4f)
            .alpha(0f)
            .setDuration(500)
            .withEndAction {
                binding.root.removeView(rippleView)
            }
            .start()
    }

    private fun createCircularDrawable(): android.graphics.drawable.Drawable {
        val drawable = android.graphics.drawable.GradientDrawable()
        drawable.shape = android.graphics.drawable.GradientDrawable.OVAL
        drawable.setColor(0x4DFFFFFF) // Semi-transparent white
        drawable.setStroke(4, 0x80FFFFFF.toInt()) // Light border
        return drawable
    }

    private fun performPressAnimation(onComplete: () -> Unit) {
        // Create a satisfying press animation before navigation - animate content only
        val scaleDown = ObjectAnimator.ofFloat(binding.mainContent, "scaleX", 1f, 0.95f)
        val scaleDownY = ObjectAnimator.ofFloat(binding.mainContent, "scaleY", 1f, 0.95f)
        val scaleUp = ObjectAnimator.ofFloat(binding.mainContent, "scaleX", 0.95f, 1.02f)
        val scaleUpY = ObjectAnimator.ofFloat(binding.mainContent, "scaleY", 0.95f, 1.02f)
        val scaleNormal = ObjectAnimator.ofFloat(binding.mainContent, "scaleX", 1.02f, 1f)
        val scaleNormalY = ObjectAnimator.ofFloat(binding.mainContent, "scaleY", 1.02f, 1f)

        val animatorSet = AnimatorSet().apply {
            play(scaleDown).with(scaleDownY)
            play(scaleUp).with(scaleUpY).after(scaleDown)
            play(scaleNormal).with(scaleNormalY).after(scaleUp)
            duration = 200
            interpolator = AccelerateDecelerateInterpolator()
        }

        animatorSet.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                onComplete()
            }
        })

        animatorSet.start()
    }

    private fun setupModalFunctionality() {
        // Question mark button click
        binding.questionMarkButton.setOnClickListener {
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

    // Prevent back button from exiting app
    override fun onBackPressed() {
        super.onBackPressed()
        // Do nothing - disable back button
    }

    // Prevent hardware keys from exiting
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_RECENT_APPS,
            KeyEvent.KEYCODE_BACK -> true // Block these keys
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setupFullScreen()
        }
    }

    private fun setupAdminGesture() {
        adminGestureDetector = AdminGestureDetector(this, binding.root)
    }
    
    /**
     * Initialize hardware at app launch using Application class
     */
    private fun initializeHardware() {
        val app = application as WaterFountainApplication
        
        AppLog.i(TAG, "Triggering hardware initialization from MainActivity")
        
        app.initializeHardware { success ->
            if (success) {
                AppLog.i(TAG, "Hardware ready for use")
            } else {
                AppLog.w(TAG, "Hardware initialization failed - app will use mock mode")
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Register USB broadcast receiver (now safe with lazy initialization)
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbReceiver, filter)
        AppLog.d(TAG, "USB broadcast receiver registered")
    }

    override fun onPause() {
        super.onPause()
        // Unregister USB broadcast receiver
        try {
            unregisterReceiver(usbReceiver)
            AppLog.d(TAG, "USB broadcast receiver unregistered")
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered, ignore
            AppLog.d(TAG, "USB receiver was not registered")
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Ensure receiver is unregistered
        try {
            unregisterReceiver(usbReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered, ignore
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        // Let admin gesture detector handle corner touches first
        ev?.let { 
            if (adminGestureDetector.onTouchEvent(it)) {
                // Admin gesture handled the event, don't pass it to other views
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}