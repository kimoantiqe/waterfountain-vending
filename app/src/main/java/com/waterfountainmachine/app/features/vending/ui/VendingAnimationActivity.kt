package com.waterfountainmachine.app.features.vending.ui
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import com.waterfountainmachine.app.core.ui.KioskActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import com.waterfountainmachine.app.R
import com.waterfountainmachine.app.databinding.ActivityVendingAnimationBinding
import com.waterfountainmachine.app.core.utils.AppLog
import com.waterfountainmachine.app.ui.views.RipplePondView
import com.waterfountainmachine.app.core.utils.FullScreenUtils
import com.waterfountainmachine.app.core.utils.SoundManager
import com.waterfountainmachine.app.core.utils.PhoneNumberUtils
import com.waterfountainmachine.app.core.utils.MachineIdProvider
import com.waterfountainmachine.app.core.config.WaterFountainConfig
import com.waterfountainmachine.app.features.vending.viewmodels.VendingViewModel
import com.waterfountainmachine.app.features.vending.viewmodels.VendingUiState
import com.waterfountainmachine.app.core.analytics.AnalyticsManager
import com.waterfountainmachine.app.core.analytics.MachineHealthMonitor
import com.waterfountainmachine.app.WaterFountainApplication
import com.waterfountainmachine.app.core.slot.SlotInventoryManager
import com.waterfountainmachine.app.core.backend.IBackendSlotService
import com.waterfountainmachine.app.core.di.BackendModule
import com.waterfountainmachine.app.core.security.SecurityModule
import com.waterfountainmachine.app.core.utils.ErrorScreenUtil
import com.waterfountainmachine.app.core.utils.UserErrorMessages
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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class VendingAnimationActivity : KioskActivity() {

    override val kioskWindowBackground: Int = R.drawable.gradient_background_main
    // fullScreenRoot left as default (window.decorView) -- matches existing behaviour.

    private lateinit var binding: ActivityVendingAnimationBinding
    private val viewModel: VendingViewModel by viewModels()
    
    // Sound manager
    private lateinit var soundManager: SoundManager
    private lateinit var analyticsManager: AnalyticsManager
    private lateinit var slotInventoryManager: SlotInventoryManager
    private lateinit var backendSlotService: IBackendSlotService

    private var phoneNumber: String? = null
    private var dispensingTime: Long = 0
    private var slot: Int = -1 // -1 indicates slot not yet determined by hardware
    private var vendingStartTime: Long = 0
    private var dispenseStartTime: Long = 0

    // --- Ripple-pond brand-handoff state -------------------------------------
    //
    // The advertiser's logo bitmap and its extracted accent color are decoded
    // off the main thread in [loadAnimationLogo] but NOT swapped into
    // [binding.logoImage] immediately. The bundled WaterFountain logo stays
    // visible through the 15s ripple cadence so the WF brand owns the
    // mere-exposure window; the swap happens inside [morphToLogo] at the
    // crest moment, aligned with the fireworks audio drop, so the advertiser
    // logo arrives ON the peak-end emotional beat (von Restorff / peak-end
    // rule). Skipping the swap leaves the WF logo in place — the correct
    // unbranded-vend fallback.
    private var pendingAdvertiserBitmap: Bitmap? = null
    @ColorInt private var pendingAccentColor: Int? = null
    private var pendingAdvertiserName: String? = null
    private var pendingAnimationMessage: String? = null

    /**
     * Phase 2 "breathing" — a subtle infinite scale loop on the disc so
     * the advertiser hold never reads as a frozen frame. Started after
     * the centerMessage fade-in completes; stopped at Phase 3 (drop) so
     * the discPunch can take over the scale property cleanly. Held as a
     * field for cancellation on Phase 3 entry + onDestroy.
     */
    private var discBreathingAnimator: AnimatorSet? = null

    /**
     * Phase 1 WF logo bob — gentle Y-translation loop so the centered
     * scan-reminder beat reads as alive, not a frozen splash. Started in
     * fadeInPhase1; stopped at fadeOutPhase1 + onDestroy.
     */
    private var wfLogoBobAnimator: ObjectAnimator? = null
    // -------------------------------------------------------------------------
    
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

        binding = ActivityVendingAnimationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Restore slot from savedInstanceState if activity was recreated
        if (savedInstanceState != null) {
            slot = savedInstanceState.getInt("slot", -1)
        } else {
            slot = intent.getIntExtra("slot", -1) // Default -1 if not provided, hardware will determine actual slot
        }
        
        phoneNumber = intent.getStringExtra("phoneNumber")
        dispensingTime = intent.getLongExtra("dispensingTime", 8000)
        
        // Check if machine is remotely disabled before starting vending
        if (bailIfMachineDisabled("blocking vending operation")) return

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
        
        // Campaign data is fetched from backend after successful vend
        // See recordSuccessfulVend() where setCampaignContext() is called with backend response
        // This ensures campaign attribution matches the actual slot inventory (backend is source of truth)
        
        // Track vending started (machine_id and campaign auto-attached)
        vendingStartTime = System.currentTimeMillis()
        dispenseStartTime = System.currentTimeMillis()
        analyticsManager.logVendingStarted()
        
        // Initialize sound manager
        soundManager = SoundManager(this)
        soundManager.loadSound(R.raw.fireworks)
        soundManager.loadSound(R.raw.loading)

        // Setup ViewModel observers
        setupViewModelObservers()

        initializeViews()
        applyFullScreen()
        
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
                // Update activity's slot with actual hardware-determined slot
                slot = state.slot
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
        
        // statusText shows QR reminder (set in XML), progressText shows "Your water is on the way!"
        binding.progressText.text = "Your water is on the way!"
        binding.completionText.text = completionMessages.random()
    }

    override fun onResume() {
        super.onResume()
        // Re-apply immersive mode in case user swiped to reveal nav bar
        com.waterfountainmachine.app.utils.ImmersiveModeHelper.applyImmersiveModeFromSettings(this)
    }

    private fun startRingAnimation() {
        // centerMessage is advertiser-only now — if no animationMessage was
        // supplied, revealCenterMessage() no-ops and the slot stays clean.
        // (Per 5b A: the `message_default` string stays in strings.xml but
        // is no longer wired in here.)

        // Phase A choreography (3 phases: scan-reminder → advertiser reveal
        // + ripple build → mega-crest + confetti drop). All timings live as
        // Config constants so the AV piece is tunable from one place.
        // Coroutines (not Handler chains) so the flow reads top-to-bottom.
        lifecycleScope.launch {
            // ----- Phase 1: scan reminder (t=0 → PHASE1_DURATION_MS) -----
            // Just the WF logo (bobbing) + centered "Scan QR" reminder.
            // No disc, no ripples, no progress text — anticipation starts
            // at the Phase 1→2 reveal.
            delay(WaterFountainConfig.ANIMATION_FADE_IN_DELAY_MS)
            fadeInPhase1()
            soundManager.playLongSound(R.raw.loading, volume = 0.6f, looping = false)

            // ----- Phase 1 → 2 transition: hand off to the advertiser reveal -----
            delay(
                WaterFountainConfig.ANIMATION_PHASE1_DURATION_MS -
                        WaterFountainConfig.ANIMATION_FADE_IN_DELAY_MS
            )
            fadeOutPhase1()
            revealDisc()              // ringContainer + logoImage + progressText fade in
            morphToLogo()             // WF → advertiser bitmap (or stays WF) with slower + overshoot reveal
            revealCenterMessage()     // advertiser animationMessage fades in below disc (no-op if blank)

            // ----- Phase 2: ripple cadence build (PHASE1_DURATION_MS → PHASE3_DROP_OFFSET_MS) -----
            // The cadence kicks in after a short settle so the disc reveal
            // can land before the ripples start radiating. Four beats
            // crescendo toward the Phase 3 crest — see RipplePondView.
            delay(WaterFountainConfig.ANIMATION_PHASE2_CADENCE_START_OFFSET_MS)
            binding.ripplePond.startCadence()

            // Wait out the remainder of Phase 2 (down to the drop moment).
            delay(
                WaterFountainConfig.ANIMATION_PHASE3_DROP_OFFSET_MS -
                        WaterFountainConfig.ANIMATION_PHASE1_DURATION_MS -
                        WaterFountainConfig.ANIMATION_PHASE2_CADENCE_START_OFFSET_MS
            )

            // ----- Phase 3: drop (t=PHASE3_DROP_OFFSET_MS) -----
            // Mega-crest (now the visual climax — was previously at the
            // Phase 1→2 transition) + confetti + completionText crossfade
            // land together on the can drop. fireworks.mp3 fires here so
            // the audio peak matches the visual peak. Timing is fixed;
            // wire off VendingUiState.DispensingComplete if hardware-event
            // sync is needed later.
            soundManager.stopLongSound()
            soundManager.playLongSound(R.raw.fireworks, volume = 0.8f, looping = false)
            discPunch()               // disc scale punch + mega-crest ripple
            launchConfetti()
            swapProgressToCompletionText()

            delay(WaterFountainConfig.ANIMATION_PHASE3_PICKUP_DELAY_MS)
            showPickupReminder()

            delay(WaterFountainConfig.PICKUP_REMINDER_DISPLAY_DURATION_MS)
            hidePickupReminder()
            delay(WaterFountainConfig.PICKUP_REMINDER_FADE_OUT_DURATION_MS)
            returnToMainScreen()
        }
    }

    /**
     * Phase 1 fade-in: WF logo, scan-reminder icon, scan-reminder text.
     * Bob starts on the WF logo as soon as it lands. The disc / ripples /
     * progress text stay hidden through Phase 1 — see [revealDisc] for the
     * Phase 1→2 reveal.
     */
    private fun fadeInPhase1() {
        binding.wfLogoPhase1.alpha = 0f
        binding.wfLogoPhase1.translationY = 40f
        binding.wfLogoPhase1.scaleX = 0.92f
        binding.wfLogoPhase1.scaleY = 0.92f
        binding.wfLogoPhase1.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(1200)
            .setInterpolator(DecelerateInterpolator(3f))
            .withEndAction { startWfLogoBob() }
            .start()

        binding.reminderIcon.alpha = 0f
        binding.reminderIcon.translationY = 40f
        binding.reminderIcon.scaleX = 0.8f
        binding.reminderIcon.scaleY = 0.8f
        binding.reminderIcon.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(1200)
            .setStartDelay(150)
            .setInterpolator(DecelerateInterpolator(3f))
            .start()

        binding.statusText.alpha = 0f
        binding.statusText.translationY = 40f
        binding.statusText.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(1200)
            .setStartDelay(300)
            .setInterpolator(DecelerateInterpolator(3f))
            .start()
    }

    /**
     * Phase 1→2 transition (out): fade the Phase 1 stack so the disc
     * reveal owns the canvas. Bob is cancelled here so the WF logo can
     * exit cleanly without continuing to tick after alpha 0.
     */
    private fun fadeOutPhase1() {
        stopWfLogoBob()
        binding.wfLogoPhase1.animate()
            .alpha(0f)
            .translationY(-30f)
            .setDuration(600)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()
        binding.reminderIcon.animate()
            .alpha(0f)
            .scaleX(0.7f)
            .scaleY(0.7f)
            .setDuration(600)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()
        binding.statusText.animate()
            .alpha(0f)
            .translationY(30f)
            .setDuration(600)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()
    }

    /**
     * Phase 1→2 transition (in): reveal the disc + progress text. Mirrors
     * the elegant slow rise that fadeInPhase1 uses for the scan reminder,
     * so the two phases visually rhyme.
     */
    private fun revealDisc() {
        binding.progressText.alpha = 0f
        binding.progressText.translationY = 40f
        binding.progressText.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(1200)
            .setStartDelay(150)
            .setInterpolator(DecelerateInterpolator(3f))
            .start()

        binding.ringContainer.scaleX = 0.90f
        binding.ringContainer.scaleY = 0.90f
        binding.ringContainer.alpha = 0f
        binding.ringContainer.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(1200)
            .setInterpolator(DecelerateInterpolator(3f))
            .start()

        binding.logoImage.alpha = 0f
        binding.logoImage.scaleX = 0.92f
        binding.logoImage.scaleY = 0.92f
        binding.logoImage.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(1200)
            .setInterpolator(DecelerateInterpolator(3f))
            .start()
    }

    /**
     * Phase 1 bob: gentle Y-translation loop on the WF logo so the
     * centered scan-reminder beat reads as alive. Reduced amplitude
     * (±12dp), ease-in-out, infinite reverse. Cancelled at fadeOutPhase1
     * and onDestroy so it can't tick after the view alpha drops to 0.
     */
    private fun startWfLogoBob() {
        stopWfLogoBob()
        val amplitudePx = -12f * resources.displayMetrics.density
        wfLogoBobAnimator = ObjectAnimator.ofFloat(
            binding.wfLogoPhase1, "translationY", 0f, amplitudePx
        ).apply {
            duration = 1800L
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopWfLogoBob() {
        wfLogoBobAnimator?.cancel()
        wfLogoBobAnimator = null
    }

    /**
     * (Retained for callers / tests; no longer invoked at the Phase 1→2
     * transition since the mega-crest moved to Phase 3 with the confetti
     * drop. Kept available as a generic disc "snap" if a future moment
     * needs visual punctuation without firing the crest.)
     */
    @Suppress("unused")
    private fun ringCompletionSnap() {
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
        // Brand reveal: revealDisc() just brought the bundled WF logo in.
        // Now swap to the advertiser bitmap (if any) and re-enter with a
        // slower, overshoot scale-in so the disc feels intentionally
        // revealed (not just crossfaded). If no advertiser logo was
        // supplied the WF logo stays — the correct fallback so unbranded
        // vends still get the dramatic re-entry.
        binding.logoImage.animate()
            .alpha(0f)
            .scaleX(0.6f)
            .scaleY(0.6f)
            .rotation(20f)
            .setDuration(WaterFountainConfig.ANIMATION_MORPH_TO_LOGO_DELAY_MS)
            .setInterpolator(DecelerateInterpolator(2.5f))
            .withLayer()
            .withEndAction {
                pendingAdvertiserBitmap?.let {
                    binding.logoImage.setImageBitmap(it)
                    binding.logoImage.contentDescription =
                        getString(R.string.sponsorship_logo_content_description)
                }

                binding.logoImage.scaleX = 0.3f
                binding.logoImage.scaleY = 0.3f
                binding.logoImage.rotation = -45f

                binding.logoImage.postDelayed(logoDelayedRunnable, 80)
            }
            .start()
    }

    /**
     * Fade in the centerMessage TextView below the disc. Called at the
     * Phase 1 → 2 transition (crest) so the message lands while the
     * advertiser logo is re-entering. Content is whatever
     * pendingAnimationMessage holds (advertiser-supplied OR the default WF
     * tagline).
     */
    private fun revealCenterMessage() {
        val msg = pendingAnimationMessage?.trim().orEmpty()
        if (msg.isEmpty()) return
        val tv = binding.centerMessage
        tv.text = msg
        tv.alpha = 0f
        tv.translationY = 20f
        tv.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(WaterFountainConfig.ANIMATION_MORPH_TO_LOGO_DELAY_MS)
            .setDuration(WaterFountainConfig.ANIMATION_PHASE2_MESSAGE_FADE_IN_MS)
            .setInterpolator(DecelerateInterpolator(2.5f))
            .withEndAction { startDiscBreathing() }
            .start()
    }

    /**
     * Phase 2 ambient: very subtle scale loop on the disc so the 8s
     * advertiser hold doesn't read as a frozen frame. Cancelled at
     * Phase 3 (discPunch takes over) and on activity teardown.
     */
    private fun startDiscBreathing() {
        stopDiscBreathing()
        val sx = ObjectAnimator.ofFloat(binding.ringContainer, "scaleX", 1.00f, 1.03f).apply {
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
        }
        val sy = ObjectAnimator.ofFloat(binding.ringContainer, "scaleY", 1.00f, 1.03f).apply {
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
        }
        discBreathingAnimator = AnimatorSet().apply {
            playTogether(sx, sy)
            duration = 3200L
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopDiscBreathing() {
        discBreathingAnimator?.cancel()
        discBreathingAnimator = null
    }

    /**
     * Phase 3 disc punctuation: scale punch + the mega-crest ripple land
     * on the can drop alongside the confetti. Stops the Phase 2 breathing
     * first so the scale property isn't fought over. The crest is now the
     * visual climax of the whole flow — cancels any tail-end Phase 2
     * cadence beats so it can't collide with them (see RipplePondView).
     */
    private fun discPunch() {
        stopDiscBreathing()
        binding.ringContainer.scaleX = 1f
        binding.ringContainer.scaleY = 1f
        val sx = ObjectAnimator.ofFloat(binding.ringContainer, "scaleX", 1f, 1.08f, 1f)
        val sy = ObjectAnimator.ofFloat(binding.ringContainer, "scaleY", 1f, 1.08f, 1f)
        AnimatorSet().apply {
            playTogether(sx, sy)
            duration = 500L
            interpolator = OvershootInterpolator(1.6f)
            start()
        }
        binding.ripplePond.crest()
    }

    /**
     * Phase 3 entry: crossfade progressText ("Your water is on the way!")
     * → completionText ("Your water is ready!") at the can-drop moment,
     * and fade out centerMessage so the peak-end stack stays clean (one
     * line of body copy below the disc, not two). The advertiser disc
     * itself stays full-alpha so the brand association lands with the
     * physical reward.
     */
    private fun swapProgressToCompletionText() {
        binding.progressText.animate()
            .alpha(0f)
            .translationY(-30f)
            .setDuration(500)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()

        // Fade out centerMessage in lockstep — it had its 8s Phase 2 hold;
        // now completionText owns the body-copy slot.
        binding.centerMessage.animate()
            .alpha(0f)
            .translationY(-10f)
            .setDuration(500)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()

        binding.completionText.alpha = 0f
        binding.completionText.translationY = 30f
        binding.completionText.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(300)
            .setDuration(900)
            .setInterpolator(DecelerateInterpolator(2.5f))
            .start()
    }

    private fun launchConfetti() {
        // Brand-tinted confetti palette: when we have an extracted accent
        // color, ride it for two of the four particle colors so the
        // celebration belongs to the advertiser. Otherwise fall back to
        // the neutral white/cream palette. See AGENTS.md: "DRY is
        // important" — compute once, reuse across all 8 Party blocks.
        val confettiColors: List<Int> = pendingAccentColor?.let { accent ->
            val light = androidx.core.graphics.ColorUtils.blendARGB(
                accent,
                android.graphics.Color.WHITE,
                0.35f
            )
            listOf(accent, light, 0xFFFFFFFF.toInt(), 0xFFF5F3EB.toInt())
        } ?: listOf(0xFFFFFF, 0xF5F3EB, 0xE6E6E6, 0xBDC3C7).map { it.toInt() }

        // Create multiple konfetti parties across the entire screen with MUCH BIGGER particles
        val parties = listOf(
            // Top left
            Party(
                speed = WaterFountainConfig.CONFETTI_SPEED,
                maxSpeed = WaterFountainConfig.CONFETTI_MAX_SPEED,
                damping = WaterFountainConfig.CONFETTI_DAMPING,
                angle = 270,
                spread = WaterFountainConfig.CONFETTI_SPREAD,
                colors = confettiColors,
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
                colors = confettiColors,
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
                colors = confettiColors,
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
                colors = confettiColors,
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
                colors = confettiColors,
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
                colors = confettiColors,
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
                colors = confettiColors,
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
                colors = confettiColors,
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
        // Track vending completed (only if slot is known and not already tracked)
        if (slot >= 1) {
            val vendingDuration = System.currentTimeMillis() - vendingStartTime
            // NOTE: Do NOT call logVendingCompleted here!
            // It's already called in recordSuccessfulVend() which is called earlier
            // This prevents duplicate vending_completed events in GA4
            
            // Record dispense success in health monitor
            val app = application as WaterFountainApplication
            val healthMonitor = app.getHealthMonitor()
            healthMonitor.recordDispense(slotNumber = slot, success = true)
        } else {
            AppLog.w(TAG, "Skipping vending_completed analytics - slot unknown (slot=$slot)")
        }
        
        // Track journey completed (includes campaign context for per-campaign analysis)
        val journeyStartTime = WaterFountainApplication.journeyStartTime
        val totalJourneyDurationMs = WaterFountainApplication.getJourneyDuration()
        val dispenseDurationMs = System.currentTimeMillis() - dispenseStartTime
        val success = slot >= 1
        analyticsManager.logJourneyCompleted(totalJourneyDurationMs, dispenseDurationMs, journeyStartTime, success)
        
        // Clear session tracking (journey complete)
        WaterFountainApplication.clearSession()
        
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
        // Skip if slot is unknown (error case where no actual dispense occurred)
        if (slot < 1) {
            AppLog.w(TAG, "Skipping vend recording - slot unknown (slot=$slot)")
            return
        }
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Record dispense for health metrics (no extra API calls)
                try {
                    (application as? WaterFountainApplication)?.getHealthMonitor()?.recordDispense(
                        slotNumber = slot,
                        success = true,
                        errorCode = null
                    )
                } catch (e: Exception) {
                    AppLog.w(TAG, "Failed to record dispense to health monitor", e)
                }
                
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
                    
                    // Get isMock flag from analytics manager
                    val isMock = analyticsManager.getIsMock()
                    
                    // Record vend event to backend with phone number (not hash)
                    val result = backendSlotService.recordVendWithSlot(
                        machineId = machineId,
                        slot = slot,
                        phone = normalizedPhone,
                        success = true,
                        totalJourneyDurationMs = totalJourneyDurationMs,
                        dispenseDurationMs = dispensingTimeMs,
                        isMock = isMock
                    )
                    
                    result.fold(
                        onSuccess = { vendResult ->
                            AppLog.i(TAG, "Vend recorded: ${vendResult.eventId}")
                            
                            if (vendResult.campaignId != null) {
                                analyticsManager.setCampaignContext(
                                    campaignId = vendResult.campaignId,
                                    advertiserId = vendResult.advertiserId,
                                    canDesignId = vendResult.canDesignId,
                                    // Names for analytics (no lookup tables needed in BigQuery)
                                    machineName = vendResult.machineName,
                                    campaignName = vendResult.campaignName,
                                    advertiserName = vendResult.advertiserName,
                                    canDesignName = vendResult.canDesignName
                                )
                                // campaign_vend removed - vending_completed already includes campaign context
                            }
                            
                            analyticsManager.logVendingCompleted(slotNumber = slot, durationMs = dispensingTimeMs)

                            // Animation surface: swap the in-ring WaterFountain
                            // logo to the canDesign's brand logo (Q1) and show the
                            // customer-message billboard at reveal (Q2). Both are
                            // best-effort — a missing logo URL leaves the bundled
                            // WaterFountain logo in place; a blank message leaves
                            // the billboard hidden.
                            val logoUrl = vendResult.animationLogo
                            if (!logoUrl.isNullOrBlank()) {
                                loadAnimationLogo(logoUrl)
                            }
                            val msg = vendResult.animationMessage
                            if (!msg.isNullOrBlank()) {
                                val byline = vendResult.advertiserName?.takeIf { it.isNotBlank() }
                                AppLog.i(TAG, "� Live message swap: msg='$msg', advertiser='${byline ?: "none"}'")
                                withContext(Dispatchers.Main) {
                                    // Stash for the crest-time reveal
                                    // (revealCenterMessage reads
                                    // pendingAnimationMessage when Phase 2
                                    // begins). If the centerMessage is
                                    // already visible (vend response landed
                                    // post-crest), crossfade in place.
                                    pendingAnimationMessage = msg
                                    pendingAdvertiserName = byline
                                    if (binding.centerMessage.alpha >= 0.99f) {
                                        binding.centerMessage.animate()
                                            .alpha(0f).setDuration(180)
                                            .withEndAction {
                                                binding.centerMessage.text = msg
                                                binding.centerMessage.animate()
                                                    .alpha(1f).setDuration(420)
                                                    .setInterpolator(DecelerateInterpolator(2.5f))
                                                    .start()
                                            }
                                            .start()
                                    }
                                }
                            } else {
                                AppLog.w(TAG, "🩧 No animationMessage in vend response — default message stays.")
                            }
                        },
                        onFailure = { error ->
                            if (error is com.waterfountainmachine.app.core.backend.BackendSlotService.DailyLimitReachedException) {
                                AppLog.w(TAG, "Daily limit reached - showing error")
                                withContext(Dispatchers.Main) {
                                    com.waterfountainmachine.app.core.utils.ErrorScreenUtil.showDailyLimitReached(this@VendingAnimationActivity)
                                }
                            } else {
                                AppLog.e(TAG, "Failed to record vend", error)
                            }
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
        // Skip recording if slot is unknown (error occurred before slot selection)
        if (slot < 1) {
            AppLog.w(TAG, "Skipping failed vend recording - slot unknown (slot=$slot)")
            // Still log general hardware error to analytics (without slot)
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    analyticsManager.logHardwareError(
                        errorMessage = errorCode ?: "unknown",
                        errorCode = errorCode,
                        slotNumber = null
                    )
                } catch (e: Exception) {
                    AppLog.e(TAG, "Error logging hardware error", e)
                }
            }
            return
        }
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Record dispense failure for health metrics (no extra API calls)
                try {
                    (application as? WaterFountainApplication)?.getHealthMonitor()?.recordDispense(
                        slotNumber = slot,
                        success = false,
                        errorCode = errorCode
                    )
                } catch (e: Exception) {
                    AppLog.w(TAG, "Failed to record dispense to health monitor", e)
                }
                
                // Calculate total journey duration (same as successful vend)
                val journeyStartTime = WaterFountainApplication.journeyStartTime
                val totalJourneyDurationMs = if (journeyStartTime > 0) {
                    System.currentTimeMillis() - journeyStartTime
                } else null
                
                // Calculate dispense attempt duration
                val dispenseDurationMs = System.currentTimeMillis() - dispenseStartTime
                
                // Get machine ID from certificate
                val machineId = MachineIdProvider.getMachineId(this@VendingAnimationActivity)
                
                if (machineId != null) {
                    // Normalize phone to E.164 before sending to backend
                    val normalizedPhone = phoneNumber?.let { PhoneNumberUtils.normalizePhoneNumber(it) }
                    
                    // Get isMock flag from analytics manager
                    val isMock = analyticsManager.getIsMock()
                    
                    // Record failed vend event to backend with phone number (not hash)
                    // Backend will retrieve campaignId/advertiserId from slot document
                    val result = backendSlotService.recordVendWithSlot(
                        machineId = machineId,
                        slot = slot,
                        phone = normalizedPhone,
                        success = false,
                        errorCode = errorCode,
                        totalJourneyDurationMs = totalJourneyDurationMs,
                        dispenseDurationMs = dispenseDurationMs,
                        isMock = isMock
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

    /**
     * Fetch the can design's animation logo over HTTPS and swap it into
     * [logoImage] in place of the bundled WaterFountain logo. Uses
     * [HttpURLConnection] + [BitmapFactory] (no extra image lib) so the app
     * keeps its dependency surface small — see AGENTS.md "engineered enough,
     * not over-engineered." Failures are intentionally swallowed: the
     * existing WaterFountain logo stays in the ring, which is the correct
     * fallback.
     */
    private suspend fun loadAnimationLogo(url: String) {
        val bitmap: Bitmap? = withContext(Dispatchers.IO) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 6000
                    instanceFollowRedirects = true
                }
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                AppLog.w(TAG, "Failed to load animation logo from $url: ${e.message}")
                null
            }
        }
        if (bitmap != null) {
            // Palette extraction — gives the ripple pond + confetti a brand-
            // accurate accent color so the entire AV peak feels like it
            // belongs to the advertiser, not WaterFountain. Off the main
            // thread because synchronous Palette can take 30-80ms on the
            // tablet hardware. Falls back gracefully when no good swatch is
            // found (water-blue default preserves brand-neutral feel).
            val accent: Int? = withContext(Dispatchers.Default) {
                try {
                    val palette = Palette.from(bitmap).maximumColorCount(16).generate()
                    palette.vibrantSwatch?.rgb
                        ?: palette.lightVibrantSwatch?.rgb
                        ?: palette.mutedSwatch?.rgb
                        ?: palette.dominantSwatch?.rgb
                } catch (e: Exception) {
                    AppLog.w(TAG, "Palette extraction failed: ${e.message}")
                    null
                }
            }
            withContext(Dispatchers.Main) {
                // Defensive: activity may be finishing by the time the
                // bitmap decode returns. Skip the swap if so.
                if (isFinishing || isDestroyed) return@withContext

                // Stash for the crest-time brand handoff — do NOT swap
                // into logoImage here. The dramatic morphToLogo()
                // crossfade is what makes the advertiser logo land on
                // the peak-end emotional beat (fireworks audio drop).
                pendingAdvertiserBitmap = bitmap
                pendingAccentColor = accent

                // The ripple pond can pick up the brand color immediately
                // — subsequent normal ripples + the mega crest will all
                // ride the accent.
                binding.ripplePond.setAccentColor(accent)
            }
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        // Disable back button during animation - do nothing
        // Intentionally not calling super.onBackPressed() to prevent user from interrupting animation
    }
    
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Save slot value in case activity is recreated
        outState.putInt("slot", slot)
    }
    
    override fun onDestroy() {
        // When bailIfMachineDisabled() short-circuits onCreate, these
        // lateinit fields were never set. Guard so a remote disable does
        // not turn into a crash on activity teardown.
        if (::analyticsManager.isInitialized) {
            // Clear campaign context when activity is destroyed
            analyticsManager.clearCampaignContext()
        }

        // Additional cleanup in onDestroy as safety net
        binding.logoImage.removeCallbacks(logoDelayedRunnable)
        binding.pickupReminderPanel.removeCallbacks(pickupReminderRunnable)
        binding.pickupReminderPanel.removeCallbacks(chevronPulseRunnable)
        binding.pickupReminderPanel.removeCallbacks(shimmerRunnable)

        // Cancel the Phase 2 breathing loop + Phase 1 bob so they can't
        // tick after teardown.
        stopDiscBreathing()
        stopWfLogoBob()

        // Tear down the ripple pond so it stops any in-flight ripples +
        // cancels its scheduled beats. Safe to call even if cadence
        // never started.
        binding.ripplePond.stop()

        // Clean up sound manager (this will also stop any playing sounds)
        if (::soundManager.isInitialized) soundManager.release()

        super.onDestroy()
    }
}
