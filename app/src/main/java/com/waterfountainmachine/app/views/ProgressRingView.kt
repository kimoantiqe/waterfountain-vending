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

    // Background ring - subtle and elegant
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 50f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#555555")
        alpha = 30
    }

    // Progress ring - bold and beautiful with cream/white colors
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 50f
        strokeCap = Paint.Cap.ROUND
    }

    // Inner glow layer
    private val innerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 70f
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(35f, BlurMaskFilter.Blur.NORMAL)
    }

    // Outer glow layer
    private val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 100f
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(60f, BlurMaskFilter.Blur.NORMAL)
    }
    
    // Soft edge paint for smooth transitions at start/end
    private val softEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 60f
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
    }

    // Completion burst glow
    private val burstGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var progress = 0f
    private var glowAlpha = 0f
    private val rect = RectF()
    
    init {
        // Enable hardware acceleration for smooth gradients
        setLayerType(LAYER_TYPE_HARDWARE, null)
        
        // Add padding to prevent clipping of glows and effects
        setPadding(120, 120, 120, 120)
    }

    fun animateProgress(duration: Long = 5000) {
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
        val radius = (availableWidth.coerceAtMost(availableHeight) * 0.42f)

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
            val glowRadius = radius + 150f
            
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

        // Draw progress with beautiful cream/white gradient and glow
        if (progress > 0) {
            val sweepAngle = (progress / 100f) * 360f
            
            // Create smooth grayish-purple gradient
            val colors = intArrayOf(
                Color.parseColor("#7B6BA8"),  // Start with darker purple-gray
                Color.parseColor("#9B8AC4"),  // Medium purple-gray
                Color.parseColor("#AFA0EE"),  // Lavender purple (matches buttons)
                Color.parseColor("#C4B5F2"),  // Light lavender
                Color.parseColor("#D8CFFF")   // Very light lavender
            )
            val positions = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
            
            // Rotate gradient to start from top
            val matrix = Matrix()
            matrix.postRotate(-90f, centerX, centerY)
            
            val gradient = SweepGradient(centerX, centerY, colors, positions)
            gradient.setLocalMatrix(matrix)
            
            // Draw outer glow (most diffuse)
            if (progress > 5) {
                outerGlowPaint.shader = gradient
                outerGlowPaint.alpha = (140 * (progress / 100f)).toInt()
                canvas.drawArc(rect, -90f, sweepAngle, false, outerGlowPaint)
            }
            
            // Draw inner glow (brighter)
            if (progress > 3) {
                innerGlowPaint.shader = gradient
                innerGlowPaint.alpha = (190 * (progress / 100f)).toInt()
                canvas.drawArc(rect, -90f, sweepAngle, false, innerGlowPaint)
            }
            
            // Draw main progress ring (crisp and bold)
            progressPaint.shader = gradient
            progressPaint.alpha = 255
            canvas.drawArc(rect, -90f, sweepAngle, false, progressPaint)
            
            // Smooth out the START point (top of ring) with extra fade - BUTTER SMOOTH
            if (progress > 0.5f && progress < 20f) {
                // Create a very smooth fade-in over first 20% of animation
                val fadeProgress = (progress / 20f).coerceIn(0f, 1f)
                // Use easeOut curve for extra smoothness
                val smoothAlpha = (Math.pow(fadeProgress.toDouble(), 0.5) * 255).toInt().coerceIn(0, 255)
                
                softEdgePaint.shader = gradient
                softEdgePaint.alpha = smoothAlpha
                canvas.drawArc(rect, -90f, 10f, false, softEdgePaint)
            }
            
            // Smooth out the END point (leading edge) with multi-layer fade - NO HARD LINES
            if (progress > 5f && progress < 99.5f) {
                val endEdgeSweep = 15f.coerceAtMost(sweepAngle)
                
                // Layer 1: Soft outer fade
                softEdgePaint.shader = gradient
                softEdgePaint.alpha = 60
                softEdgePaint.strokeWidth = 80f
                canvas.drawArc(rect, -90f + sweepAngle - endEdgeSweep, endEdgeSweep, false, softEdgePaint)
                
                // Layer 2: Medium fade
                softEdgePaint.alpha = 120
                softEdgePaint.strokeWidth = 65f
                canvas.drawArc(rect, -90f + sweepAngle - (endEdgeSweep * 0.6f), endEdgeSweep * 0.6f, false, softEdgePaint)
                
                // Layer 3: Inner fade
                softEdgePaint.alpha = 200
                softEdgePaint.strokeWidth = 50f
                canvas.drawArc(rect, -90f + sweepAngle - (endEdgeSweep * 0.3f), endEdgeSweep * 0.3f, false, softEdgePaint)
            }
            
            // Add shimmering white highlight at the leading edge - ULTRA SMOOTH
            if (progress < 99.5f && progress > 10f) {
                val highlightAngle = -90f + sweepAngle
                val highlightRad = Math.toRadians(highlightAngle.toDouble())
                val highlightX = centerX + (radius * cos(highlightRad)).toFloat()
                val highlightY = centerY + (radius * sin(highlightRad)).toFloat()
                
                // Pulsing effect - subtle breathing animation
                val pulseAlpha = (0.85f + 0.15f * Math.sin(System.currentTimeMillis() / 400.0)).toFloat()
                
                val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    shader = RadialGradient(
                        highlightX, highlightY, 90f,
                        intArrayOf(
                            Color.argb((240 * pulseAlpha).toInt(), 216, 207, 255),  // Very light lavender
                            Color.argb((160 * pulseAlpha).toInt(), 196, 181, 242),  // Lavender glow
                            Color.argb((60 * pulseAlpha).toInt(), 175, 160, 238),   // Soft lavender
                            Color.argb(0, 175, 160, 238)
                        ),
                        floatArrayOf(0f, 0.3f, 0.6f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawCircle(highlightX, highlightY, 90f, highlightPaint)
                
                // Trigger continuous redraw for pulsing effect
                postInvalidateOnAnimation()
            }
        }
    }
}
