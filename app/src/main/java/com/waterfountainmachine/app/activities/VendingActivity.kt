package com.waterfountainmachine.app.activities

import android.animation.AnimatorSet
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.waterfountainmachine.app.R
import com.waterfountainmachine.app.WaterFountainApplication
import com.waterfountainmachine.app.databinding.ActivityVendingBinding
import com.waterfountainmachine.app.hardware.WaterFountainManager
import com.waterfountainmachine.app.debug.WaterFountainDebug
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.utils.FullScreenUtils
import com.waterfountainmachine.app.utils.AnimationUtils
import com.waterfountainmachine.app.utils.InactivityTimer
import com.waterfountainmachine.app.utils.HardwareKeyHandler
import kotlinx.coroutines.launch

class VendingActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVendingBinding
    private lateinit var inactivityTimer: InactivityTimer
    private var questionMarkAnimator: AnimatorSet? = null
    private var isNavigating = false // Prevent multiple activity launches
    
    // Runnable references for proper cleanup
    private val navigationResetRunnable = Runnable { isNavigating = false }
    
    // Water Fountain Hardware Integration
    private lateinit var waterFountainManager: WaterFountainManager
    private var isHardwareInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVendingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupFullScreen()
        setupHardware()
        setupClickListeners()
        setupQuestionMarkAnimation()
        setupModalFunctionality()
        
        inactivityTimer = InactivityTimer(30000L) { returnToMainScreen() }
        inactivityTimer.start()
    }

    private fun setupFullScreen() {
        FullScreenUtils.setupFullScreen(window, binding.root)
    }

    private fun setupClickListeners() {
        binding.smsCard.setOnClickListener {
            if (isNavigating) return@setOnClickListener // Prevent multiple clicks
            
            inactivityTimer.reset()
            performCardClickAnimation(binding.smsCard) {
                if (!isNavigating) {
                    isNavigating = true
                    val intent = Intent(this, SMSActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                    startActivity(intent)
                    // Use fade transition for smooth screen transition
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    
                    // Reset flag after delay
                    binding.root.postDelayed(navigationResetRunnable, 1000)
                }
            }
        }

        // PIN Code card - now enables direct water dispensing for testing
        binding.pinCodeCard.setOnClickListener {
            if (isNavigating) return@setOnClickListener // Prevent multiple clicks
            
            inactivityTimer.reset()
            performCardClickAnimation(binding.pinCodeCard) {
                if (!isNavigating) {
                    isNavigating = true
                    // Direct water dispensing (for testing/demo)
                    dispenseWaterDirect()
                    
                    // Reset flag after delay
                    binding.root.postDelayed(navigationResetRunnable, 1000)
                }
            }
        }

        // QR Code card - now used for system diagnostics
        binding.qrCodeCard.setOnClickListener {
            if (isNavigating) return@setOnClickListener // Prevent multiple clicks
            
            inactivityTimer.reset()
            performCardClickAnimation(binding.qrCodeCard) {
                if (!isNavigating) {
                    isNavigating = true
                    // Run system diagnostics
                    runSystemDiagnostics()
                    
                    // Reset flag after delay
                    binding.root.postDelayed(navigationResetRunnable, 2000) // Longer for diagnostics
                }
            }
        }

        binding.backButton.setOnClickListener {
            if (!isNavigating) {
                returnToMainScreen()
            }
        }
    }

    private fun returnToMainScreen() {
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        inactivityTimer.reset()
    }

    // Prevent back button from exiting app - return to main screen instead
    override fun onBackPressed() {
        super.onBackPressed()
        returnToMainScreen()
    }

    // Prevent hardware keys from exiting
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return HardwareKeyHandler.handleKeyDown(keyCode) { returnToMainScreen() }
            || super.onKeyDown(keyCode, event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            FullScreenUtils.reapplyFullScreen(window, binding.root)
        }
    }



    private fun setupQuestionMarkAnimation() {
        questionMarkAnimator = AnimationUtils.setupQuestionMarkAnimation(
            button = binding.questionMarkButton,
            icon = binding.questionMarkIcon,
            rootView = binding.root
        )
    }

    private fun setupModalFunctionality() {
        // Question mark button click
        binding.questionMarkButton.setOnClickListener {
            inactivityTimer.reset()
            showModal()
        }

        // Close modal button click
        binding.closeModalButton.setOnClickListener {
            hideModal()
        }

        // Click outside modal to close
        binding.modalOverlay.setOnClickListener { view ->
            if (view == binding.modalOverlay) {
                hideModal()
            }
        }

        // Prevent modal content clicks from closing modal
        binding.modalContent.setOnClickListener {
            // Do nothing - prevent click from bubbling up
        }
    }

    private fun showModal() {
        binding.modalOverlay.visibility = View.VISIBLE
        AnimationUtils.showModalAnimation(binding.modalContent)
    }

    private fun hideModal() {
        AnimationUtils.hideModalAnimation(binding.modalContent) {
            binding.modalOverlay.visibility = View.GONE
        }
    }

    private fun performCardClickAnimation(card: View, onComplete: () -> Unit) {
        AnimationUtils.performCardClickAnimation(card, onComplete)
    }

    /**
     * Initialize hardware connection - now uses Application state
     */
    private fun setupHardware() {
        try {
            // Get hardware manager from Application (already initialized)
            val app = application as WaterFountainApplication
            waterFountainManager = app.hardwareManager
            isHardwareInitialized = app.isHardwareReady()
            
            if (isHardwareInitialized) {
                AppLog.i("VendingActivity", "Hardware ready (initialized at app launch)")
            } else {
                AppLog.w("VendingActivity", "Hardware not ready - check admin panel")
            }
        } catch (e: Exception) {
            AppLog.e("VendingActivity", "Exception accessing hardware", e)
            isHardwareInitialized = false
        }
    }

    /**
     * Dispense water directly (for testing and demo)
     */
    private fun dispenseWaterDirect() {
        if (!isHardwareInitialized) {
            AppLog.w("VendingActivity", "Hardware not ready - cannot dispense water")
            return
        }

        lifecycleScope.launch {
            try {
                AppLog.i("VendingActivity", "Starting water dispensing...")
                
                // Dispense water
                val result = waterFountainManager.dispenseWater()
                
                // Handle result on UI thread
                runOnUiThread {
                    if (result.success) {
                        AppLog.i("VendingActivity", "Water dispensed successfully in ${result.dispensingTimeMs}ms")
                        
                        // Navigate to animation activity to show success
                        val intent = Intent(this@VendingActivity, VendingAnimationActivity::class.java)
                        intent.putExtra("dispensingTime", result.dispensingTimeMs)
                        intent.putExtra("slot", result.slot)
                        startActivity(intent)
                        @Suppress("DEPRECATION")
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                        
                    } else {
                        AppLog.e("VendingActivity", "Water dispensing failed: ${result.errorMessage}")
                    }
                }
                
            } catch (e: Exception) {
                AppLog.e("VendingActivity", "Exception during water dispensing", e)
            }
        }
    }

    /**
     * Run comprehensive system diagnostics
     */
    private fun runSystemDiagnostics() {
        lifecycleScope.launch {
            try {
                AppLog.i("VendingActivity", "Starting system diagnostics...")
                
                // Run comprehensive system test
                val testResult = WaterFountainDebug.runSystemTest(this@VendingActivity)
                
                // Print all results to log
                testResult.printToLog()
                
                // Log summary
                if (testResult.success) {
                    AppLog.i("VendingActivity", "All system tests passed")
                } else {
                    AppLog.w("VendingActivity", "Some system tests failed")
                }
                
            } catch (e: Exception) {
                AppLog.e("VendingActivity", "Exception during system diagnostics", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        inactivityTimer.cleanup()
        
        // Clean up animations
        questionMarkAnimator?.apply {
            removeAllListeners()
            cancel()
        }
        questionMarkAnimator = null
        
        // Clean up pending callbacks
        binding.root.removeCallbacks(navigationResetRunnable)
        
        // Don't shutdown hardware here - it's managed by Application class
        // Hardware stays alive for the entire app lifecycle
        AppLog.d("VendingActivity", "VendingActivity destroyed (hardware remains active)")
    }
}