package com.waterfountainmachine.app.utils

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Utility object for common animations used throughout the app.
 * Centralizes animation logic to avoid duplication.
 */
object AnimationUtils {
    
    /**
     * Create and start a repeating question mark animation.
     * 
     * @param button The button view to animate
     * @param icon The icon view inside the button to rotate
     * @param rootView The root view to post delayed callbacks on
     * @return The AnimatorSet for the animation (can be stopped if needed)
     */
    fun setupQuestionMarkAnimation(
        button: View,
        icon: View,
        rootView: View
    ): AnimatorSet {
        // Create shake animation
        val shakeX = ObjectAnimator.ofFloat(
            button, 
            "translationX", 
            0f, -12f, 12f, -8f, 8f, -4f, 4f, 0f
        ).apply { duration = 1000 }
        
        // Create rotation animation
        val rotate = ObjectAnimator.ofFloat(
            icon, 
            "rotation", 
            0f, 20f, -20f, 15f, -15f, 8f, -8f, 0f
        ).apply { duration = 1000 }
        
        // Create scale animations
        val scaleX = ObjectAnimator.ofFloat(button, "scaleX", 1f, 1.15f, 1f)
            .apply { duration = 1000 }
        
        val scaleY = ObjectAnimator.ofFloat(button, "scaleY", 1f, 1.15f, 1f)
            .apply { duration = 1000 }
        
        val animator = AnimatorSet().apply {
            playTogether(shakeX, rotate, scaleX, scaleY)
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = 1000
        }
        
        // Make animation repeat
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                rootView.postDelayed({
                    animator.start()
                }, 1500)
            }
        })
        
        animator.start()
        return animator
    }
    
    /**
     * Create a modal show animation
     * 
     * @param modalContent The modal content view to animate
     * @param onComplete Callback when animation completes
     */
    fun showModalAnimation(modalContent: View, onComplete: (() -> Unit)? = null) {
        modalContent.alpha = 0f
        modalContent.scaleX = 0.8f
        modalContent.scaleY = 0.8f
        
        modalContent.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { onComplete?.invoke() }
            .start()
    }
    
    /**
     * Create a modal hide animation
     * 
     * @param modalContent The modal content view to animate
     * @param onComplete Callback when animation completes
     */
    fun hideModalAnimation(modalContent: View, onComplete: (() -> Unit)? = null) {
        modalContent.animate()
            .alpha(0f)
            .scaleX(0.8f)
            .scaleY(0.8f)
            .setDuration(250)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { onComplete?.invoke() }
            .start()
    }
    
    /**
     * Create a card click animation
     * 
     * @param card The card view to animate
     * @param onComplete Callback when animation completes
     */
    fun performCardClickAnimation(card: View, onComplete: () -> Unit) {
        val scaleDownX = ObjectAnimator.ofFloat(card, "scaleX", 1f, 0.95f)
        val scaleDownY = ObjectAnimator.ofFloat(card, "scaleY", 1f, 0.95f)
        val scaleUpX = ObjectAnimator.ofFloat(card, "scaleX", 0.95f, 1.02f)
        val scaleUpY = ObjectAnimator.ofFloat(card, "scaleY", 0.95f, 1.02f)
        val scaleNormalX = ObjectAnimator.ofFloat(card, "scaleX", 1.02f, 1f)
        val scaleNormalY = ObjectAnimator.ofFloat(card, "scaleY", 1.02f, 1f)
        
        val elevateUp = ObjectAnimator.ofFloat(card, "elevation", 8f, 16f)
        val elevateDown = ObjectAnimator.ofFloat(card, "elevation", 16f, 8f)
        
        val pressSet = AnimatorSet().apply {
            play(scaleDownX).with(scaleDownY).with(elevateUp)
            duration = 100
            interpolator = AccelerateDecelerateInterpolator()
        }
        
        val bounceSet = AnimatorSet().apply {
            play(scaleUpX).with(scaleUpY)
            duration = 150
            interpolator = AccelerateDecelerateInterpolator()
        }
        
        val normalizeSet = AnimatorSet().apply {
            play(scaleNormalX).with(scaleNormalY).with(elevateDown)
            duration = 100
            interpolator = AccelerateDecelerateInterpolator()
        }
        
        val fullAnimation = AnimatorSet().apply {
            playSequentially(pressSet, bounceSet, normalizeSet)
        }
        
        fullAnimation.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                onComplete()
            }
        })
        
        fullAnimation.start()
    }
}
