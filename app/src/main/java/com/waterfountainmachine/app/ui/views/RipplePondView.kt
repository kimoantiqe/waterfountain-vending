package com.waterfountainmachine.app.ui.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.annotation.ColorInt
import androidx.annotation.VisibleForTesting
import androidx.core.graphics.ColorUtils
import kotlin.math.hypot
import kotlin.math.max

/**
 * RipplePondView — replaces ProgressRingView for the dispense animation.
 *
 * Renders concentric water-ripple strokes emanating from the disc center
 * during the 15s dispense window, then a single mega-ripple at the
 * brand-handoff crest. See docs/animation-mockups/ for the visual spec and
 * VendingAnimationActivity for the surrounding 31s AV choreography.
 *
 * Psych anchors baked into the cadence (see PlanGod review notes):
 *  - 4 beats with accelerating gaps (3.5s → 1.2s) mirror the goal-gradient
 *    arousal curve so the screen's tempo tracks the viewer's attention.
 *  - Each ripple's 4s expansion + decelerate is tuned to natural exhale
 *    length so motion entrains breathing rhythm (biophilic calm).
 *  - The mega-ripple at t≈14.5s lands on fireworks.mp3's drop and provides
 *    the peak-end Gestalt closure for the experience.
 *
 * The view is intentionally additive: it draws *only* ripples. The disc /
 * logo are separate views layered on top, so this view safely sits behind
 * everything and fills the full screen with clipChildren disabled on
 * ancestors so ripples may expand past the disc.
 *
 * Reduced motion: when [Settings.Global.ANIMATOR_DURATION_SCALE] is 0 the
 * cadence is a no-op and crest fires a single instantaneous flash. Required
 * for Material a11y compliance.
 */
