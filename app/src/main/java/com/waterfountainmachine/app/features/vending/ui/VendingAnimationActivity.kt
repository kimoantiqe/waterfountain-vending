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
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.waterfountainmachine.app.R
import com.waterfountainmachine.app.databinding.ActivityVendingAnimationBinding
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.views.ProgressRingView
import com.waterfountainmachine.app.utils.FullScreenUtils
import com.waterfountainmachine.app.utils.SoundManager
import com.waterfountainmachine.app.utils.PhoneNumberUtils
import com.waterfountainmachine.app.utils.MachineIdProvider
import com.waterfountainmachine.app.config.WaterFountainConfig
import com.waterfountainmachine.app.viewmodels.VendingViewModel
import com.waterfountainmachine.app.viewmodels.VendingUiState
import com.waterfountainmachine.app.analytics.AnalyticsManager
import com.waterfountainmachine.app.WaterFountainApplication
import com.waterfountainmachine.app.core.slot.SlotInventoryManager
import com.waterfountainmachine.app.core.backend.IBackendSlotService
import com.waterfountainmachine.app.di.BackendModule
import com.waterfountainmachine.app.security.SecurityModule
import dagger.hilt.android.AndroidEntryPoint
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

@AndroidEntryPoint
class VendingAnimationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVendingAnimationBinding
    private val viewModel: VendingViewModel by viewModels()
    
    // Sound manager
    private lateinit var soundManager: SoundManager
    private lateinit var analyticsManager: AnalyticsManager
    private lateinit var slotInventoryManager: SlotInventoryManager
    private lateinit var backendSlotService: IBackendSlotService

    private var phoneNumber: String? = null
    private var dispensingTime: Long = 0
    private var slot: Int = 1
    private var vendingStartTime: Long = 0
    private var dispenseStartTime: Long = 0
    
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
    
    private val pickupReminderRunnable = Runnable {
        showPickupReminder()
    }
    
    // Chevron animation runnables
    private val chevronPulseRunnable = object : Runnable {
        override fun run() {
            animateChevronPulse()
            binding.pickupReminderPanel.postDelayed(this, WaterFountainConfig.CHEVRON_PULSE_REPEAT_INTERVAL_MS)
        }
    }
    
    private val shimmerRunnable = object : Runnable {
        override fun run() {
            animateShimmer()
            binding.pickupReminderPanel.postDelayed(this, WaterFountainConfig.SHIMMER_REPEAT_INTERVAL_MS)
        }
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
        
        // Initialize analytics
        analyticsManager = AnalyticsManager.getInstance(this)
        analyticsManager.logScreenView("VendingAnimationActivity", "VendingAnimationActivity")
        
        // Initialize slot manager and backend service
        slotInventoryManager = SlotInventoryManager.getInstance(this)
        backendSlotService = BackendModule.getBackendSlotService(this)
        
        // Sync inventory before vend to ensure latest stock levels
        lifecycleScope.launch(Dispatchers.IO) {
            val machineId = MachineIdProvider.getMachineId(this@VendingAnimationActivity)
            if (machineId != null) {
                AppLog.d(TAG, "Syncing inventory before vend...")
                backendSlotService.syncInventoryWithBackend(machineId).fold(
                    onSuccess = { slots ->
                        AppLog.i(TAG, "Pre-vend inventory sync complete: ${slots.size} slots")
                    },
                    onFailure = { error ->
                        AppLog.w(TAG, "Pre-vend sync failed, using cached inventory: ${error.message}")
                    }
                )
            }
        }
        
        // Get campaign data for this vending session (will be attached to all events)
        // TODO: Get actual campaign data from intent extras when campaign flow is implemented
        val campaignId: String? = null // intent.getStringExtra("campaign_id")
        val advertiserId: String? = null // intent.getStringExtra("advertiser_id")
        val canDesignId: String? = null // intent.getStringExtra("can_design_id")
        analyticsManager.setCampaignContext(campaignId, advertiserId, canDesignId)
        
        // Track vending started (machine_id and campaign auto-attached)
        vendingStartTime = System.currentTimeMillis()
        analyticsManager.logVendingStarted(slot)
        
        // Track dispensing started (water pickup = goal)
        val journeyStartTime = WaterFountainApplication.journeyStartTime
        dispenseStartTime = System.currentTimeMillis()
        analyticsManager.logDispensingStarted(slot, journeyStartTime)
        
        // Initialize sound manager
        soundManager = SoundManager(this)
        soundManager.loadSound(R.raw.fireworks)
        soundManager.loadSound(R.raw.loading)

        // Setup ViewModel observers
        setupViewModelObservers()

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
     * Setup ViewModel state observers
     */
    private fun setupViewModelObservers() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                handleVendingState(state)
            }
        }
    }
    
    /**
     * Handle ViewModel state changes
     */
    private fun handleVendingState(state: VendingUiState) {
        when (state) {
            is VendingUiState.Initializing -> {
                AppLog.d(TAG, "Hardware initializing...")
            }
            is VendingUiState.Ready -> {
                AppLog.i(TAG, "Hardware ready, starting dispensing...")
                viewModel.startDispensing()
            }
            is VendingUiState.Dispensing -> {
                AppLog.i(TAG, "Water dispensing in progress...")
            }
            is VendingUiState.DispensingComplete -> {
                AppLog.i(TAG, "Water dispensing completed")
                // Record successful vend with slot tracking
                recordSuccessfulVend(state.slot, state.dispensingTimeMs)
            }
            is VendingUiState.Complete -> {
                AppLog.i(TAG, "All operations complete")
            }
            is VendingUiState.HardwareError -> {
                AppLog.e(TAG, "Hardware error: ${state.message}")
                // Continue with animation anyway - don't break user experience
                viewModel.forceContinue()
            }
            is VendingUiState.DispensingError -> {
                AppLog.e(TAG, "Dispensing error: ${state.message}")
                // Record failed vend without decrementing inventory
                recordFailedVend(state.slot, state.errorCode)
                // Continue with animation anyway
            }
        }
    }
    
    /**
     * Start water dispensing in background while showing animation
     * ViewModel handles the actual hardware interaction
     */
    private fun startWaterDispensing() {
        // ViewModel will automatically check hardware and start dispensing when ready
        // We just need to start the animation
        startRingAnimation()
    }

    private fun initializeViews() {
        // Load random messages from resources
        val completionMessages = resources.getStringArray(R.array.completion_messages)
        
        // Set fixed status text (will show "Your water is on the way!") and random completion message
        binding.statusText.text = "Your water is on the way!"
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

            // Phase 2: Start ring progress (1s)
            delay(WaterFountainConfig.ANIMATION_PROGRESS_START_DELAY_MS - WaterFountainConfig.ANIMATION_FADE_IN_DELAY_MS)
            
            // Start ring animation - 15 seconds total
            val ringDuration = 15000L
            binding.progressRing.animateProgress(ringDuration)
            
            // Wait 500ms before starting loading sound (gives ring time to start visually)
            delay(500L)
            
            // Start the loading sound (NOT looping - you'll provide perfectly looped 15s file)
            soundManager.playLongSound(R.raw.loading, volume = 0.6f, looping = false)

            // Phase 3: Ring completion at 15s mark (adjusted for 500ms sound delay)
            delay(ringDuration - 500L)  // Subtract the 500ms we added before sound started
            
            // Stop loading sound (should be finished by now anyway)
            soundManager.stopLongSound()
            
            // Start fireworks.mp3 (this is the sync point!)
            soundManager.playLongSound(R.raw.fireworks, volume = 0.8f, looping = false)
            
            // Fireworks Timeline:
            // 0:00-0:02 = Ring snap animation (15s-17s total)
            ringCompletionSnap()
            morphToLogo()

            // Phase 4: 0:02-0:04 into fireworks = Logo fully visible (17s-19s total)
            delay(2000L)
            // Logo is already showing from morphToLogo, this is just a timing marker

            // Phase 5: 0:04 into fireworks = Confetti starts (19s total)
            delay(2000L) // 2 more seconds (4s into fireworks)
            showCompletion()

            // Phase 6: Show pickup reminder after 2 seconds (21s total)
            delay(2000L)
            showPickupReminder()

            // Phase 7: Wait 10 seconds for pickup reminder, then return to main (31s total)
            delay(WaterFountainConfig.PICKUP_REMINDER_DISPLAY_DURATION_MS)
            hidePickupReminder()
            delay(WaterFountainConfig.PICKUP_REMINDER_FADE_OUT_DURATION_MS)
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
        // Fireworks sound is already playing, just show the visuals
        
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

    /**
     * Show pickup reminder panel with elegant animations
     */
    private fun showPickupReminder() {
        binding.pickupReminderPanel.visibility = android.view.View.VISIBLE
        
        // Fade in and slide up animation
        binding.pickupReminderPanel.alpha = 0f
        binding.pickupReminderPanel.translationY = 50f
        binding.pickupReminderPanel.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(800)
            .setInterpolator(DecelerateInterpolator(2.5f))
            .withStartAction {
                // Start chevron pulse animation
                binding.pickupReminderPanel.postDelayed(chevronPulseRunnable, 500)
                // Start shimmer animation
                binding.pickupReminderPanel.postDelayed(shimmerRunnable, 800)
            }
            .start()
    }

    /**
     * Hide pickup reminder panel with fade out
     */
    private fun hidePickupReminder() {
        // Stop animations
        binding.pickupReminderPanel.removeCallbacks(chevronPulseRunnable)
        binding.pickupReminderPanel.removeCallbacks(shimmerRunnable)
        
        // Fade out and slide down
        binding.pickupReminderPanel.animate()
            .alpha(0f)
            .translationY(30f)
            .setDuration(600)
            .setInterpolator(DecelerateInterpolator(2f))
            .withEndAction {
                binding.pickupReminderPanel.visibility = android.view.View.GONE
            }
            .start()
    }

    /**
     * Animate chevrons with staggered downward pulse effect
     */
    private fun animateChevronPulse() {
        val chevrons = listOf(
            binding.chevron1,
            binding.chevron2,
            binding.chevron3
        )
        
        chevrons.forEachIndexed { index, chevron ->
            chevron.animate()
                .translationY(12f)
                .alpha(0.4f)
                .setStartDelay((index * 150).toLong())
                .setDuration(600)
                .setInterpolator(DecelerateInterpolator(2f))
                .withEndAction {
                    chevron.animate()
                        .translationY(0f)
                        .alpha(1f)
                        .setDuration(600)
                        .setInterpolator(DecelerateInterpolator(2f))
                        .start()
                }
                .start()
        }
        
        // Add purple tint pulse
        chevrons.forEachIndexed { index, chevron ->
            chevron.postDelayed({
                // Temporarily tint purple during pulse
                chevron.setColorFilter(WaterFountainConfig.COLOR_PURPLE_ACCENT)
                chevron.postDelayed({
                    // Fade back to gray
                    chevron.setColorFilter(WaterFountainConfig.COLOR_DARK_GRAY)
                }, 600)
            }, (index * 150).toLong())
        }
    }

    /**
     * Animate purple shimmer overlay across chevrons
     */
    private fun animateShimmer() {
        binding.shimmerOverlay.translationX = -200f
        binding.shimmerOverlay.alpha = 0f
        
        binding.shimmerOverlay.animate()
            .translationX(200f)
            .alpha(0.6f)
            .setDuration(1500)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .withEndAction {
                binding.shimmerOverlay.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .start()
            }
            .start()
    }

    private fun returnToMainScreen() {
        // Track vending completed (campaign context auto-attached)
        val vendingDuration = System.currentTimeMillis() - vendingStartTime
        analyticsManager.logVendingCompleted(
            slotNumber = slot,
            durationMs = vendingDuration
        )
        
        // Track journey completed (includes campaign context for per-campaign analysis)
        val journeyStartTime = WaterFountainApplication.journeyStartTime
        val totalJourneyDurationMs = WaterFountainApplication.getJourneyDuration()
        val dispenseDurationMs = System.currentTimeMillis() - dispenseStartTime
        analyticsManager.logJourneyCompleted(totalJourneyDurationMs, dispenseDurationMs, journeyStartTime)
        
        // Clear campaign context after vending session ends
        analyticsManager.clearCampaignContext()
        
        // Record dispense success in health monitor
        val app = application as WaterFountainApplication
        val healthMonitor = app.getHealthMonitor()
        healthMonitor.recordDispenseAttempt(success = true)
        
        // Notify ViewModel that animation is complete
        viewModel.onAnimationComplete()
        
        val intent = Intent(this, MainActivity::class.java)
        // Use SINGLE_TOP to reuse existing MainActivity instance for smooth transition
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    /**
     * Record successful vend with slot tracking and backend synchronization
     */
    private fun recordSuccessfulVend(slot: Int, dispensingTimeMs: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Decrement local inventory
                slotInventoryManager.decrementInventory(slot)
                
                // Calculate total journey duration
                val journeyStartTime = WaterFountainApplication.journeyStartTime
                val totalJourneyDurationMs = if (journeyStartTime > 0) {
                    System.currentTimeMillis() - journeyStartTime
                } else null
                
                // Get machine ID from certificate
                val machineId = MachineIdProvider.getMachineId(this@VendingAnimationActivity)
                
                if (machineId != null) {
                    // Normalize phone to E.164 before sending to backend
                    val normalizedPhone = phoneNumber?.let { PhoneNumberUtils.normalizePhoneNumber(it) }
                    
                    // Record vend event to backend with phone number (not hash)
                    val result = backendSlotService.recordVendWithSlot(
                        machineId = machineId,
                        slot = slot,
                        phone = normalizedPhone,
                        success = true,
                        totalJourneyDurationMs = totalJourneyDurationMs,
                        dispenseDurationMs = dispensingTimeMs
                    )
                    
                    result.fold(
                        onSuccess = { vendResult ->
                            AppLog.i(TAG, "Vend event recorded: ${vendResult.eventId}, campaign=${vendResult.campaignId}, advertiser=${vendResult.advertiserId}")
                            
                            // Update campaign context with backend response (in case it differs)
                            if (vendResult.campaignId != null) {
                                analyticsManager.setCampaignContext(
                                    campaignId = vendResult.campaignId,
                                    advertiserId = vendResult.advertiserId,
                                    canDesignId = vendResult.canDesignId
                                )
                            }
                            
                            // Log analytics (campaign context auto-attached)
                            analyticsManager.logVendingCompleted(
                                slotNumber = slot,
                                durationMs = dispensingTimeMs
                            )
                            
                            if (vendResult.campaignId != null) {
                                // Log campaign-attributed vend for ROI tracking
                                analyticsManager.logCampaignVend(slotNumber = slot)
                            }
                        },
                        onFailure = { error ->
                            AppLog.e(TAG, "Failed to record vend event to backend", error)
                            // Continue anyway - local inventory already decremented
                        }
                    )
                } else {
                    AppLog.w(TAG, "Machine ID not found, cannot record vend to backend")
                }
                
                // Check if slot is now low or empty
                val updatedInventory = slotInventoryManager.getSlotInventory(slot)
                if (updatedInventory != null) {
                    if (updatedInventory.remainingBottles == 0) {
                        AppLog.w(TAG, "Slot $slot is now empty")
                        analyticsManager.logSlotEmpty(slot)
                    } else if (updatedInventory.remainingBottles <= (updatedInventory.capacity * 0.2).toInt()) {
                        AppLog.w(TAG, "Slot $slot is low on inventory: ${updatedInventory.remainingBottles} bottles")
                        analyticsManager.logSlotInventoryLow(slot, updatedInventory.remainingBottles)
                    }
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "Error recording successful vend", e)
            }
        }
    }
    
    /**
     * Record failed vend without decrementing inventory
     */
    private fun recordFailedVend(slot: Int, errorCode: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Get machine ID from certificate
                val machineId = MachineIdProvider.getMachineId(this@VendingAnimationActivity)
                
                if (machineId != null) {
                    // Normalize phone to E.164 before sending to backend
                    val normalizedPhone = phoneNumber?.let { PhoneNumberUtils.normalizePhoneNumber(it) }
                    
                    // Record failed vend event to backend with phone number (not hash)
                    val result = backendSlotService.recordVendWithSlot(
                        machineId = machineId,
                        slot = slot,
                        phone = normalizedPhone,
                        success = false,
                        errorCode = errorCode
                    )
                    
                    result.fold(
                        onSuccess = { vendResult ->
                            AppLog.i(TAG, "Failed vend event recorded: ${vendResult.eventId}")
                        },
                        onFailure = { error ->
                            AppLog.e(TAG, "Failed to record failed vend event to backend", error)
                        }
                    )
                } else {
                    AppLog.w(TAG, "Machine ID not found, cannot record vend to backend")
                }
                
                // Log analytics (campaign context auto-attached if vend was campaign-based)
                analyticsManager.logVendingFailed(slot, errorCode ?: "unknown")
            } catch (e: Exception) {
                AppLog.e(TAG, "Error recording failed vend", e)
            }
        }
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
        binding.pickupReminderPanel.removeCallbacks(pickupReminderRunnable)
        binding.pickupReminderPanel.removeCallbacks(chevronPulseRunnable)
        binding.pickupReminderPanel.removeCallbacks(shimmerRunnable)
        
        // Stop loading sound if playing
        soundManager.stopLongSound()
    }
    
    override fun onDestroy() {
        // Additional cleanup in onDestroy as safety net
        binding.logoImage.removeCallbacks(logoDelayedRunnable)
        binding.root.removeCallbacks(confettiDelayedRunnable)
        binding.pickupReminderPanel.removeCallbacks(pickupReminderRunnable)
        binding.pickupReminderPanel.removeCallbacks(chevronPulseRunnable)
        binding.pickupReminderPanel.removeCallbacks(shimmerRunnable)
        
        // Clean up sound manager (this will also stop any playing sounds)
        soundManager.release()
        
        super.onDestroy()
    }
}
