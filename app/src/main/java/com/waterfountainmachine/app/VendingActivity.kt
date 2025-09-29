package com.waterfountainmachine.app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.waterfountainmachine.app.databinding.ActivityVendingBinding
import com.waterfountainmachine.app.hardware.WaterFountainManager
import com.waterfountainmachine.app.debug.WaterFountainDebug
import kotlinx.coroutines.launch

class VendingActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVendingBinding
    private val inactivityHandler = Handler(Looper.getMainLooper())
    private val inactivityTimeout = 30000L // 30 seconds
    private var questionMarkAnimator: AnimatorSet? = null
    
    // Water Fountain Hardware Integration
    private lateinit var waterFountainManager: WaterFountainManager
    private var isHardwareInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVendingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupFullScreen()
        setupHardware()
        setupClickListeners()
        setupQuestionMarkAnimation()
        setupModalFunctionality()
        startInactivityTimer()
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

    private fun setupClickListeners() {
        binding.smsCard.setOnClickListener {
            resetInactivityTimer()
            performCardClickAnimation(binding.smsCard) {
                val intent = Intent(this, SMSActivity::class.java)
                startActivity(intent)
                // Use zoom transition for a modern, engaging feel
                overridePendingTransition(R.anim.zoom_in_fade, R.anim.zoom_out_fade)
            }
        }

        // PIN Code card - now enables direct water dispensing for testing
        binding.pinCodeCard.setOnClickListener {
            resetInactivityTimer()
            performCardClickAnimation(binding.pinCodeCard) {
                // Direct water dispensing (for testing/demo)
                dispenseWaterDirect()
            }
        }

        // QR Code card - now used for system diagnostics
        binding.qrCodeCard.setOnClickListener {
            resetInactivityTimer()
            performCardClickAnimation(binding.qrCodeCard) {
                // Run system diagnostics
                runSystemDiagnostics()
            }
        }

        binding.backButton.setOnClickListener {
            returnToMainScreen()
        }
    }

    private fun returnToMainScreen() {
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun startInactivityTimer() {
        inactivityHandler.postDelayed({
            returnToMainScreen()
        }, inactivityTimeout)
    }

    private fun resetInactivityTimer() {
        inactivityHandler.removeCallbacksAndMessages(null)
        startInactivityTimer()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        resetInactivityTimer()
    }

    // Prevent back button from exiting app - return to main screen instead
    override fun onBackPressed() {
        returnToMainScreen()
    }

    // Prevent hardware keys from exiting
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_RECENT_APPS -> true // Block these keys
            KeyEvent.KEYCODE_BACK -> {
                returnToMainScreen()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setupFullScreen()
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

    private fun performCardClickAnimation(card: View, onComplete: () -> Unit) {
        // Create satisfying Material Design-style press animation
        val scaleDownX = ObjectAnimator.ofFloat(card, "scaleX", 1f, 0.95f)
        val scaleDownY = ObjectAnimator.ofFloat(card, "scaleY", 1f, 0.95f)
        val scaleUpX = ObjectAnimator.ofFloat(card, "scaleX", 0.95f, 1.02f)
        val scaleUpY = ObjectAnimator.ofFloat(card, "scaleY", 0.95f, 1.02f)
        val scaleNormalX = ObjectAnimator.ofFloat(card, "scaleX", 1.02f, 1f)
        val scaleNormalY = ObjectAnimator.ofFloat(card, "scaleY", 1.02f, 1f)

        // Add elevation animation for depth feedback
        val elevateUp = ObjectAnimator.ofFloat(card, "elevation", 8f, 16f)
        val elevateDown = ObjectAnimator.ofFloat(card, "elevation", 16f, 8f)

        val pressSet = AnimatorSet().apply {
            play(scaleDownX).with(scaleDownY).with(elevateUp)
            duration = 100
            interpolator = AccelerateDecelerateInterpolator()
        }

        val bounceSet = AnimatorSet().apply {
            play(scaleUpX).with(scaleUpY)
            duration = 150
            interpolator = AccelerateDecelerateInterpolator()
        }

        val normalizeSet = AnimatorSet().apply {
            play(scaleNormalX).with(scaleNormalY).with(elevateDown)
            duration = 100
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

    /**
     * Initialize hardware connection
     */
    private fun setupHardware() {
        try {
            waterFountainManager = WaterFountainManager.getInstance(this)
            
            // Initialize hardware in background
            lifecycleScope.launch {
                try {
                    Log.d("VendingActivity", "Initializing water fountain hardware...")
                    isHardwareInitialized = waterFountainManager.initialize()
                    
                    if (isHardwareInitialized) {
                        Log.i("VendingActivity", "Water fountain hardware initialized successfully")
                        
                        // Perform health check
                        val healthCheck = waterFountainManager.performHealthCheck()
                        Log.d("VendingActivity", "Health check: ${healthCheck.message}")
                        for (detail in healthCheck.details) {
                            Log.d("VendingActivity", "  $detail")
                        }
                        
                        // Show success toast on UI thread
                        runOnUiThread {
                            Toast.makeText(this@VendingActivity, "Water fountain ready", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Log.e("VendingActivity", "Failed to initialize water fountain hardware")
                        runOnUiThread {
                            Toast.makeText(this@VendingActivity, "Hardware initialization failed", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("VendingActivity", "Exception during hardware initialization", e)
                    runOnUiThread {
                        Toast.makeText(this@VendingActivity, "Hardware error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VendingActivity", "Failed to setup hardware", e)
        }
    }

    /**
     * Dispense water directly (for testing and demo)
     */
    private fun dispenseWaterDirect() {
        if (!isHardwareInitialized) {
            Toast.makeText(this, "Hardware not ready. Please wait...", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                Log.i("VendingActivity", "Starting water dispensing...")
                
                // Show loading state
                runOnUiThread {
                    Toast.makeText(this@VendingActivity, "Dispensing water...", Toast.LENGTH_SHORT).show()
                }
                
                // Dispense water
                val result = waterFountainManager.dispenseWater()
                
                // Handle result on UI thread
                runOnUiThread {
                    if (result.success) {
                        Log.i("VendingActivity", "Water dispensed successfully in ${result.dispensingTimeMs}ms")
                        
                        // Navigate to animation activity to show success
                        val intent = Intent(this@VendingActivity, VendingAnimationActivity::class.java)
                        intent.putExtra("dispensingTime", result.dispensingTimeMs)
                        intent.putExtra("slot", result.slot)
                        startActivity(intent)
                        overridePendingTransition(R.anim.zoom_in_fade, R.anim.zoom_out_fade)
                        
                    } else {
                        Log.e("VendingActivity", "Water dispensing failed: ${result.errorMessage}")
                        val errorMsg = result.errorMessage ?: "Unknown error occurred"
                        Toast.makeText(this@VendingActivity, "Dispensing failed: $errorMsg", Toast.LENGTH_LONG).show()
                        
                        // Try to clear faults for next attempt
                        lifecycleScope.launch {
                            waterFountainManager.clearFaults()
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e("VendingActivity", "Exception during water dispensing", e)
                runOnUiThread {
                    Toast.makeText(this@VendingActivity, "Dispensing error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Run comprehensive system diagnostics
     */
    private fun runSystemDiagnostics() {
        lifecycleScope.launch {
            try {
                Log.i("VendingActivity", "Starting system diagnostics...")
                
                runOnUiThread {
                    Toast.makeText(this@VendingActivity, "Running system diagnostics...", Toast.LENGTH_SHORT).show()
                }
                
                // Run comprehensive system test
                val testResult = WaterFountainDebug.runSystemTest(this@VendingActivity)
                
                // Print all results to log
                testResult.printToLog()
                
                // Show summary to user
                runOnUiThread {
                    val message = if (testResult.success) {
                        "✓ All system tests passed! Check logs for details."
                    } else {
                        "✗ Some tests failed. Check logs for details."
                    }
                    Toast.makeText(this@VendingActivity, message, Toast.LENGTH_LONG).show()
                }
                
            } catch (e: Exception) {
                Log.e("VendingActivity", "Exception during system diagnostics", e)
                runOnUiThread {
                    Toast.makeText(this@VendingActivity, "Diagnostics error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        inactivityHandler.removeCallbacksAndMessages(null)
        
        // Cleanup hardware connection
        if (isHardwareInitialized) {
            lifecycleScope.launch {
                try {
                    waterFountainManager.shutdown()
                } catch (e: Exception) {
                    Log.e("VendingActivity", "Error during hardware shutdown", e)
                }
            }
        }
    }
}