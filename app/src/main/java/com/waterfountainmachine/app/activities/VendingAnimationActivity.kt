package com.waterfountainmachine.app.activities

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.waterfountainmachine.app.R
import com.waterfountainmachine.app.databinding.ActivityVendingAnimationBinding
import com.waterfountainmachine.app.hardware.WaterFountainManager
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.views.ProgressRingView
import com.waterfountainmachine.app.utils.FullScreenUtils
import com.waterfountainmachine.app.utils.SoundManager
import com.waterfountainmachine.app.config.WaterFountainConfig
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import nl.dionsegijn.konfetti.core.models.Size
import nl.dionsegijn.konfetti.xml.KonfettiView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class VendingAnimationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVendingAnimationBinding
    
    // Sound manager
    private lateinit var soundManager: SoundManager
    
    // Water fountain hardware manager
    private lateinit var waterFountainManager: WaterFountainManager

    private var phoneNumber: String? = null
    private var dispensingTime: Long = 0
    private var slot: Int = 1
    
    // Runnable references for cleanup
    private val logoDelayedRunnable = Runnable {
        binding.logoImage.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .rotation(0f)
            .setDuration(900)
            .setInterpolator(OvershootInterpolator(1.2f))
            .withLayer()  // Use hardware layer during animation for smooth 60fps
            .start()
    }
    
    private val confettiDelayedRunnable = Runnable {
        launchConfetti()
    }
    
    companion object {
        private const val TAG = "VendingAnimationActivity"
        // All constants moved to WaterFountainConfig for centralization
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set window background to match gradient BEFORE setContentView to prevent black flash
        window.setBackgroundDrawableResource(R.drawable.gradient_background_main)
        
        binding = ActivityVendingAnimationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        phoneNumber = intent.getStringExtra("phoneNumber")
        dispensingTime = intent.getLongExtra("dispensingTime", 8000)
        slot = intent.getIntExtra("slot", 1)
        
        // Initialize sound manager
        soundManager = SoundManager(this)
        soundManager.loadSound(R.raw.vend)
        
        // Initialize hardware manager
        waterFountainManager = WaterFountainManager.getInstance(this)

        initializeViews()
        setupFullscreen()
        
        // Start all views invisible to prevent "boop in" effect
        binding.statusText.alpha = 0f
        binding.ringContainer.alpha = 0f
        binding.completionText.alpha = 0f
        binding.logoImage.alpha = 0f
        
        // Start water dispensing and animation
        startWaterDispensing()
    }
    
    /**
     * Start water dispensing in background while showing animation
     */
    private fun startWaterDispensing() {
        lifecycleScope.launch {
            try {
                AppLog.i(TAG, "Starting water dispensing...")
                
                // Start the ring animation immediately for user feedback
                startRingAnimation()
                
                // Dispense water on IO thread (blocking hardware operation)
                val result = withContext(Dispatchers.IO) {
                    waterFountainManager.dispenseWater()
                }
                
                if (result.success) {
                    AppLog.i(TAG, "Water dispensed successfully in ${result.dispensingTimeMs}ms")
                    // Update dispensing time with actual value
                    dispensingTime = result.dispensingTimeMs
                } else {
                    AppLog.e(TAG, "Water dispensing failed: ${result.errorMessage}")
                    // Continue with animation anyway - don't break user experience
                }
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Error during water dispensing", e)
                // Continue with animation anyway
            }
        }
    }

    private fun initializeViews() {
        // Load random messages from resources
        val magicMessages = resources.getStringArray(R.array.magic_messages)
        val completionMessages = resources.getStringArray(R.array.completion_messages)
        
        // Set random messages
        binding.statusText.text = magicMessages.random()
        binding.completionText.text = completionMessages.random()
    }

    private fun setupFullscreen() {
        FullScreenUtils.setupFullScreen(window, window.decorView)
    }

    private fun startRingAnimation() {
        // Use coroutines for sequential animations instead of Handler chains
        lifecycleScope.launch {
            // Phase 1: Fade in text and ring (0-1s)
            delay(WaterFountainConfig.ANIMATION_FADE_IN_DELAY_MS)
            fadeInElements()

            // Phase 2: Start ring progress (1-7s)
            delay(WaterFountainConfig.ANIMATION_PROGRESS_START_DELAY_MS - WaterFountainConfig.ANIMATION_FADE_IN_DELAY_MS)
            binding.progressRing.animateProgress(WaterFountainConfig.ANIMATION_PROGRESS_DURATION_MS)

            // Phase 3: Ring completion snap (7s)
            delay(WaterFountainConfig.ANIMATION_RING_COMPLETION_DELAY_MS - WaterFountainConfig.ANIMATION_PROGRESS_START_DELAY_MS)
            ringCompletionSnap()

            // Phase 4: Morph to logo (7.5-8.5s)
            delay(WaterFountainConfig.ANIMATION_MORPH_TO_LOGO_DELAY_MS - WaterFountainConfig.ANIMATION_RING_COMPLETION_DELAY_MS)
            morphToLogo()

            // Phase 5: Show completion text + confetti (8.5-9.5s)
            delay(WaterFountainConfig.ANIMATION_SHOW_COMPLETION_DELAY_MS - WaterFountainConfig.ANIMATION_MORPH_TO_LOGO_DELAY_MS)
            showCompletion()

            // Phase 6: Return to main screen (12s)
            delay(WaterFountainConfig.ANIMATION_RETURN_TO_MAIN_DELAY_MS - WaterFountainConfig.ANIMATION_SHOW_COMPLETION_DELAY_MS)
            returnToMainScreen()
        }
    }

    private fun fadeInElements() {
        // Fade in status text with elegant rise - slower and smoother
        binding.statusText.alpha = 0f
        binding.statusText.translationY = 40f
        binding.statusText.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(1500)  // Slower fade
            .setInterpolator(DecelerateInterpolator(3f))  // Much smoother
            .start()

        // Fade in ring container with slight scale - slower and more subtle
        binding.ringContainer.scaleX = 0.90f
        binding.ringContainer.scaleY = 0.90f
        binding.ringContainer.alpha = 0f
        binding.ringContainer.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(1500)  // Slower fade
            .setInterpolator(DecelerateInterpolator(3f))  // Much smoother
            .start()
    }

    private fun ringCompletionSnap() {
        // Glow effect
        binding.progressRing.animateGlow()

        // More dramatic "snap" animation with rotation pulse
        val scaleX = ObjectAnimator.ofFloat(binding.ringContainer, "scaleX", 1f, 1.12f, 0.98f, 1.02f, 1f)
        val scaleY = ObjectAnimator.ofFloat(binding.ringContainer, "scaleY", 1f, 1.12f, 0.98f, 1.02f, 1f)
        val rotation = ObjectAnimator.ofFloat(binding.ringContainer, "rotation", 0f, 5f, -3f, 0f)

        AnimatorSet().apply {
            playTogether(scaleX, scaleY, rotation)
            duration = 600
            interpolator = OvershootInterpolator(2f)
            start()
        }
    }

    private fun morphToLogo() {
        // Fade out status text with elegant fall
        binding.statusText.animate()
            .alpha(0f)
            .translationY(-30f)
            .setDuration(600)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()

        // Shrink the entire ring container dramatically as ring fades
        binding.ringContainer.animate()
            .scaleX(0.65f)
            .scaleY(0.65f)
            .setDuration(800)
            .setInterpolator(DecelerateInterpolator(2.5f))
            .start()

        // Fade out ring with REDUCED rotation for smoother animation
        binding.progressRing.animate()
            .alpha(0f)
            .scaleX(0.7f)
            .scaleY(0.7f)
            .rotation(90f)  // Reduced from 180f to 90f
            .setDuration(800)
            .setInterpolator(DecelerateInterpolator(2.5f))
            .withLayer()  // Use hardware layer during animation
            .start()

        // Fade in logo with dramatic entrance - REDUCED rotation for smooth 60fps
        binding.logoImage.alpha = 0f
        binding.logoImage.scaleX = 0.3f
        binding.logoImage.scaleY = 0.3f
        binding.logoImage.rotation = -45f  // Reduced from -180f to -45f
        
        // Delay logo appearance slightly so ring fades first
        binding.logoImage.postDelayed(logoDelayedRunnable, 200)
    }

    private fun showCompletion() {
        // Play vend sound
        soundManager.playSound(R.raw.vend, 0.8f)
        
        // Dramatic logo pulse with glow effect, then shrink significantly
        val scaleX = ObjectAnimator.ofFloat(binding.logoImage, "scaleX", 1f, 1.15f, 1.05f, 1f, 0.85f, 0.7f)
        val scaleY = ObjectAnimator.ofFloat(binding.logoImage, "scaleY", 1f, 1.15f, 1.05f, 1f, 0.85f, 0.7f)
        val rotation = ObjectAnimator.ofFloat(binding.logoImage, "rotation", 0f, -5f, 5f, 0f, 0f, 0f)

        AnimatorSet().apply {
            playTogether(scaleX, scaleY, rotation)
            duration = 1400 // Extended duration for more dramatic shrink
            interpolator = DecelerateInterpolator(1.8f)
            start()
        }

        // Fade in completion text with elegant rise - start from fully transparent
        binding.completionText.alpha = 0f
        binding.completionText.translationY = 50f
        binding.completionText.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(300) // Delay to let logo settle first
            .setDuration(1000)
            .setInterpolator(DecelerateInterpolator(2.5f))
            .start()

        // Trigger konfetti with slight delay for better timing
        binding.root.postDelayed(confettiDelayedRunnable, 200)
    }

    private fun launchConfetti() {
        // Create multiple konfetti parties across the entire screen with MUCH BIGGER particles
        val parties = listOf(
            // Top left
            Party(
                speed = WaterFountainConfig.CONFETTI_SPEED,
                maxSpeed = WaterFountainConfig.CONFETTI_MAX_SPEED,
                damping = WaterFountainConfig.CONFETTI_DAMPING,
                angle = 270,
                spread = WaterFountainConfig.CONFETTI_SPREAD,
                colors = listOf(0xFFFFFF, 0xF5F3EB, 0xE6E6E6, 0xBDC3C7).map { it.toInt() },
                size = listOf(Size(12), Size(16), Size(20), Size(24)), // Much bigger custom sizes
                emitter = Emitter(duration = WaterFountainConfig.CONFETTI_DURATION_MS, TimeUnit.MILLISECONDS).perSecond(WaterFountainConfig.CONFETTI_PARTICLES_PER_SECOND),
                position = Position.Relative(0.0, 0.0)
            ),
            // Top center
            Party(
                speed = WaterFountainConfig.CONFETTI_SPEED,
                maxSpeed = WaterFountainConfig.CONFETTI_MAX_SPEED,
                damping = WaterFountainConfig.CONFETTI_DAMPING,
                angle = 270,
                spread = WaterFountainConfig.CONFETTI_SPREAD,
                colors = listOf(0xFFFFFF, 0xF5F3EB, 0xE6E6E6, 0xBDC3C7).map { it.toInt() },
                size = listOf(Size(12), Size(16), Size(20), Size(24)),
                emitter = Emitter(duration = WaterFountainConfig.CONFETTI_DURATION_MS, TimeUnit.MILLISECONDS).perSecond(WaterFountainConfig.CONFETTI_PARTICLES_PER_SECOND),
                position = Position.Relative(0.5, 0.0)
            ),
            // Top right
            Party(
                speed = WaterFountainConfig.CONFETTI_SPEED,
                maxSpeed = WaterFountainConfig.CONFETTI_MAX_SPEED,
                damping = WaterFountainConfig.CONFETTI_DAMPING,
                angle = 270,
                spread = WaterFountainConfig.CONFETTI_SPREAD,
                colors = listOf(0xFFFFFF, 0xF5F3EB, 0xE6E6E6, 0xBDC3C7).map { it.toInt() },
                size = listOf(Size(12), Size(16), Size(20), Size(24)),
                emitter = Emitter(duration = WaterFountainConfig.CONFETTI_DURATION_MS, TimeUnit.MILLISECONDS).perSecond(WaterFountainConfig.CONFETTI_PARTICLES_PER_SECOND),
                position = Position.Relative(1.0, 0.0)
            ),
            // Center left
            Party(
                speed = 15f,
                maxSpeed = 35f,
                damping = WaterFountainConfig.CONFETTI_DAMPING,
                angle = 0,
                spread = 180,
                colors = listOf(0xFFFFFF, 0xF5F3EB, 0xE6E6E6, 0xBDC3C7).map { it.toInt() },
                size = listOf(Size(12), Size(16), Size(20), Size(24)),
                emitter = Emitter(duration = WaterFountainConfig.CONFETTI_DURATION_MS, TimeUnit.MILLISECONDS).perSecond(25),
                position = Position.Relative(0.0, 0.5)
            ),
            // Center right
            Party(
                speed = 15f,
                maxSpeed = 35f,
                damping = WaterFountainConfig.CONFETTI_DAMPING,
                angle = 180,
                spread = 180,
                colors = listOf(0xFFFFFF, 0xF5F3EB, 0xE6E6E6, 0xBDC3C7).map { it.toInt() },
                size = listOf(Size(12), Size(16), Size(20), Size(24)),
                emitter = Emitter(duration = WaterFountainConfig.CONFETTI_DURATION_MS, TimeUnit.MILLISECONDS).perSecond(25),
                position = Position.Relative(1.0, 0.5)
            ),
            // Bottom left
            Party(
                speed = WaterFountainConfig.CONFETTI_SPEED,
                maxSpeed = WaterFountainConfig.CONFETTI_MAX_SPEED,
                damping = WaterFountainConfig.CONFETTI_DAMPING,
                angle = 90,
                spread = WaterFountainConfig.CONFETTI_SPREAD,
                colors = listOf(0xFFFFFF, 0xF5F3EB, 0xE6E6E6, 0xBDC3C7).map { it.toInt() },
                size = listOf(Size(12), Size(16), Size(20), Size(24)),
                emitter = Emitter(duration = WaterFountainConfig.CONFETTI_DURATION_MS, TimeUnit.MILLISECONDS).perSecond(WaterFountainConfig.CONFETTI_PARTICLES_PER_SECOND),
                position = Position.Relative(0.0, 1.0)
            ),
            // Bottom center
            Party(
                speed = WaterFountainConfig.CONFETTI_SPEED,
                maxSpeed = WaterFountainConfig.CONFETTI_MAX_SPEED,
                damping = WaterFountainConfig.CONFETTI_DAMPING,
                angle = 90,
                spread = WaterFountainConfig.CONFETTI_SPREAD,
                colors = listOf(0xFFFFFF, 0xF5F3EB, 0xE6E6E6, 0xBDC3C7).map { it.toInt() },
                size = listOf(Size(12), Size(16), Size(20), Size(24)),
                emitter = Emitter(duration = WaterFountainConfig.CONFETTI_DURATION_MS, TimeUnit.MILLISECONDS).perSecond(WaterFountainConfig.CONFETTI_PARTICLES_PER_SECOND),
                position = Position.Relative(0.5, 1.0)
            ),
            // Bottom right
            Party(
                speed = WaterFountainConfig.CONFETTI_SPEED,
                maxSpeed = WaterFountainConfig.CONFETTI_MAX_SPEED,
                damping = WaterFountainConfig.CONFETTI_DAMPING,
                angle = 90,
                spread = WaterFountainConfig.CONFETTI_SPREAD,
                colors = listOf(0xFFFFFF, 0xF5F3EB, 0xE6E6E6, 0xBDC3C7).map { it.toInt() },
                size = listOf(Size(12), Size(16), Size(20), Size(24)),
                emitter = Emitter(duration = WaterFountainConfig.CONFETTI_DURATION_MS, TimeUnit.MILLISECONDS).perSecond(WaterFountainConfig.CONFETTI_PARTICLES_PER_SECOND),
                position = Position.Relative(1.0, 1.0)
            )
        )

        // Start all parties simultaneously for full-screen effect
        binding.konfettiView.start(parties)
    }

    private fun returnToMainScreen() {
        val intent = Intent(this, MainActivity::class.java)
        // Use SINGLE_TOP to reuse existing MainActivity instance for smooth transition
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        // Disable back button during animation - do nothing
        // Intentionally not calling super.onBackPressed() to prevent user from interrupting animation
    }
    
    override fun onPause() {
        super.onPause()
        // Clean up callbacks when activity is paused to prevent memory leaks
        binding.logoImage.removeCallbacks(logoDelayedRunnable)
        binding.root.removeCallbacks(confettiDelayedRunnable)
    }
    
    override fun onDestroy() {
        // Additional cleanup in onDestroy as safety net
        binding.logoImage.removeCallbacks(logoDelayedRunnable)
        binding.root.removeCallbacks(confettiDelayedRunnable)
        
        // Clean up sound manager
        soundManager.release()
        
        super.onDestroy()
    }
}
