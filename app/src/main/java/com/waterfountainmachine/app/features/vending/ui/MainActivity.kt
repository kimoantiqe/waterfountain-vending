package com.waterfountainmachine.app.activities

import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import com.waterfountainmachine.app.R
import com.waterfountainmachine.app.WaterFountainApplication
import com.waterfountainmachine.app.admin.AdminGestureDetector
import com.waterfountainmachine.app.databinding.ActivityMainBinding
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.utils.FullScreenUtils
import com.waterfountainmachine.app.utils.AnimationUtils
import com.waterfountainmachine.app.utils.HardwareKeyHandler
import com.waterfountainmachine.app.utils.SoundManager

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var canBobbingAnimator: AnimatorSet? = null
    private lateinit var adminGestureDetector: AdminGestureDetector
    private lateinit var soundManager: SoundManager
    
    // Prevent multiple launches - FLAG_ACTIVITY_SINGLE_TOP provides primary protection
    // This flag provides additional UI feedback during navigation
    private var isNavigating = false
    
    // Runnable references for proper cleanup
    private val navigationResetRunnable = Runnable { isNavigating = false }

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set window background to black to prevent white flash during transitions
        window.setBackgroundDrawableResource(android.R.color.black)
        
        AppLog.i(TAG, "Water Fountain Vending Machine starting...")
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        AppLog.i(TAG, "MainActivity created successfully")

        setupKioskMode()
        setupFullScreen()
        setupSoundManager()
        setupBackButtonHandler()
        setupClickListener()
        setupPressAnimation()
        setupAdminGesture()
        
        // Initialize hardware on app launch
        initializeHardware()
    }
    
    private fun setupSoundManager() {
        soundManager = SoundManager(this)
        // Pre-load sounds for instant playback
        soundManager.loadSound(R.raw.click)
        AppLog.i(TAG, "SoundManager initialized and sounds loaded")
    }
    
    private fun setupBackButtonHandler() {
        // Disable back button in kiosk mode using modern API
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Do nothing - disable back button for kiosk mode
                AppLog.d(TAG, "Back button disabled in kiosk mode")
            }
        })
    }
    
    private fun setupKioskMode() {
        // Check if kiosk mode is enabled in settings (using encrypted preferences)
        val prefs = com.waterfountainmachine.app.utils.SecurePreferences.getSystemSettings(this)
        val kioskModeEnabled = prefs.getBoolean("kiosk_mode", true) // Default to enabled
        
        AppLog.i(TAG, "Kiosk mode setting: ${if (kioskModeEnabled) "ENABLED" else "DISABLED"}")
        
        if (kioskModeEnabled) {
            // Keep screen on
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            // Use modern APIs for showing activity when locked
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                )
            }

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
            // Only navigate if not already navigating
            if (!isNavigating) {
                AppLog.d(TAG, "Screen tapped, launching SMSActivity")
                
                isNavigating = true
                performPressAnimation {
                    val intent = Intent(this, SMSActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP // Prevent multiple instances
                    
                    val options = ActivityOptionsCompat.makeCustomAnimation(
                        this,
                        android.R.anim.fade_in,
                        android.R.anim.fade_out
                    )
                    
                    startActivity(intent, options.toBundle())
                    
                    // Reset flag after delay
                    binding.root.postDelayed(navigationResetRunnable, 1000)
                }
            }
        }
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
                    // Animate ONLY the can, not the entire content
                    binding.canImage.animate()
                        .scaleX(0.95f)
                        .scaleY(0.95f)
                        .setDuration(100)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    // Scale back to normal
                    binding.canImage.animate()
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
        // Smooth glow pulse animation - continuous glow that never disappears
        // Text color pulses from gray to slightly lighter gray-purple (very subtle)
        val textColorFrom = Color.parseColor("#555555") // Medium Gray (matches other text)
        val textColorTo = Color.parseColor("#6B657A")   // Slightly lighter gray with subtle hint of purple
        
        val textColorAnimator = ValueAnimator.ofObject(
            ArgbEvaluator(),
            textColorFrom,
            textColorTo
        ).apply {
            duration = 2000L // 2 second smooth pulse
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE // Smooth back and forth
            
            addUpdateListener { animator ->
                binding.instructionText.setTextColor(animator.animatedValue as Int)
            }
        }
        
        // Use alpha animation instead of scale for smoother, less jarring effect
        // More dramatic fade for visibility
        val alphaAnimator = ObjectAnimator.ofFloat(binding.instructionText, "alpha", 0.5f, 1f).apply {
            duration = 2500L // Slightly slower for smoother animation
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        
        // Combine animations (only text, no can animations, no scale)
        canBobbingAnimator = AnimatorSet().apply {
            playTogether(
                textColorAnimator,
                alphaAnimator
            )
            start()
        }
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
        // Create a satisfying press animation before navigation - animate can only
        val scaleDown = ObjectAnimator.ofFloat(binding.canImage, "scaleX", 1f, 0.92f)
        val scaleDownY = ObjectAnimator.ofFloat(binding.canImage, "scaleY", 1f, 0.92f)
        val scaleUp = ObjectAnimator.ofFloat(binding.canImage, "scaleX", 0.92f, 1.05f)
        val scaleUpY = ObjectAnimator.ofFloat(binding.canImage, "scaleY", 0.92f, 1.05f)
        val scaleNormal = ObjectAnimator.ofFloat(binding.canImage, "scaleX", 1.05f, 1f)
        val scaleNormalY = ObjectAnimator.ofFloat(binding.canImage, "scaleY", 1.05f, 1f)

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

    private fun showModal() {
        binding.modalOverlay.visibility = View.VISIBLE
        AnimationUtils.showModalAnimation(binding.modalContent)
    }

    private fun hideModal() {
        AnimationUtils.hideModalAnimation(binding.modalContent) {
            binding.modalOverlay.visibility = View.GONE
        }
    }

    // Prevent hardware keys from exiting AND handle admin keyboard trigger
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Only process on ACTION_DOWN to avoid double-counting
        if (event.action == KeyEvent.ACTION_DOWN) {
            // First, let admin gesture detector handle keyboard input (USB keyboard Enter key)
            if (adminGestureDetector.onKeyEvent(event.keyCode, event)) {
                // Admin gesture handled the key event - consume it completely
                AppLog.d(TAG, "Admin keyboard trigger processed, consuming key event")
                return true
            }
            
            // Block ALL Enter key presses from propagating (admin or not)
            // This prevents Enter from triggering screen clicks
            if (event.keyCode == KeyEvent.KEYCODE_ENTER) {
                AppLog.d(TAG, "Enter key blocked to prevent screen click")
                return true // Consume the event
            }
        }
        
        // Let other events be handled normally
        return super.dispatchKeyEvent(event)
    }
    
    // Keep onKeyDown for hardware key blocking
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Handle general hardware key blocking
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
    
    override fun onStop() {
        super.onStop()
        
        // Pause animations to save resources while in background
        canBobbingAnimator?.pause()
        
        AppLog.d(TAG, "MainActivity stopped - animations paused")
    }
    
    override fun onStart() {
        super.onStart()
        
        // Resume animations when returning to foreground
        canBobbingAnimator?.resume()
        
        AppLog.d(TAG, "MainActivity started - animations resumed")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Clean up animations
        canBobbingAnimator?.apply {
            removeAllListeners()
            cancel()
        }
        canBobbingAnimator = null
        
        // Clean up pending callbacks
        binding.root.removeCallbacks(navigationResetRunnable)
        
        // Release sound resources
        soundManager.release()
        
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