package com.waterfountainmachine.app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.waterfountainmachine.app.views.ProgressRingView
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import nl.dionsegijn.konfetti.core.models.Size
import nl.dionsegijn.konfetti.xml.KonfettiView
import java.util.concurrent.TimeUnit

class VendingAnimationActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var completionText: TextView
    private lateinit var progressRing: ProgressRingView
    private lateinit var logoImage: ImageView
    private lateinit var ringContainer: FrameLayout
    private lateinit var konfettiView: KonfettiView

    private var phoneNumber: String? = null
    private var dispensingTime: Long = 0
    private var slot: Int = 1

    private val magicMessages = listOf(
        "Magic is happening...",
        "Crafting your moment...",
        "Hydration incoming...",
        "Good things take time...",
        "Almost there..."
    )

    private val completionMessages = listOf(
        "Your water is ready!",
        "Grab your refreshment!",
        "Enjoy! On the house.",
        "Stay hydrated!",
        "Freshly dispensed!"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vending_animation)

        phoneNumber = intent.getStringExtra("phoneNumber")
        dispensingTime = intent.getLongExtra("dispensingTime", 8000)
        slot = intent.getIntExtra("slot", 1)

        initializeViews()
        setupFullscreen()
        startRingAnimation()
    }

    private fun initializeViews() {
        statusText = findViewById(R.id.statusText)
        completionText = findViewById(R.id.completionText)
        progressRing = findViewById(R.id.progressRing)
        logoImage = findViewById(R.id.logoImage)
        ringContainer = findViewById(R.id.ringContainer)
        konfettiView = findViewById(R.id.konfettiView)

        // Set random messages
        statusText.text = magicMessages.random()
        completionText.text = completionMessages.random()
    }

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun startRingAnimation() {
        // Phase 1: Fade in text and ring (0-1s)
        Handler(Looper.getMainLooper()).postDelayed({
            fadeInElements()
        }, 200)

        // Phase 2: Start ring progress (1-7s)
        Handler(Looper.getMainLooper()).postDelayed({
            progressRing.animateProgress(6000)
        }, 1000)

        // Phase 3: Ring completion snap (7s)
        Handler(Looper.getMainLooper()).postDelayed({
            ringCompletionSnap()
        }, 7000)

        // Phase 4: Morph to logo (7.5-8.5s)
        Handler(Looper.getMainLooper()).postDelayed({
            morphToLogo()
        }, 7500)

        // Phase 5: Show completion text + confetti (8.5-9.5s)
        Handler(Looper.getMainLooper()).postDelayed({
            showCompletion()
        }, 8500)

        // Phase 6: Return to main screen (12s)
        Handler(Looper.getMainLooper()).postDelayed({
            returnToMainScreen()
        }, 12000)
    }

    private fun fadeInElements() {
        // Fade in status text with elegant rise
        statusText.translationY = 30f
        statusText.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(1000)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()

        // Fade in ring container with slight scale and rotation hint
        ringContainer.scaleX = 0.7f
        ringContainer.scaleY = 0.7f
        ringContainer.alpha = 0f
        ringContainer.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(1000)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()
    }

    private fun ringCompletionSnap() {
        // Glow effect
        progressRing.animateGlow()

        // More dramatic "snap" animation with rotation pulse
        val scaleX = ObjectAnimator.ofFloat(ringContainer, "scaleX", 1f, 1.12f, 0.98f, 1.02f, 1f)
        val scaleY = ObjectAnimator.ofFloat(ringContainer, "scaleY", 1f, 1.12f, 0.98f, 1.02f, 1f)
        val rotation = ObjectAnimator.ofFloat(ringContainer, "rotation", 0f, 5f, -3f, 0f)

        AnimatorSet().apply {
            playTogether(scaleX, scaleY, rotation)
            duration = 600
            interpolator = OvershootInterpolator(2f)
            start()
        }
    }

    private fun morphToLogo() {
        // Fade out status text with elegant fall
        statusText.animate()
            .alpha(0f)
            .translationY(-30f)
            .setDuration(500)
            .start()

        // Fade out ring with rotation
        progressRing.animate()
            .alpha(0f)
            .scaleX(0.7f)
            .scaleY(0.7f)
            .rotation(180f)
            .setDuration(700)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()

        // Fade in logo with dramatic entrance
        logoImage.scaleX = 0.3f
        logoImage.scaleY = 0.3f
        logoImage.rotation = -180f
        logoImage.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .rotation(0f)
            .setDuration(800)
            .setInterpolator(OvershootInterpolator(1.5f))
            .start()
    }

    private fun showCompletion() {
        // Dramatic logo pulse with glow effect
        val scaleX = ObjectAnimator.ofFloat(logoImage, "scaleX", 1f, 1.15f, 1.05f, 1f)
        val scaleY = ObjectAnimator.ofFloat(logoImage, "scaleY", 1f, 1.15f, 1.05f, 1f)
        val rotation = ObjectAnimator.ofFloat(logoImage, "rotation", 0f, -5f, 5f, 0f)

        AnimatorSet().apply {
            playTogether(scaleX, scaleY, rotation)
            duration = 700
            interpolator = OvershootInterpolator(1.5f)
            start()
        }

        // Fade in completion text with elegant rise
        completionText.translationY = 40f
        completionText.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(800)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()

        // Trigger konfetti
        launchConfetti()
    }

    private fun launchConfetti() {
        // Create multiple konfetti parties across the entire screen with BIGGER particles
        val parties = listOf(
            // Top left
            Party(
                speed = 20f,
                maxSpeed = 40f,
                damping = 0.9f,
                angle = 270,
                spread = 90,
                colors = listOf(0xFFFFFF, 0xF5F3EB, 0xE6E6E6, 0xBDC3C7).map { it.toInt() },
                size = listOf(Size.LARGE, Size.LARGE, Size.LARGE), // Make particles bigger
                emitter = Emitter(duration = 2500, TimeUnit.MILLISECONDS).perSecond(25),
                position = Position.Relative(0.0, 0.0)
            ),
            // Top center
            Party(
                speed = 20f,
                maxSpeed = 40f,
                damping = 0.9f,
                angle = 270,
                spread = 90,
                colors = listOf(0xFFFFFF, 0xF5F3EB, 0xE6E6E6, 0xBDC3C7).map { it.toInt() },
                size = listOf(Size.LARGE, Size.LARGE, Size.LARGE),
                emitter = Emitter(duration = 2500, TimeUnit.MILLISECONDS).perSecond(25),
                position = Position.Relative(0.5, 0.0)
            ),
            // Top right
            Party(
                speed = 20f,
                maxSpeed = 40f,
                damping = 0.9f,
                angle = 270,
                spread = 90,
                colors = listOf(0xFFFFFF, 0xF5F3EB, 0xE6E6E6, 0xBDC3C7).map { it.toInt() },
                size = listOf(Size.LARGE, Size.LARGE, Size.LARGE),
                emitter = Emitter(duration = 2500, TimeUnit.MILLISECONDS).perSecond(25),
                position = Position.Relative(1.0, 0.0)
            ),
            // Center left
            Party(
                speed = 15f,
                maxSpeed = 35f,
                damping = 0.9f,
                angle = 0,
                spread = 180,
                colors = listOf(0xFFFFFF, 0xF5F3EB, 0xE6E6E6, 0xBDC3C7).map { it.toInt() },
                size = listOf(Size.LARGE, Size.LARGE, Size.LARGE),
                emitter = Emitter(duration = 2500, TimeUnit.MILLISECONDS).perSecond(20),
                position = Position.Relative(0.0, 0.5)
            ),
            // Center right
            Party(
                speed = 15f,
                maxSpeed = 35f,
                damping = 0.9f,
                angle = 180,
                spread = 180,
                colors = listOf(0xFFFFFF, 0xF5F3EB, 0xE6E6E6, 0xBDC3C7).map { it.toInt() },
                size = listOf(Size.LARGE, Size.LARGE, Size.LARGE),
                emitter = Emitter(duration = 2500, TimeUnit.MILLISECONDS).perSecond(20),
                position = Position.Relative(1.0, 0.5)
            ),
            // Bottom left
            Party(
                speed = 20f,
                maxSpeed = 40f,
                damping = 0.9f,
                angle = 90,
                spread = 90,
                colors = listOf(0xFFFFFF, 0xF5F3EB, 0xE6E6E6, 0xBDC3C7).map { it.toInt() },
                size = listOf(Size.LARGE, Size.LARGE, Size.LARGE),
                emitter = Emitter(duration = 2500, TimeUnit.MILLISECONDS).perSecond(25),
                position = Position.Relative(0.0, 1.0)
            ),
            // Bottom center
            Party(
                speed = 20f,
                maxSpeed = 40f,
                damping = 0.9f,
                angle = 90,
                spread = 90,
                colors = listOf(0xFFFFFF, 0xF5F3EB, 0xE6E6E6, 0xBDC3C7).map { it.toInt() },
                size = listOf(Size.LARGE, Size.LARGE, Size.LARGE),
                emitter = Emitter(duration = 2500, TimeUnit.MILLISECONDS).perSecond(25),
                position = Position.Relative(0.5, 1.0)
            ),
            // Bottom right
            Party(
                speed = 20f,
                maxSpeed = 40f,
                damping = 0.9f,
                angle = 90,
                spread = 90,
                colors = listOf(0xFFFFFF, 0xF5F3EB, 0xE6E6E6, 0xBDC3C7).map { it.toInt() },
                size = listOf(Size.LARGE, Size.LARGE, Size.LARGE),
                emitter = Emitter(duration = 2500, TimeUnit.MILLISECONDS).perSecond(25),
                position = Position.Relative(1.0, 1.0)
            )
        )

        // Start all parties simultaneously for full-screen effect
        konfettiView.start(parties)
    }

    private fun returnToMainScreen() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onBackPressed() {
        // Disable back button during animation
    }
}