class RipplePondView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** A single in-flight ripple. Pooled in [activeRipples] and recycled. */
    private data class Ripple(
        var startElapsedMs: Long,
        var kind: Kind,
        @ColorInt var color: Int,
        var baseAlpha: Float,
        var strokeWidthPx: Float,
        var startRadiusPx: Float,
        var endRadiusPx: Float,
        var durationMs: Long
    ) {
        enum class Kind { NORMAL, MEGA }
    }

    /**
     * Beat schedule — four building ripples spread across Phase 2's
     * advertiser window. Cadence is started at the Phase 1→2 reveal
     * (after a short settle) and runs through to the Phase 3 drop, where
     * [crest] takes over as the visual climax. Tightening intensities
     * (0.55 → 1.00) crescendo into the crest; the ~2s lull between the
     * last beat and the crest gives the moment room to land.
     */
    @VisibleForTesting
    internal val beatOffsetsMs: LongArray = longArrayOf(
        1_500L,   // Beat 1 — small, signals the cadence has begun
        4_000L,   // Beat 2 — medium
        6_500L,   // Beat 3 — large
        9_000L    // Beat 4 — full strength, primes the crest at ~+2s
    )

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    @ColorInt
    private var accentColor: Int = DEFAULT_ACCENT_COLOR
    private val activeRipples = ArrayList<Ripple>(MAX_ACTIVE_RIPPLES)
    private val pendingPosts = ArrayList<Runnable>(beatOffsetsMs.size + 1)
    private var startedAtUptimeMs: Long = -1L
    private var animator: ValueAnimator? = null

    private val density = context.resources.displayMetrics.density
    private val normalStrokeWidthPx = NORMAL_STROKE_WIDTH_DP * density
    private val megaStrokeWidthPx = MEGA_STROKE_WIDTH_DP * density
    private val startRadiusPx = START_RADIUS_DP * density

    init {
        // Pure additive layer — nothing to clip.
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /**
     * Override the brand accent color used by ripples + crest. Call this from
     * loadAnimationLogo once Palette has produced a swatch. Reverts to the
     * default water-blue when [color] is null.
     */
    fun setAccentColor(@ColorInt color: Int?) {
        accentColor = color?.let { clampForRadialBg(it) } ?: DEFAULT_ACCENT_COLOR
    }

    /**
     * Begin the 3-beat ripple cadence. Idempotent — repeated calls are
     * ignored while a cadence is running. No-op when reduced motion is on.
     */
    @SuppressLint("ObsoleteSdkInt")
    fun startCadence() {
        if (startedAtUptimeMs > 0L) return
        if (isReducedMotion()) return

        startedAtUptimeMs = android.os.SystemClock.uptimeMillis()
        // Each beat scales toward 1.0 so the cadence visibly builds:
        // small → medium → large, then the crest towers above all three.
        val lastIndex = (beatOffsetsMs.size - 1).coerceAtLeast(1)
        beatOffsetsMs.forEachIndexed { index, offset ->
            val intensity = 0.55f + 0.45f * (index.toFloat() / lastIndex.toFloat())
            val r = Runnable { emitRipple(Ripple.Kind.NORMAL, intensity = intensity) }
            pendingPosts.add(r)
            postDelayed(r, offset)
        }
        startDrawLoop()
    }

    /**
     * Fire the mega-ripple. Safe to call at any time; idempotent — any
     * still-scheduled beats are cancelled so the crest cannot collide
     * with a tail-end beat or a future internally-scheduled crest. Lands
     * the peak-end moment for the experience.
     */
    fun crest() {
        pendingPosts.forEach { removeCallbacks(it) }
        pendingPosts.clear()
        if (isReducedMotion()) {
            // Single instantaneous flash via a short, alpha-only ripple.
            emitRipple(Ripple.Kind.MEGA, durationOverrideMs = 250L)
            startDrawLoop()
            return
        }
        emitRipple(Ripple.Kind.MEGA)
        startDrawLoop()
    }

    /**
     * Fire a single mid-intensity ripple. Used by Phase 3 (drop) as the
     * "ring flash" punctuation alongside the disc punch — smaller than
     * [crest] so the WF→advertiser handoff at Phase 2 stays the visual
     * climax of the cadence, but big enough to land the drop moment.
     * Reduced-motion: no-op (the disc punch already conveys the beat).
     */
    fun pulse(intensity: Float = 0.85f) {
        if (isReducedMotion()) return
        emitRipple(Ripple.Kind.NORMAL, intensity = intensity)
        startDrawLoop()
    }

    /** Cancel everything in flight. Always safe; idempotent. */
    fun stop() {
        pendingPosts.forEach { removeCallbacks(it) }
        pendingPosts.clear()
        animator?.cancel()
        animator = null
        activeRipples.clear()
        startedAtUptimeMs = -1L
        invalidate()
    }

    /** Test seam: how many beats are still scheduled. */
    @VisibleForTesting
    internal fun pendingPostCount(): Int = pendingPosts.size

    /** Test seam: how many ripples are currently animating. */
    @VisibleForTesting
    internal fun activeRippleCount(): Int = activeRipples.size

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (activeRipples.isEmpty()) return

        val now = android.os.SystemClock.uptimeMillis()
        val cx = width / 2f
        val cy = height / 2f

        val iter = activeRipples.iterator()
        while (iter.hasNext()) {
            val r = iter.next()
            val elapsed = now - r.startElapsedMs
            if (elapsed >= r.durationMs) {
                iter.remove()
                continue
            }
            val t = elapsed.toFloat() / r.durationMs.toFloat()
            // DecelerateInterpolator(2.5f)-equivalent expansion + linear fade
            val eased = 1f - (1f - t) * (1f - t) * (1f - t)
            val radius = r.startRadiusPx + (r.endRadiusPx - r.startRadiusPx) * eased
            val alpha = (r.baseAlpha * (1f - t)).coerceIn(0f, 1f)

            ripplePaint.color = ColorUtils.setAlphaComponent(r.color, (alpha * 255f).toInt())
            ripplePaint.strokeWidth = r.strokeWidthPx
            canvas.drawCircle(cx, cy, radius, ripplePaint)
        }
    }

    private fun emitRipple(
        kind: Ripple.Kind,
        durationOverrideMs: Long? = null,
        intensity: Float = 1f
    ) {
        // Cap the in-flight pool to keep overdraw bounded even if the host
        // calls crest() multiple times in quick succession.
        while (activeRipples.size >= MAX_ACTIVE_RIPPLES) {
            activeRipples.removeAt(0)
        }
        val end = endRadiusForKind(kind)
        val (dur, baseAlpha, stroke) = when (kind) {
            Ripple.Kind.NORMAL -> Triple(NORMAL_DURATION_MS, NORMAL_BASE_ALPHA, normalStrokeWidthPx)
            Ripple.Kind.MEGA   -> Triple(MEGA_DURATION_MS, MEGA_BASE_ALPHA, megaStrokeWidthPx)
        }
        val clampedIntensity = intensity.coerceIn(0.2f, 1f)
        // Interpolate the travel radius from "just past the disc" toward
        // the full end-radius based on intensity so early beats feel small
        // and the final beat feels large.
        val scaledEnd = startRadiusPx + (end - startRadiusPx) * clampedIntensity
        activeRipples.add(
            Ripple(
                startElapsedMs = android.os.SystemClock.uptimeMillis(),
                kind = kind,
                color = accentColor,
                baseAlpha = baseAlpha * clampedIntensity,
                strokeWidthPx = stroke * (0.6f + 0.4f * clampedIntensity),
                startRadiusPx = startRadiusPx,
                endRadiusPx = scaledEnd,
                durationMs = durationOverrideMs ?: dur
            )
        )
    }

    private fun endRadiusForKind(kind: Ripple.Kind): Float {
        // Use the screen-diagonal so even short/wide kiosks have ripples that
        // travel off-canvas at the same visual pace.
        val diagonal = hypot(width.toFloat(), height.toFloat())
        val factor = when (kind) {
            Ripple.Kind.NORMAL -> NORMAL_END_RADIUS_FACTOR
            Ripple.Kind.MEGA   -> MEGA_END_RADIUS_FACTOR
        }
        return max(startRadiusPx * 1.5f, diagonal * factor)
    }

    private fun startDrawLoop() {
        if (animator?.isRunning == true) return
        // Single long-running animator drives invalidations until the active
        // pool drains. We use a ValueAnimator (rather than postOnAnimation
        // recursion) so the loop self-cancels and never leaks between vends.
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DRAW_LOOP_DURATION_MS
            interpolator = null
            addUpdateListener {
                if (activeRipples.isEmpty()) {
                    cancel()
                    return@addUpdateListener
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                }
            })
            start()
        }
    }

    private fun isReducedMotion(): Boolean {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
        return scale == 0f
    }

    /**
     * Clamp a brand color into a mid-luminance band so it stays legible on
     * the activity's radial WHITE-CENTER / purple-edges background. Pure
     * white / very light colors vanish at the center; near-black colors
     * disappear into the purple edges. We pin L* into [MIN, MAX] without
     * touching hue/chroma so the brand identity is preserved.
     */
    @ColorInt
    private fun clampForRadialBg(@ColorInt color: Int): Int {
        val lab = DoubleArray(3)
        ColorUtils.colorToLAB(color, lab)
        val clamped = lab[0].coerceIn(MIN_LAB_L_FOR_RADIAL_BG, MAX_LAB_L_FOR_RADIAL_BG)
        if (clamped == lab[0]) return color
        return ColorUtils.LABToColor(clamped, lab[1], lab[2])
    }

    companion object {
        // Default accent: dark teal. Reads against the white center of the
        // radial background AND survives the purple edges. Replaces the
        // earlier light water-blue which disappeared on white.
        @ColorInt private const val DEFAULT_ACCENT_COLOR: Int = 0xFF0B6E8C.toInt()

        private const val START_RADIUS_DP = 220f      // outside the visible 540dp logo
        private const val NORMAL_STROKE_WIDTH_DP = 3f
        private const val MEGA_STROKE_WIDTH_DP = 6f

        private const val NORMAL_DURATION_MS = 4_000L
        private const val MEGA_DURATION_MS = 900L

        private const val NORMAL_BASE_ALPHA = 0.70f
        private const val MEGA_BASE_ALPHA = 0.95f

        private const val NORMAL_END_RADIUS_FACTOR = 0.70f
        private const val MEGA_END_RADIUS_FACTOR = 1.40f

        private const val MAX_ACTIVE_RIPPLES = 8

        // Generous bound — the cadence finishes by ~18.5s; the loop self-
        // cancels earlier as soon as the active pool drains.
        private const val DRAW_LOOP_DURATION_MS = 25_000L

        // L* (perceptual lightness, 0..100) clamp band for the radial
        // white-center / purple-edges background. Below MIN, brand colors
        // disappear into the purple edges; above MAX, they wash out against
        // the white center.
        private const val MIN_LAB_L_FOR_RADIAL_BG = 30.0
        private const val MAX_LAB_L_FOR_RADIAL_BG = 55.0
    }
}
