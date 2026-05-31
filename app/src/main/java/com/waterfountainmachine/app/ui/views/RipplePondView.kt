package com.waterfountainmachine.app.ui.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.VisibleForTesting
import androidx.core.graphics.ColorUtils

/**
 * RipplePondView — neumorphic soft-shadow ripple pond.
 *
 * Each ripple is a pre-rendered "disc with soft shadows" bitmap drawn
 * with a scaling matrix. The bitmap is composed once at sizing time
 * (light TL highlight + dark BR shadow + gray surface fill — literal
 * CSS reference values) so per-frame cost is one drawBitmap on the
 * hardware-accelerated canvas. No per-frame BlurMaskFilter cost.
 *
 * Cadence: [startCadence] schedules a fixed sequence of ripples with
 * decelerating gaps — start slow, accelerate, build anticipation toward
 * the Phase 3 [crest] (the "explosion"). Each ripple in the sequence is
 * progressively larger and more opaque so the build is felt.
 *
 * Anchoring: [anchorToView] pins the emit center to a sibling view (the
 * disc), so ripples emanate from the disc's actual on-screen center
 * regardless of activity layout padding. Re-anchors on layout changes.
 *
 * Reduced motion: when [Settings.Global.ANIMATOR_DURATION_SCALE] is 0
 * the cadence is a no-op and [crest] fires a single instantaneous flash.
 */
