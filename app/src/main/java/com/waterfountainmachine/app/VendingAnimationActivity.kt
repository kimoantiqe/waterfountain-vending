package com.waterfountainmachine.app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.*
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import nl.dionsegijn.konfetti.xml.KonfettiView
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class VendingAnimationActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var sodaCan: LinearLayout
    private lateinit var explosionContainer: View
    private lateinit var konfettiView: KonfettiView

    // Explosion particles
    private lateinit var explosionParticle1: View
    private lateinit var explosionParticle2: View
    private lateinit var explosionParticle3: View
    private lateinit var explosionParticle4: View
    private lateinit var explosionParticle5: View

    private var phoneNumber: String? = null
    private var dispensingTime: Long = 0
    private var slot: Int = 1
    private var shakeAnimatorSet: AnimatorSet? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vending_animation)

        phoneNumber = intent.getStringExtra("phoneNumber")
        dispensingTime = intent.getLongExtra("dispensingTime", 0)
        slot = intent.getIntExtra("slot", 1)

        initializeViews()
        setupFullscreen()
        startCanAnimation()
    }

    private fun initializeViews() {
        statusText = findViewById(R.id.statusText)
        subtitleText = findViewById(R.id.subtitleText)
        sodaCan = findViewById(R.id.sodaCan)
        explosionContainer = findViewById(R.id.explosionContainer)
        konfettiView = findViewById(R.id.konfettiView)

        // Explosion particles
        explosionParticle1 = findViewById(R.id.explosionParticle1)
        explosionParticle2 = findViewById(R.id.explosionParticle2)
        explosionParticle3 = findViewById(R.id.explosionParticle3)
        explosionParticle4 = findViewById(R.id.explosionParticle4)
        explosionParticle5 = findViewById(R.id.explosionParticle5)
    }

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun startCanAnimation() {
        // Phase 1: Initial message (0-2s)
        statusText.text = "Preparing your refreshing water..."
        val timeMsg = if (dispensingTime > 0) " (${dispensingTime}ms)" else ""
        subtitleText.text = "Dispensing from slot $slot$timeMsg"

        Handler(Looper.getMainLooper()).postDelayed({
            // Phase 2: Start intense shaking (2-6s)
            statusText.text = "Activating the magic..."
            subtitleText.text = "Building up the energy!"
            startIntenseCanShaking()
        }, 2000)

        Handler(Looper.getMainLooper()).postDelayed({
            // Phase 3: Build up to explosion (6-8s)
            statusText.text = "Almost ready..."
            subtitleText.text = "3... 2... 1..."
            intensifyShaking()
        }, 6000)

        Handler(Looper.getMainLooper()).postDelayed({
            // Phase 4: EXPLOSION! (8-10s)
            statusText.text = "🎉 ENJOY! 🎉"
            subtitleText.text = "Your water is ready!"
            explodeCan()
        }, 8000)

        Handler(Looper.getMainLooper()).postDelayed({
            // Return to main screen
            returnToMainScreen()
        }, 12000)
    }

    private fun startIntenseCanShaking() {
        shakeAnimatorSet?.cancel()

        // Create multiple random shake animations
        val shakeAnimations = mutableListOf<ObjectAnimator>()

        // Random X translations
        val shakeX = ObjectAnimator.ofFloat(
            sodaCan, "translationX",
            0f, getRandomShake(), getRandomShake(), getRandomShake(),
            getRandomShake(), getRandomShake(), getRandomShake(), 0f
        ).apply {
            duration = 800
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
        }

        // Random Y translations
        val shakeY = ObjectAnimator.ofFloat(
            sodaCan, "translationY",
            0f, getRandomShake(), getRandomShake(), getRandomShake(),
            getRandomShake(), getRandomShake(), getRandomShake(), 0f
        ).apply {
            duration = 600
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
        }

        // Random rotation
        val rotation = ObjectAnimator.ofFloat(
            sodaCan, "rotation",
            0f, getRandomRotation(), getRandomRotation(), getRandomRotation(),
            getRandomRotation(), 0f
        ).apply {
            duration = 500
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
        }

        // Random scale effects
        val scaleX = ObjectAnimator.ofFloat(
            sodaCan, "scaleX",
            1f, 1.1f, 0.9f, 1.05f, 0.95f, 1f
        ).apply {
            duration = 700
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
        }

        val scaleY = ObjectAnimator.ofFloat(
            sodaCan, "scaleY",
            1f, 0.9f, 1.1f, 0.95f, 1.05f, 1f
        ).apply {
            duration = 650
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
        }

        shakeAnimatorSet = AnimatorSet().apply {
            playTogether(shakeX, shakeY, rotation, scaleX, scaleY)
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun intensifyShaking() {
        shakeAnimatorSet?.cancel()

        // Much more intense shaking before explosion
        val megaShakeX = ObjectAnimator.ofFloat(
            sodaCan, "translationX",
            0f, getRandomShake() * 2, getRandomShake() * 2, getRandomShake() * 2,
            getRandomShake() * 2, getRandomShake() * 2, 0f
        ).apply {
            duration = 200
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
        }

        val megaShakeY = ObjectAnimator.ofFloat(
            sodaCan, "translationY",
            0f, getRandomShake() * 2, getRandomShake() * 2, getRandomShake() * 2,
            getRandomShake() * 2, getRandomShake() * 2, 0f
        ).apply {
            duration = 150
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
        }

        val megaRotation = ObjectAnimator.ofFloat(
            sodaCan, "rotation",
            0f, getRandomRotation() * 2, getRandomRotation() * 2,
            getRandomRotation() * 2, 0f
        ).apply {
            duration = 100
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
        }

        shakeAnimatorSet = AnimatorSet().apply {
            playTogether(megaShakeX, megaShakeY, megaRotation)
            interpolator = AccelerateInterpolator()
            start()
        }
    }

    private fun explodeCan() {
        // Stop shaking
        shakeAnimatorSet?.cancel()

        // Hide the can with explosion effect
        val canDisappear = ObjectAnimator.ofFloat(sodaCan, "alpha", 1f, 0f).apply {
            duration = 300
        }

        // Show explosion container
        explosionContainer.visibility = View.VISIBLE

        // Animate explosion particles in all directions
        animateExplosionParticles()

        // Massive confetti explosion
        launchMassiveConfetti()

        canDisappear.start()
    }

    private fun animateExplosionParticles() {
        val particles = listOf(explosionParticle1, explosionParticle2, explosionParticle3, explosionParticle4, explosionParticle5)

        particles.forEachIndexed { index, particle ->
            // Random direction and distance for each particle
            val randomX = Random.nextFloat() * 800 - 400 // -400 to 400
            val randomY = Random.nextFloat() * 800 - 400
            val randomRotation = Random.nextFloat() * 720 - 360 // Full spins

            val moveX = ObjectAnimator.ofFloat(particle, "translationX", 0f, randomX)
            val moveY = ObjectAnimator.ofFloat(particle, "translationY", 0f, randomY)
            val rotate = ObjectAnimator.ofFloat(particle, "rotation", 0f, randomRotation)
            val appear = ObjectAnimator.ofFloat(particle, "alpha", 0f, 1f, 0.8f, 0f)
            val scale = ObjectAnimator.ofFloat(particle, "scaleX", 0f, 2f, 1f, 0f)
            val scaleY = ObjectAnimator.ofFloat(particle, "scaleY", 0f, 2f, 1f, 0f)

            val particleSet = AnimatorSet().apply {
                playTogether(moveX, moveY, rotate, appear, scale, scaleY)
                duration = 2000 + (index * 200L) // Stagger the particles
                interpolator = DecelerateInterpolator()
                start()
            }
        }
    }

    private fun launchMassiveConfetti() {
        // Center-explosion confetti that starts from center and spreads outward
        val centerExplosionParty = Party(
            speed = 30f,
            maxSpeed = 100f,
            damping = 0.75f,
            spread = 360, // Full circle explosion from center
            angle = 270, // Start going upward
            colors = listOf(0xfce18a, 0xff726d, 0xf4306d, 0xb48def, 0x00d4aa, 0xff9ff3, 0x54a0ff),
            emitter = Emitter(duration = 1, TimeUnit.SECONDS).max(180),
            position = Position.Relative(0.5, 0.5) // Center explosion
        )

        // First massive center explosion
        konfettiView.start(centerExplosionParty)

        // Second wave from center with different timing
        Handler(Looper.getMainLooper()).postDelayed({
            konfettiView.start(centerExplosionParty.copy(
                emitter = Emitter(duration = 1200, TimeUnit.MILLISECONDS).max(150),
                speed = 25f,
                maxSpeed = 85f
            ))
        }, 400)

        // Third wave - slightly higher speed for wider spread
        Handler(Looper.getMainLooper()).postDelayed({
            konfettiView.start(centerExplosionParty.copy(
                emitter = Emitter(duration = 1500, TimeUnit.MILLISECONDS).max(200),
                speed = 35f,
                maxSpeed = 110f,
                damping = 0.7f // Less damping for wider spread
            ))
        }, 800)

        // Final burst - maximum spread from center
        Handler(Looper.getMainLooper()).postDelayed({
            konfettiView.start(centerExplosionParty.copy(
                emitter = Emitter(duration = 2000, TimeUnit.MILLISECONDS).max(250),
                speed = 40f,
                maxSpeed = 120f,
                damping = 0.65f // Even less damping for maximum spread
            ))
        }, 1200)
    }

    private fun getRandomShake(): Float {
        return Random.nextFloat() * 60f - 30f // -30 to 30 pixels
    }

    private fun getRandomRotation(): Float {
        return Random.nextFloat() * 30f - 15f // -15 to 15 degrees
    }

    private fun returnToMainScreen() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onDestroy() {
        super.onDestroy()
        shakeAnimatorSet?.cancel()
    }
}
