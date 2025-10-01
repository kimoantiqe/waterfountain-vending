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
        strokeWidth = 45f
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
        alpha = 30
    }

    // Progress ring - bold and beautiful with cream/white colors
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 45f
        strokeCap = Paint.Cap.ROUND
    }

    // Inner glow layer
    private val innerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 65f
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(30f, BlurMaskFilter.Blur.NORMAL)
    }

    // Outer glow layer
    private val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 90f
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(50f, BlurMaskFilter.Blur.NORMAL)
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
                    Color.argb((glowAlpha * 200).toInt(), 255, 253, 245),  // Warm cream
                    Color.argb((glowAlpha * 150).toInt(), 245, 243, 235),  // Light cream
                    Color.argb((glowAlpha * 80).toInt(), 230, 230, 230),   // Silver
                    Color.argb(0, 255, 255, 255)
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
            
            // Create smooth cream-to-white gradient matching background aesthetic
            val colors = intArrayOf(
                Color.argb(255, 189, 195, 199),  // Start with background light gray
                Color.argb(255, 210, 210, 210),  // Warm silver
                Color.argb(255, 230, 230, 230),  // Bright silver
                Color.argb(255, 245, 243, 235),  // Cream white
                Color.argb(255, 255, 253, 245)   // Pure warm white
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
            
            // Add shimmering white highlight at the leading edge
            if (progress < 99.5f) {
                val highlightAngle = -90f + sweepAngle
                val highlightRad = Math.toRadians(highlightAngle.toDouble())
                val highlightX = centerX + (radius * cos(highlightRad)).toFloat()
                val highlightY = centerY + (radius * sin(highlightRad)).toFloat()
                
                val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    shader = RadialGradient(
                        highlightX, highlightY, 70f,
                        intArrayOf(
                            Color.argb(220, 255, 255, 255),  // Bright white
                            Color.argb(120, 255, 253, 245),  // Cream glow
                            Color.argb(0, 255, 255, 255)
                        ),
                        floatArrayOf(0f, 0.5f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawCircle(highlightX, highlightY, 70f, highlightPaint)
            }
        }
    }
}
