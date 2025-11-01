package com.waterfountainmachine.app.activities

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.waterfountainmachine.app.databinding.ActivityPrivacyExplanationBinding
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.utils.FullScreenUtils

class PrivacyExplanationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPrivacyExplanationBinding
    private var isNavigating = false // Prevent multiple launches

    companion object {
        private const val TAG = "PrivacyExplanationActivity"
        
        // URLs for each privacy topic
        private const val URL_FREE_WATER = "https://waterfountain.com/how-it-works"
        private const val URL_PRIVACY_POLICY = "https://waterfountain.com/privacy"
        private const val URL_DATA_SECURITY = "https://waterfountain.com/security"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        AppLog.i(TAG, "Privacy Explanation Activity started")
        
        binding = ActivityPrivacyExplanationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupFullScreen()
        setupClickListeners()
        setupFadingAnimation()
    }

    private fun setupFullScreen() {
        FullScreenUtils.setupFullScreen(window, binding.root)
    }

    private fun setupFadingAnimation() {
        // Add fading animation to "Tap to get free water!" text
        binding.gotItButton.alpha = 0.4f
        binding.gotItButton.animate()
            .alpha(1f)
            .setDuration(800)
            .setStartDelay(0)
            .withEndAction {
                // Fade back down
                binding.gotItButton.animate()
                    .alpha(0.4f)
                    .setDuration(4500)
                    .setStartDelay(0)
                    .withEndAction {
                        // Loop the animation
                        setupFadingAnimation()
                    }
                    .start()
            }
            .start()
    }

    private fun setupClickListeners() {
        // Close button - go back to main screen
        binding.closeButton.setOnClickListener {
            AppLog.d(TAG, "Close button clicked - returning to main screen")
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        // Root view click listener - tap anywhere to continue (excluding cards and buttons)
        binding.root.setOnClickListener {
            // Only navigate if modal is not visible and not already navigating
            if (binding.modalOverlay.visibility == View.GONE && !isNavigating) {
                AppLog.d(TAG, "Screen tapped - launching SMSActivity")
                isNavigating = true
                
                val intent = android.content.Intent(this, SMSActivity::class.java)
                intent.flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                startActivity(intent)
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                finish()
                
                // Reset flag after delay
                binding.root.postDelayed({
                    isNavigating = false
                }, 1000)
            }
        }

        // Got It button - proceed to SMS activity
        binding.gotItButton.setOnClickListener {
            if (!isNavigating) {
                AppLog.d(TAG, "Got It button clicked - launching SMSActivity")
                isNavigating = true
                
                val intent = android.content.Intent(this, SMSActivity::class.java)
                intent.flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                startActivity(intent)
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                finish()
                
                // Reset flag after delay
                binding.root.postDelayed({
                    isNavigating = false
                }, 1000)
            }
        }

        // Card click listeners with animation - these will consume clicks and not propagate to root
        setupCardClickListener(
            binding.card1,
            "How is it free?",
            "Local businesses sponsor your water by advertising on the can. You get free hydration + exclusive discounts on their products. Scan the QR code on your can to unlock deals!",
            URL_FREE_WATER
        )

        setupCardClickListener(
            binding.card2,
            "Why your number?",
            "We don't store your phone number in plain text. We hash it (one-way encryption) so our team can't see it, and we use it only to enforce the 2-waters-per-day limit. We are in the process of building an app/QR code based limiter so you don't have to provide your phone number to us",
            URL_PRIVACY_POLICY
        )

        setupCardClickListener(
            binding.card3,
            "Your privacy?",
            "We don't store or sell your data. We try to keep the minimum amount of data we need to keep our business sustainable. Scanning QR codes on the can will keep the water free",
            URL_DATA_SECURITY
        )

        // Close modal button and overlay click
        binding.closeModalButton.setOnClickListener {
            hideModal()
        }

        binding.modalOverlay.setOnClickListener {
            hideModal()
        }

        // Question mark button - show help information (consume click)
        binding.questionMarkButton.setOnClickListener {
            AppLog.d(TAG, "Question mark button clicked - showing help modal")
            showModal(
                "Need Help?",
                "These cards explain how our service works. Tap any card to scan a QR code and learn more details on your phone.",
                URL_FREE_WATER
            )
            // Don't propagate click to parent
        }
    }

    private fun setupCardClickListener(card: View, title: String, description: String, url: String) {
        card.setOnClickListener {
            // Animate card press
            animateCardPress(card) {
                showModal(title, description, url)
            }
            // Don't propagate click to parent (consume the event)
        }
        
        // Make sure cards are clickable and focusable
        card.isClickable = true
        card.isFocusable = true
    }

    private fun animateCardPress(view: View, onComplete: () -> Unit) {
        val scaleDown = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, "scaleX", 1f, 0.95f),
                ObjectAnimator.ofFloat(view, "scaleY", 1f, 0.95f)
            )
            duration = 100
            interpolator = AccelerateDecelerateInterpolator()
        }

        val scaleUp = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, "scaleX", 0.95f, 1f),
                ObjectAnimator.ofFloat(view, "scaleY", 0.95f, 1f)
            )
            duration = 100
            interpolator = AccelerateDecelerateInterpolator()
        }

        scaleDown.start()
        scaleDown.doOnEnd {
            scaleUp.start()
            scaleUp.doOnEnd {
                onComplete()
            }
        }
    }

    private fun android.animation.Animator.doOnEnd(action: () -> Unit) {
        addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationStart(animation: android.animation.Animator) {}
            override fun onAnimationEnd(animation: android.animation.Animator) {
                action()
                animation.removeListener(this)
            }
            override fun onAnimationCancel(animation: android.animation.Animator) {}
            override fun onAnimationRepeat(animation: android.animation.Animator) {}
        })
    }

    private fun showModal(title: String, description: String, url: String) {
        AppLog.d(TAG, "Showing modal for: $title")
        
        // Set modal content
        binding.modalTitle.text = title
        binding.modalDescription.text = description
        
        // Generate and display QR code
        try {
            val qrBitmap = generateQRCode(url, 400, 400)
            binding.modalQrCode.setImageBitmap(qrBitmap)
        } catch (e: Exception) {
            AppLog.e(TAG, "Error generating QR code", e)
        }
        
        // Show modal with fade in animation
        binding.modalOverlay.visibility = View.VISIBLE
        binding.modalOverlay.alpha = 0f
        binding.modalOverlay.animate()
            .alpha(1f)
            .setDuration(200)
            .start()
    }

    private fun hideModal() {
        AppLog.d(TAG, "Hiding modal")
        
        // Hide modal with fade out animation
        binding.modalOverlay.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                binding.modalOverlay.visibility = View.GONE
            }
            .start()
    }

    private fun generateQRCode(text: String, width: Int, height: Int): Bitmap {
        val qrCodeWriter = QRCodeWriter()
        val bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, width, height)
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        
        return bitmap
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            FullScreenUtils.reapplyFullScreen(window, binding.root)
        }
    }

    override fun onBackPressed() {
        // If modal is showing, close it instead of activity
        if (binding.modalOverlay.visibility == View.VISIBLE) {
            hideModal()
        } else {
            super.onBackPressed()
        }
    }
}