class RipplePondView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** A single in-flight ripple. */
    private data class Ripple(
        val startElapsedMs: Long,
        val durationMs: Long,
        /** Starting scale (relative to baseRadius). Defaults to seed/base via emitRipple. */
        val startScale: Float,
        /** Final scale of the disc bitmap relative to its base radius. */
        val endScale: Float,
        /** Peak alpha for this ripple (0..1). */
        val peakAlpha: Float,
        /** Fraction of lifetime spent fading in. 0 = born at peak alpha. */
        val fadeInFrac: Float,
        /** If true, use pure deceleration easing (mega crest); else cubic ease-in-out. */
        val decelerate: Boolean
    )

    private val density = context.resources.displayMetrics.density

    private val baseRadiusPx = BASE_RADIUS_DP * density
    private val shadowOffsetPx = SHADOW_OFFSET_DP * density
    private val shadowBlurPx = SHADOW_BLUR_DP * density

    /**
     * Visible-logo radius the ripples appear to emanate from. Each ripple
     * starts drawn at this radius (not at the full [baseRadiusPx]) and
     * grows outward, so the first frame matches the logo silhouette
     * instead of popping in as a larger disc.
     */
    private var seedRadiusPx: Float = SEED_RADIUS_DP * density

    @ColorInt
    private var fillColor: Int = DEFAULT_FILL_COLOR

    private val activeRipples = ArrayList<Ripple>(MAX_ACTIVE_RIPPLES)
    private var animator: ValueAnimator? = null

    /** Pending cadence beats (scheduled emits). */
    private val pendingPosts = ArrayList<Runnable>(CADENCE_GAPS_MS.size)
    private var cadenceActive = false

    /** Anchor target — emit center follows this view's center if set. */
    private var anchorView: View? = null
    private var anchorPreDrawListener: android.view.ViewTreeObserver.OnPreDrawListener? = null
    private var centerXPx: Float = -1f
    private var centerYPx: Float = -1f

    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }
    private val matrix = Matrix()

    /** Composed once on first sizing — disc + dark BR shadow + light TL shadow. */
    private var rippleBitmap: Bitmap? = null
    private var rippleBitmapSizePx: Int = 0
    /** Half of [rippleBitmapSizePx] — bitmap is square, centered. */
    private var rippleBitmapHalfPx: Float = 0f

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /**
     * Override the disc fill. Defaults to the neumorphic surface gray
     * (#E0E5EC) which is the corner color of bg_neumorphic — they're
     * tuned together so the ripples blend into the perimeter.
     */
    /**
     * Override the seed radius (in pixels). Defaults to [SEED_RADIUS_DP]
     * converted to px — a tuned estimate of the visible logo content
     * radius (the logo PNG has transparent padding inside the 540dp
     * ImageView, so half-width overshoots the visible silhouette).
     */
    fun setSeedRadiusPx(radiusPx: Float) {
        if (radiusPx <= 0f) return
        // No upper clamp: the seed can legitimately exceed [baseRadiusPx]
        // when the activity uses an occluding platform larger than the
        // base disc (see neumorphic_logo_platform.xml). The matrix-scaled
        // bitmap renders fine at any scale.
        seedRadiusPx = radiusPx
        invalidate()
    }

    fun setFillColor(@ColorInt color: Int?) {
        val newColor = color ?: DEFAULT_FILL_COLOR
        if (newColor == fillColor) return
        fillColor = newColor
        // Invalidate the cached bitmap so it gets recomposed on next draw.
        rippleBitmap?.recycle()
        rippleBitmap = null
        invalidate()
    }

    /**
     * Anchor the emit center to the given view's on-screen center.
     * Re-anchors on layout changes. Call once after the binding is
     * inflated and the target view is part of the same window.
     */
    fun anchorToView(target: View) {
        // Detach any prior listener.
        anchorPreDrawListener?.let { l ->
            anchorView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(l)
        }
        anchorView = target
        // Re-anchor every frame so ancestor layout shifts (LinearLayout
        // re-centering when message text grows) and any in-flight
        // transforms on the target both stay tracked. Cheap: two
        // getLocationInWindow calls + an invalidate when the center moved.
        val listener = android.view.ViewTreeObserver.OnPreDrawListener {
            updateAnchorFromView()
            true
        }
        anchorPreDrawListener = listener
        target.viewTreeObserver.addOnPreDrawListener(listener)
        // Compute eagerly in case the target is already laid out.
        post { updateAnchorFromView() }
    }

    private fun updateAnchorFromView() {
        val target = anchorView ?: return
        if (target.width == 0 || target.height == 0) return
        val targetLoc = IntArray(2)
        val selfLoc = IntArray(2)
        target.getLocationInWindow(targetLoc)
        getLocationInWindow(selfLoc)
        val newCx = (targetLoc[0] - selfLoc[0]) + target.width / 2f
        val newCy = (targetLoc[1] - selfLoc[1]) + target.height / 2f
        if (newCx != centerXPx || newCy != centerYPx) {
            centerXPx = newCx
            centerYPx = newCy
            invalidate()
        }
    }

    /**
     * Begin the crescendo cadence — schedules a fixed sequence of
     * ripples with decelerating gaps so anticipation builds toward the
     * Phase 3 crest. Idempotent; no-op when reduced motion is on.
     */
    @SuppressLint("ObsoleteSdkInt")
    fun startCadence() {
        if (cadenceActive) return
        if (isReducedMotion()) return

        cadenceActive = true
        // First ripple fires synchronously so the cadence visibly begins.
        // Subsequent beats are scheduled with decelerating gaps; intensity
        // (scale + alpha) ramps so each beat feels larger than the last,
        // priming the explosion at the end.
        val total = CADENCE_GAPS_MS.size + 1
        fun intensityFor(index: Int): Float =
            MIN_INTENSITY + (1f - MIN_INTENSITY) * (index.toFloat() / (total - 1).toFloat())
        emitRipple(RIPPLE_DURATION_MS, intensityFor(0))
        var cumulativeDelay = 0L
        for (index in 1 until total) {
            cumulativeDelay += CADENCE_GAPS_MS[index - 1]
            val intensity = intensityFor(index)
            val r = Runnable {
                if (!cadenceActive) return@Runnable
                emitRipple(RIPPLE_DURATION_MS, intensity)
            }
            // Tracked for cancellation only; once it fires, removeCallbacks
            // in cancelCadence is a harmless no-op for it.
            pendingPosts.add(r)
            postDelayed(r, cumulativeDelay)
        }
        startDrawLoop()
    }

    /**
     * Fire the mega-ripple (the "explosion"). Cancels the cadence so no
     * further beats are scheduled, but intentionally leaves any in-flight
     * cadence ripples animating — they blend into the climax instead of
     * being cut off.
     */
    fun crest() {
        cancelCadence()
        // Intentionally do NOT clear activeRipples — letting the last
        // cadence beats finish naturally blends the build into the
        // explosion instead of cutting them off.
        if (isReducedMotion()) {
            emitRipple(durationOverrideMs = 250L, intensity = 1f, scaleOverride = MEGA_SCALE_FACTOR)
            startDrawLoop()
            return
        }
        // Mega ripple: starts at the platform edge (no tiny seed dot),
        // born at full brightness, slow + long expansion. Reads as a
        // shockwave continuing the cadence, not a separate flash.
        emitRipple(
            durationOverrideMs = MEGA_DURATION_MS,
            intensity = 1f,
            scaleOverride = MEGA_SCALE_FACTOR,
            startScaleOverride = MEGA_START_SCALE,
            fadeInFracOverride = MEGA_FADE_IN_FRAC,
            decelerate = true
        )
        startDrawLoop()
    }

    /**
     * Fire a single mid-intensity ripple. Used by Phase 3 (drop) as a
     * ring-flash punctuation independent of the crest. Reduced-motion:
     * no-op.
     */
    fun pulse() {
        if (isReducedMotion()) return
        emitRipple(RIPPLE_DURATION_MS, intensity = 0.85f)
        startDrawLoop()
    }

    /** Cancel everything in flight. Always safe; idempotent. */
    fun stop() {
        cancelCadence()
        animator?.cancel()
        animator = null
        activeRipples.clear()
        invalidate()
    }

    private fun cancelCadence() {
        pendingPosts.forEach { removeCallbacks(it) }
        pendingPosts.clear()
        cadenceActive = false
    }

    /** Test seam: is the cadence currently active? */
    @VisibleForTesting
    internal fun isCadenceScheduled(): Boolean = cadenceActive

    /** Test seam: how many cadence beats are still pending. */
    @VisibleForTesting
    internal fun pendingBeatCount(): Int = pendingPosts.size

    /** Test seam: how many ripples are currently animating. */
    @VisibleForTesting
    internal fun activeRippleCount(): Int = activeRipples.size

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Fall back to view-center if no anchor was provided.
        if (anchorView == null) {
            centerXPx = w / 2f
            centerYPx = h / 2f
        }
        // Bitmap is sized for the base disc — scale handles the rest.
        ensureBitmap()
    }

    override fun onDetachedFromWindow() {
        stop()
        anchorPreDrawListener?.let { l ->
            anchorView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(l)
        }
        anchorPreDrawListener = null
        anchorView = null
        rippleBitmap?.recycle()
        rippleBitmap = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (activeRipples.isEmpty()) return
        val bitmap = rippleBitmap ?: run {
            ensureBitmap()
            rippleBitmap ?: return
        }
        if (centerXPx < 0f || centerYPx < 0f) return

        val now = android.os.SystemClock.uptimeMillis()
        val iter = activeRipples.iterator()
        while (iter.hasNext()) {
            val r = iter.next()
            val elapsed = now - r.startElapsedMs
            if (elapsed >= r.durationMs) {
                iter.remove()
                continue
            }
            val t = (elapsed.toFloat() / r.durationMs.toFloat()).coerceIn(0f, 1f)
            // Cubic ease-in-out for cadence; pure decelerate for the
            // mega crest (grows fast, settles — no acceleration phase
            // that reads as a "shrink-back" at the end).
            val eased = if (r.decelerate) {
                1f - (1f - t) * (1f - t)
            } else if (t < 0.5f) {
                4f * t * t * t
            } else {
                1f - Math.pow((-2f * t + 2f).toDouble(), 3.0).toFloat() / 2f
            }

            // Per-ripple start scale: cadence beats start near the seed
            // (logo); the crest starts at the platform edge so the
            // explosion launches from the disc, not a dot.
            val scale = r.startScale + (r.endScale - r.startScale) * eased
            // Triangular envelope: fade-in across the first fadeInFrac of
            // the ripple's life, then fade-out across the rest. The crest
            // uses a near-zero fadeInFrac so it's born at peak brightness.
            val alphaFrac = if (r.fadeInFrac > 0f && t < r.fadeInFrac) {
                t / r.fadeInFrac
            } else {
                1f - (t - r.fadeInFrac) / (1f - r.fadeInFrac)
            }
            val alpha = (r.peakAlpha * alphaFrac).coerceIn(0f, 1f)

            matrix.reset()
            matrix.postTranslate(-rippleBitmapHalfPx, -rippleBitmapHalfPx)
            matrix.postScale(scale, scale)
            matrix.postTranslate(centerXPx, centerYPx)

            drawPaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
            canvas.drawBitmap(bitmap, matrix, drawPaint)
        }
    }

    /**
     * Compose the disc-with-shadows bitmap once. Size is chosen so the
     * blur and shadow offset fit fully inside the bitmap at base scale.
     */
    private fun ensureBitmap() {
        if (rippleBitmap != null) return
        // Padding accommodates blur radius + shadow offset on both sides.
        val padding = shadowBlurPx + shadowOffsetPx + 4f * density
        val size = ((baseRadiusPx + padding) * 2f).toInt().coerceAtLeast(4)
        rippleBitmapSizePx = size
        rippleBitmapHalfPx = size / 2f

        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val r = baseRadiusPx

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(shadowBlurPx, BlurMaskFilter.Blur.NORMAL)
        }
        // Light highlight (top-left).
        shadowPaint.color = Color.argb(
            (LIGHT_SHADOW_ALPHA * 255f).toInt(), 255, 255, 255
        )
        canvas.drawCircle(cx - shadowOffsetPx, cy - shadowOffsetPx, r, shadowPaint)
        // Dark drop shadow (bottom-right).
        shadowPaint.color = Color.argb(
            (DARK_SHADOW_ALPHA * 255f).toInt(), 163, 177, 198
        )
        canvas.drawCircle(cx + shadowOffsetPx, cy + shadowOffsetPx, r, shadowPaint)
        // Surface fill on top — same color as the background corners
        // (#E0E5EC) so the disc reads as the surface itself.
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fillColor
        }
        canvas.drawCircle(cx, cy, r, fillPaint)

        rippleBitmap = bmp
    }

    private fun emitRipple(
        durationOverrideMs: Long = RIPPLE_DURATION_MS,
        intensity: Float = 1f,
        scaleOverride: Float? = null,
        startScaleOverride: Float? = null,
        fadeInFracOverride: Float? = null,
        decelerate: Boolean = false
    ) {
        while (activeRipples.size >= MAX_ACTIVE_RIPPLES) {
            activeRipples.removeAt(0)
        }
        val clamped = intensity.coerceIn(0.2f, 1f)
        val endScale = scaleOverride
            ?: (MIN_SCALE_FACTOR + (SCALE_FACTOR - MIN_SCALE_FACTOR) * clamped)
        val startScale = startScaleOverride ?: (seedRadiusPx / baseRadiusPx)
        val fadeInFrac = fadeInFracOverride ?: FADE_IN_FRAC
        activeRipples.add(
            Ripple(
                startElapsedMs = android.os.SystemClock.uptimeMillis(),
                durationMs = durationOverrideMs,
                startScale = startScale,
                endScale = endScale,
                peakAlpha = clamped,
                fadeInFrac = fadeInFrac,
                decelerate = decelerate
            )
        )
    }

    private fun startDrawLoop() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DRAW_LOOP_DURATION_MS
            interpolator = null
            addUpdateListener {
                if (activeRipples.isEmpty() && !cadenceActive) {
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

    companion object {
        // Neumorphic surface gray — matches bg_neumorphic's corner color
        // so ripples blend seamlessly into the background perimeter.
        @ColorInt private const val DEFAULT_FILL_COLOR: Int = 0xFFC8CFDB.toInt()

        // Base disc radius — full ImageView radius (logoImage is 540dp).
        // This is the disc's geometric size; the seed (visible logo)
        // radius is smaller — see [SEED_RADIUS_DP].
        private const val BASE_RADIUS_DP = 270f

        // Visible logo radius — seed scale for each ripple. Small
        // (~80dp) so ripples appear to be born from a point at the logo's
        // center and visibly grow outward through and past the logo
        // silhouette. Overridable per-instance via [setSeedRadiusPx].
        private const val SEED_RADIUS_DP = 80f

        // Fraction of each ripple's lifetime spent fading IN before the
        // longer fade-out begins. Triangular envelope so ripples never
        // pop on at full opacity.
        private const val FADE_IN_FRAC = 0.18f

        // Scale envelope (multipliers on [baseRadiusPx]). Cadence ripples
        // grow past the seed (typically the occluding logo platform) to
        // become visible halos. Tuned so the smallest beat clears the
        // platform with room to spare, and the largest cadence beat is
        // still meaningfully smaller than the MEGA crest.
        private const val MIN_SCALE_FACTOR = 2.0f
        private const val SCALE_FACTOR = 4.5f

        // Soft-shadow geometry — literal CSS values: 5px offset, 10px blur.
        private const val SHADOW_OFFSET_DP = 5f
        private const val SHADOW_BLUR_DP = 10f

        // Shadow alphas — literal CSS values (rgba(163,177,198,.6) +
        // rgba(255,255,255,.5)).
        private const val DARK_SHADOW_ALPHA = 0.6f
        private const val LIGHT_SHADOW_ALPHA = 0.5f

        // Ripple lifetime — 2s per ripple, matches CSS animation-duration.
        private const val RIPPLE_DURATION_MS = 2_000L

        // Mega ripple at the crest — the climax. Slow, gentle expansion
        // (no ease-in/ease-out "shrink-back" feel), launched from the
        // platform edge at full brightness so it reads as a shockwave
        // continuing the cadence. End scale kept moderate so the
        // disc-with-shadows bitmap doesn't magnify its inner asymmetry.
        private const val MEGA_DURATION_MS = 2_600L
        private const val MEGA_SCALE_FACTOR = 5f
        private const val MEGA_START_SCALE = MIN_SCALE_FACTOR
        private const val MEGA_FADE_IN_FRAC = 0.04f

        /**
         * Crescendo schedule — gaps between successive cadence ripples.
         * Decelerating gaps = accelerating tempo. Total cadence span
         * = sum(CADENCE_GAPS_MS) ≈ 9.2s, leaving ~2.3s pause before the
         * Phase 3 crest so the explosion lands with breathing room.
         * Ripple count = CADENCE_GAPS_MS.size + 1 = 8.
         */
        private val CADENCE_GAPS_MS = longArrayOf(
            1800L,  // beat 1 → 2 (slow start — logo already settled)
            1400L,  // beat 2 → 3
            1100L,  // beat 3 → 4
            900L,   // beat 4 → 5
            750L,   // beat 5 → 6
            650L,   // beat 6 → 7
            550L    // beat 7 → 8 (fastest before the climax)
        )

        // Intensity floor — even the first beat is visible enough to read.
        private const val MIN_INTENSITY = 0.35f

        private const val MAX_ACTIVE_RIPPLES = 8

        // Generous bound — the loop self-cancels as soon as the active
        // pool drains AND no cadence is pending.
        private const val DRAW_LOOP_DURATION_MS = 60_000L
    }
}
