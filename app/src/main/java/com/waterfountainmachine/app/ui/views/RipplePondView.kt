package com.waterfountainmachine.app.ui.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
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
 * Faithful Android port of the soft-UI CSS reference: each ripple is a
 * filled disc the same color as the surface, with a warm-dark drop shadow
 * offset bottom-right and a cool-light highlight offset top-left. As the
 * ripple expands (scale 1.0 → 4.0 over ~2s, ease-in-out), the shadows
 * carry the visible edge outward; the disc itself fades from alpha 1 → 0.
 *
 * Cadence: while [startCadence] is active, a new ripple is emitted every
 * [EMIT_INTERVAL_MS] so three ripples are always in flight — mirroring
 * the CSS demo's three staggered dots — but continuously, throughout
 * Phase 2. The cadence stops on [stop] (or [crest], which also drains the
 * pond before firing its mega ripple).
 *
 * Reduced motion: when [Settings.Global.ANIMATOR_DURATION_SCALE] is 0 the
 * cadence is a no-op and [crest] fires a single instantaneous flash.
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
        val startRadiusPx: Float,
        val endRadiusPx: Float
    )

    private val density = context.resources.displayMetrics.density

    private val baseRadiusPx = BASE_RADIUS_DP * density
    private val shadowOffsetPx = SHADOW_OFFSET_DP * density
    private val shadowBlurPx = SHADOW_BLUR_DP * density

    @ColorInt
    private var accentColor: Int = DEFAULT_ACCENT_COLOR

    private val activeRipples = ArrayList<Ripple>(MAX_ACTIVE_RIPPLES)
    private var startedAtUptimeMs: Long = -1L
    private var animator: ValueAnimator? = null

    /** Test seam — recurring cadence emitter scheduled via postDelayed. */
    private var cadenceEmitter: Runnable? = null

    // Paints — pre-allocated so onDraw never allocates.
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val darkShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(shadowBlurPx, BlurMaskFilter.Blur.NORMAL)
    }
    private val lightShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(shadowBlurPx, BlurMaskFilter.Blur.NORMAL)
    }

    init {
        // BlurMaskFilter requires software layer.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /**
     * Override the brand accent color used by the disc fill. Defaults to
     * the neumorphic surface gray (#E0E5EC). Loud brand colors get pinned
     * into a low-saturation band so the soft-shadow effect survives.
     */
    fun setAccentColor(@ColorInt color: Int?) {
        accentColor = color ?: DEFAULT_ACCENT_COLOR
    }

    /**
     * Begin the continuous ripple cadence. Idempotent — repeated calls
     * are ignored while a cadence is running. No-op when reduced motion
     * is on.
     */
    @SuppressLint("ObsoleteSdkInt")
    fun startCadence() {
        if (startedAtUptimeMs > 0L) return
        if (isReducedMotion()) return

        startedAtUptimeMs = android.os.SystemClock.uptimeMillis()
        // Fire the first ripple immediately so the cadence visibly begins,
        // then a self-rescheduling emitter keeps a steady stream going.
        emitRipple(RIPPLE_DURATION_MS)
        scheduleNextEmit()
        startDrawLoop()
    }

    private fun scheduleNextEmit() {
        val r = Runnable {
            // Guard against stop() racing with the post.
            if (startedAtUptimeMs <= 0L) return@Runnable
            emitRipple(RIPPLE_DURATION_MS)
            scheduleNextEmit()
        }
        cadenceEmitter = r
        postDelayed(r, EMIT_INTERVAL_MS)
    }

    /**
     * Fire the mega-ripple. Cancels the cadence and drains any in-flight
     * ripples so the climax owns the canvas. Safe to call at any time.
     */
    fun crest() {
        cancelCadence()
        activeRipples.clear()
        if (isReducedMotion()) {
            emitRipple(MEGA_DURATION_MS, scaleFactor = MEGA_SCALE_FACTOR, durationOverrideMs = 250L)
            startDrawLoop()
            return
        }
        emitRipple(MEGA_DURATION_MS, scaleFactor = MEGA_SCALE_FACTOR)
        startDrawLoop()
    }

    /**
     * Fire a single mid-intensity ripple. Used by Phase 3 (drop) as a
     * ring-flash punctuation independent of the crest. Reduced-motion:
     * no-op.
     */
    fun pulse() {
        if (isReducedMotion()) return
        emitRipple(RIPPLE_DURATION_MS)
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
        cadenceEmitter?.let { removeCallbacks(it) }
        cadenceEmitter = null
        startedAtUptimeMs = -1L
    }

    /** Test seam: is the recurring emitter currently scheduled? */
    @VisibleForTesting
    internal fun isCadenceScheduled(): Boolean = cadenceEmitter != null

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
            val t = (elapsed.toFloat() / r.durationMs.toFloat()).coerceIn(0f, 1f)
            // ease-in-out (cubic) — matches CSS `ease-in-out`.
            val eased = if (t < 0.5f) 4f * t * t * t else 1f - Math.pow((-2f * t + 2f).toDouble(), 3.0).toFloat() / 2f
            val radius = r.startRadiusPx + (r.endRadiusPx - r.startRadiusPx) * eased
            // Alpha rides the same ease — CSS opacity is in the same animation.
            val alpha = 1f - eased

            // Light shadow (top-left): cool-white blur.
            lightShadowPaint.color = Color.argb(
                (LIGHT_SHADOW_ALPHA * 255f * alpha).toInt().coerceIn(0, 255),
                255, 255, 255
            )
            canvas.drawCircle(cx - shadowOffsetPx, cy - shadowOffsetPx, radius, lightShadowPaint)

            // Dark shadow (bottom-right): warm-dark blur.
            darkShadowPaint.color = Color.argb(
                (DARK_SHADOW_ALPHA * 255f * alpha).toInt().coerceIn(0, 255),
                163, 177, 198
            )
            canvas.drawCircle(cx + shadowOffsetPx, cy + shadowOffsetPx, radius, darkShadowPaint)

            // Disc fill (surface color) on top — alpha fades the whole ring.
            fillPaint.color = ColorUtils.setAlphaComponent(
                accentColor, (alpha * 255f).toInt().coerceIn(0, 255)
            )
            canvas.drawCircle(cx, cy, radius, fillPaint)
        }
    }

    private fun emitRipple(
        duration: Long,
        scaleFactor: Float = SCALE_FACTOR,
        durationOverrideMs: Long? = null
    ) {
        while (activeRipples.size >= MAX_ACTIVE_RIPPLES) {
            activeRipples.removeAt(0)
        }
        activeRipples.add(
            Ripple(
                startElapsedMs = android.os.SystemClock.uptimeMillis(),
                durationMs = durationOverrideMs ?: duration,
                startRadiusPx = baseRadiusPx,
                endRadiusPx = baseRadiusPx * scaleFactor
            )
        )
    }

    private fun startDrawLoop() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DRAW_LOOP_DURATION_MS
            interpolator = null
            addUpdateListener {
                if (activeRipples.isEmpty() && cadenceEmitter == null) {
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
        // Neumorphic surface gray — matches CSS `--gray: #e0e5ec`. The
        // disc reads as the surface itself; the soft shadows do the work.
        @ColorInt private const val DEFAULT_ACCENT_COLOR: Int = 0xFFE0E5EC.toInt()

        // Start disc roughly matches the visible 540dp logoImage radius
        // so each ripple appears to emanate from the disc's silhouette.
        private const val BASE_RADIUS_DP = 270f

        // Scale 1 → 4 over the ripple lifetime — matches CSS
        // `var(--scalingFactor) = 100/25 = 4`.
        private const val SCALE_FACTOR = 4f
        private const val MEGA_SCALE_FACTOR = 6f

        // Soft-shadow geometry — literal CSS values: 5px offset, 10px blur.
        private const val SHADOW_OFFSET_DP = 5f
        private const val SHADOW_BLUR_DP = 10f

        // Shadow alphas — literal CSS values (`rgba(163,177,198,.6)` +
        // `rgba(255,255,255,.5)`).
        private const val DARK_SHADOW_ALPHA = 0.6f
        private const val LIGHT_SHADOW_ALPHA = 0.5f

        // Ripple cadence — 2s per ripple, new one every ~666ms so three
        // are always in flight (matches the CSS demo's three staggered
        // dots, but continuously).
        private const val RIPPLE_DURATION_MS = 2_000L
        private const val EMIT_INTERVAL_MS = 666L

        // Mega ripple at the crest — bigger, slightly longer.
        private const val MEGA_DURATION_MS = 1_200L

        private const val MAX_ACTIVE_RIPPLES = 8

        // Generous bound — the loop self-cancels as soon as the active
        // pool drains AND no cadence is scheduled.
        private const val DRAW_LOOP_DURATION_MS = 60_000L
    }
}
