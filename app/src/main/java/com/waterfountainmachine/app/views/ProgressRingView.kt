package com.waterfountainmachine.app.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.sin

class ProgressRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        // Ring dimensions
        private const val RING_STROKE_WIDTH = 50f
        private const val INNER_GLOW_STROKE_WIDTH = 70f
        private const val OUTER_GLOW_STROKE_WIDTH = 100f
        private const val SOFT_EDGE_STROKE_WIDTH = 60f
        
        // Blur radii
        private const val INNER_GLOW_BLUR_RADIUS = 35f
        private const val OUTER_GLOW_BLUR_RADIUS = 60f
        private const val SOFT_EDGE_BLUR_RADIUS = 20f
        
        // Alpha values
        private const val BACKGROUND_RING_ALPHA = 30
        private const val OUTER_GLOW_MAX_ALPHA = 140
        private const val INNER_GLOW_MAX_ALPHA = 190
        
        // Padding to prevent clipping
        private const val VIEW_PADDING = 120
        
        // Animation thresholds
        private const val RADIUS_SCALE_FACTOR = 0.42f
        private const val GLOW_RADIUS_OFFSET = 150f
    }

    // Background ring - subtle and elegant
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = RING_STROKE_WIDTH
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#555555")
        alpha = BACKGROUND_RING_ALPHA
    }

    // Progress ring - bold and beautiful with grayish-purple colors
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = RING_STROKE_WIDTH
        strokeCap = Paint.Cap.ROUND
    }

    // Inner glow layer
    private val innerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = INNER_GLOW_STROKE_WIDTH
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(INNER_GLOW_BLUR_RADIUS, BlurMaskFilter.Blur.NORMAL)
    }

    // Outer glow layer
    private val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = OUTER_GLOW_STROKE_WIDTH
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(OUTER_GLOW_BLUR_RADIUS, BlurMaskFilter.Blur.NORMAL)
    }
    
    // Soft edge paint for smooth transitions at start/end
    private val softEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = SOFT_EDGE_STROKE_WIDTH
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(SOFT_EDGE_BLUR_RADIUS, BlurMaskFilter.Blur.NORMAL)
    }

    // Completion burst glow
    private val burstGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var progress = 0f
    private var glowAlpha = 0f
    private var progressAlpha = 0f // Add alpha for smooth fade-in
    private val rect = RectF()
    
    init {
        // Enable hardware acceleration for smooth gradients
        setLayerType(LAYER_TYPE_HARDWARE, null)
        
        // Add padding to prevent clipping of glows and effects
        setPadding(VIEW_PADDING, VIEW_PADDING, VIEW_PADDING, VIEW_PADDING)
    }

    fun animateProgress(duration: Long = 5000) {
        // Animate progress from 0 to 100
        ValueAnimator.ofFloat(0f, 100f).apply {
            this.duration = duration
            // Use a smoother interpolator for buttery animation
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                progress = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
        
        // Simultaneously fade in the progress ring over first 2.5 seconds (longer fade)
        ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = 2500
            interpolator = DecelerateInterpolator(3f) // Even smoother curve
            addUpdateListener { animator ->
                progressAlpha = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun animateGlow() {
        ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 800
            addUpdateListener { animator ->
                glowAlpha = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Account for padding
        val availableWidth = width - paddingLeft - paddingRight
        val availableHeight = height - paddingTop - paddingBottom
        
        val centerX = paddingLeft + availableWidth / 2f
        val centerY = paddingTop + availableHeight / 2f
        
        // Use smaller radius to account for padding and prevent clipping
        val radius = (availableWidth.coerceAtMost(availableHeight) * RADIUS_SCALE_FACTOR)

        rect.set(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius
        )

        // Draw completion burst glow with cream colors - CIRCULAR!
        if (glowAlpha > 0) {
            // Save canvas state
            val saveCount = canvas.save()
            
            // Create circular clipping path for burst glow
            val glowRadius = radius + GLOW_RADIUS_OFFSET
            
            burstGlowPaint.shader = RadialGradient(
                centerX, centerY, glowRadius,
                intArrayOf(
                    Color.argb((glowAlpha * 200).toInt(), 196, 181, 242),  // Lavender purple #C4B5F2
                    Color.argb((glowAlpha * 150).toInt(), 175, 160, 238),  // Medium lavender #AFA0EE
                    Color.argb((glowAlpha * 80).toInt(), 155, 138, 196),   // Muted purple-gray
                    Color.argb(0, 123, 107, 168)
                ),
                floatArrayOf(0f, 0.4f, 0.7f, 1f),
                Shader.TileMode.CLAMP
            )
            
            // Draw the burst glow as a pure circle
            canvas.drawCircle(centerX, centerY, glowRadius, burstGlowPaint)
            
            // Restore canvas
            canvas.restoreToCount(saveCount)
        }

        // Draw background ring
        canvas.drawCircle(centerX, centerY, radius, ringPaint)

        // Draw progress with subtle light-to-dark gradient
        if (progress > 0) {
            val sweepAngle = (progress / 100f) * 360f
            
            // Create gradient that maps ONLY to the drawn arc, not the full circle
            // This prevents the "tail" effect by ensuring the gradient is relative to the actual progress
            val progressRatio = progress / 100f
            
            // Adjust gradient positions to map only to the drawn portion
            // The last color should appear at the end of the sweep, not wrap around
            val colors = intArrayOf(
                Color.parseColor("#D8CFFF"),  // Light lavender at start (top of circle)
                Color.parseColor("#C4B5F2"),  // Medium-light lavender
                Color.parseColor("#AFA0EE"),  // Medium lavender (matches buttons)
                Color.parseColor("#9B8AC4"),  // Darker lavender-gray
                Color.parseColor("#8B7BB8")   // Darkest lavender at end
            )
            
            // Map gradient positions to the actual sweep angle
            // Fill the rest of the circle with the start color to avoid tail
            val adjustedColors = intArrayOf(
                Color.parseColor("#D8CFFF"),  // Start color at 0
                Color.parseColor("#C4B5F2"),  // 25% of progress
                Color.parseColor("#AFA0EE"),  // 50% of progress  
                Color.parseColor("#9B8AC4"),  // 75% of progress
                Color.parseColor("#8B7BB8"),  // 100% of progress (end)
                Color.parseColor("#D8CFFF")   // Fill rest of circle with start color
            )
            val adjustedPositions = floatArrayOf(
                0f,
                0.25f * progressRatio,
                0.5f * progressRatio,
                0.75f * progressRatio,
                progressRatio,
                1f
            )
            
            // Rotate gradient to start from top
            val matrix = Matrix()
            matrix.postRotate(-90f, centerX, centerY)
            
            val gradient = SweepGradient(centerX, centerY, adjustedColors, adjustedPositions)
            gradient.setLocalMatrix(matrix)
            
            // Calculate smooth fade-in for color saturation (first 8% of progress)
            // This fades in the purple color gradually instead of appearing suddenly
            val colorFadeProgress = (progress / 8f).coerceIn(0f, 1f)
            val combinedAlpha = colorFadeProgress * progressAlpha
            
            // Draw outer glow (most diffuse) - with smooth fade-in
            outerGlowPaint.shader = gradient
            outerGlowPaint.alpha = (OUTER_GLOW_MAX_ALPHA * combinedAlpha).toInt()
            canvas.drawArc(rect, -90f, sweepAngle, false, outerGlowPaint)
            
            // Draw inner glow (brighter) - with smooth fade-in
            innerGlowPaint.shader = gradient
            innerGlowPaint.alpha = (INNER_GLOW_MAX_ALPHA * combinedAlpha).toInt()
            canvas.drawArc(rect, -90f, sweepAngle, false, innerGlowPaint)
            
            // Draw main progress ring (crisp and bold) - with smooth fade-in
            progressPaint.shader = gradient
            progressPaint.alpha = (255 * combinedAlpha).toInt()
            canvas.drawArc(rect, -90f, sweepAngle, false, progressPaint)
            
            // Add subtle highlight at the leading edge for depth - with smooth fade-in and fade-out
            if (progress > 2f && progress < 99.5f) {
                val highlightAngle = -90f + sweepAngle
                val highlightRad = Math.toRadians(highlightAngle.toDouble())
                val highlightX = centerX + (radius * cos(highlightRad)).toFloat()
                val highlightY = centerY + (radius * sin(highlightRad)).toFloat()
                
                // Fade in the highlight over first 20% of progress (2% to 20%)
                val highlightFadeInProgress = ((progress - 2f) / 18f).coerceIn(0f, 1f)
                
                // Fade out the highlight over last 10% of progress (90% to 99.5%)
                val highlightFadeOutProgress = if (progress > 90f) {
                    (1f - ((progress - 90f) / 9.5f)).coerceIn(0f, 1f)
                } else {
                    1f
                }
                
                // Subtle pulsing effect combined with fade-in and fade-out
                val pulseAlpha = (0.9f + 0.1f * Math.sin(System.currentTimeMillis() / 400.0)).toFloat()
                val finalAlpha = pulseAlpha * combinedAlpha * highlightFadeInProgress * highlightFadeOutProgress
                
                // Use same color scheme: light lavender highlight
                val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    shader = RadialGradient(
                        highlightX, highlightY, 70f,
                        intArrayOf(
                            Color.argb((220 * finalAlpha).toInt(), 245, 240, 255),  // Almost white lavender
                            Color.argb((160 * finalAlpha).toInt(), 216, 207, 255),  // Very light lavender
                            Color.argb((90 * finalAlpha).toInt(), 196, 181, 242),   // Light lavender glow
                            Color.argb(0, 175, 160, 238)
                        ),
                        floatArrayOf(0f, 0.4f, 0.7f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawCircle(highlightX, highlightY, 70f, highlightPaint)
                
                // Trigger continuous redraw for pulsing effect
                postInvalidateOnAnimation()
            }
        }
    }
}
