package com.waterfountainmachine.app.activities

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.waterfountainmachine.app.R
import com.waterfountainmachine.app.WaterFountainApplication
import com.waterfountainmachine.app.admin.AdminGestureDetector
import com.waterfountainmachine.app.databinding.ActivityMainBinding
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.utils.FullScreenUtils
import com.waterfountainmachine.app.utils.AnimationUtils
import com.waterfountainmachine.app.utils.HardwareKeyHandler

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var questionMarkAnimator: AnimatorSet? = null
    private lateinit var adminGestureDetector: AdminGestureDetector
    private var isNavigating = false // Prevent multiple launches

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
        FullScreenUtils.setupFullScreen(window, binding.root)
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
        questionMarkAnimator = AnimationUtils.setupQuestionMarkAnimation(
            button = binding.questionMarkButton,
            icon = binding.questionMarkIcon,
            rootView = binding.root
        )
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
        AnimationUtils.showModalAnimation(binding.modalContent)
    }

    private fun hideModal() {
        AnimationUtils.hideModalAnimation(binding.modalContent) {
            binding.modalOverlay.visibility = View.GONE
        }
    }

    // Prevent back button from exiting app
    override fun onBackPressed() {
        super.onBackPressed()
        // Do nothing - disable back button
    }

    // Prevent hardware keys from exiting
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return HardwareKeyHandler.handleKeyDown(keyCode)
            || super.onKeyDown(keyCode, event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            FullScreenUtils.reapplyFullScreen(window, binding.root)
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
        
        // Observe hardware state changes
        app.observeHardwareState { state ->
            runOnUiThread {
                updateHardwareStatusIndicator(state)
            }
        }
        
        app.initializeHardware { success ->
            if (success) {
                AppLog.i(TAG, "✅ Hardware ready for use")
            } else {
                AppLog.e(TAG, "❌ Hardware initialization failed - operations may not work")
            }
        }
    }
    
    /**
     * Update hardware status indicator in UI
     */
    private fun updateHardwareStatusIndicator(state: WaterFountainApplication.HardwareState) {
        val statusIndicator = binding.root.findViewById<View>(R.id.hardwareStatusIndicator)
        
        statusIndicator?.let { indicator ->
            when (state) {
                WaterFountainApplication.HardwareState.READY -> {
                    // Hide indicator when hardware is ready (green = success = no need to show)
                    indicator.visibility = View.GONE
                }
                WaterFountainApplication.HardwareState.ERROR,
                WaterFountainApplication.HardwareState.DISCONNECTED -> {
                    // Red circle - hardware error
                    indicator.visibility = View.VISIBLE
                    indicator.background = resources.getDrawable(R.drawable.circle_indicator, null)
                    AppLog.w(TAG, "⚠️ Hardware status indicator: RED (error/disconnected)")
                }
                WaterFountainApplication.HardwareState.INITIALIZING -> {
                    // Orange circle - initializing
                    indicator.visibility = View.VISIBLE
                    val drawable = indicator.background.mutate()
                    if (drawable is android.graphics.drawable.GradientDrawable) {
                        drawable.setColor(0xFFFFA500.toInt()) // Orange
                    }
                    AppLog.d(TAG, "⚠️ Hardware status indicator: ORANGE (initializing)")
                }
                else -> {
                    // Gray circle - other states (uninitialized, maintenance)
                    indicator.visibility = View.VISIBLE
                    val drawable = indicator.background.mutate()
                    if (drawable is android.graphics.drawable.GradientDrawable) {
                        drawable.setColor(0xFF808080.toInt()) // Gray
                    }
                    AppLog.d(TAG, "⚠️ Hardware status indicator: GRAY (${state.name})")
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        AppLog.d(TAG, "MainActivity destroyed")
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