package com.waterfountainmachine.app

import android.animation.AnimatorSet
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.waterfountainmachine.app.databinding.ActivityVendingBinding
import com.waterfountainmachine.app.hardware.WaterFountainManager
import com.waterfountainmachine.app.debug.WaterFountainDebug
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
        
        // Update mock mode indicator visibility
        updateMockModeIndicator()
    }
    
    /**
     * Show/hide mock mode indicator based on hardware mode
     */
    private fun updateMockModeIndicator() {
        val prefs = getSharedPreferences("system_settings", android.content.Context.MODE_PRIVATE)
        val useRealSerial = prefs.getBoolean("use_real_serial", false)
        
        // Show indicator only if in mock mode (not using real hardware)
        binding.mockModeIndicator.root.visibility = if (!useRealSerial) View.VISIBLE else View.GONE
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
                    binding.root.postDelayed({ isNavigating = false }, 1000)
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
                    binding.root.postDelayed({ isNavigating = false }, 1000)
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
                    binding.root.postDelayed({ isNavigating = false }, 2000) // Longer for diagnostics
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
                Log.i("VendingActivity", "✅ Hardware ready (initialized at app launch)")
                Toast.makeText(this, "Water fountain ready", Toast.LENGTH_SHORT).show()
            } else {
                Log.w("VendingActivity", "⚠️ Hardware not ready - check admin panel")
                Toast.makeText(this, "Hardware not ready. Please check connection.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("VendingActivity", "Exception accessing hardware", e)
            isHardwareInitialized = false
        }
    }

    /**
     * Dispense water directly (for testing and demo)
     */
    private fun dispenseWaterDirect() {
        if (!isHardwareInitialized) {
            Toast.makeText(this, "Hardware not ready. Please wait...", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                Log.i("VendingActivity", "Starting water dispensing...")
                
                // Show loading state
                runOnUiThread {
                    Toast.makeText(this@VendingActivity, "Dispensing water...", Toast.LENGTH_SHORT).show()
                }
                
                // Dispense water
                val result = waterFountainManager.dispenseWater()
                
                // Handle result on UI thread
                runOnUiThread {
                    if (result.success) {
                        Log.i("VendingActivity", "Water dispensed successfully in ${result.dispensingTimeMs}ms")
                        
                        // Navigate to animation activity to show success
                        val intent = Intent(this@VendingActivity, VendingAnimationActivity::class.java)
                        intent.putExtra("dispensingTime", result.dispensingTimeMs)
                        intent.putExtra("slot", result.slot)
                        startActivity(intent)
                        overridePendingTransition(R.anim.zoom_in_fade, R.anim.zoom_out_fade)
                        
                    } else {
                        Log.e("VendingActivity", "Water dispensing failed: ${result.errorMessage}")
                        val errorMsg = result.errorMessage ?: "Unknown error occurred"
                        Toast.makeText(this@VendingActivity, "Dispensing failed: $errorMsg", Toast.LENGTH_LONG).show()
                    }
                }
                
            } catch (e: Exception) {
                Log.e("VendingActivity", "Exception during water dispensing", e)
                runOnUiThread {
                    Toast.makeText(this@VendingActivity, "Dispensing error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Run comprehensive system diagnostics
     */
    private fun runSystemDiagnostics() {
        lifecycleScope.launch {
            try {
                Log.i("VendingActivity", "Starting system diagnostics...")
                
                runOnUiThread {
                    Toast.makeText(this@VendingActivity, "Running system diagnostics...", Toast.LENGTH_SHORT).show()
                }
                
                // Run comprehensive system test
                val testResult = WaterFountainDebug.runSystemTest(this@VendingActivity)
                
                // Print all results to log
                testResult.printToLog()
                
                // Show summary to user
                runOnUiThread {
                    val message = if (testResult.success) {
                        "✓ All system tests passed! Check logs for details."
                    } else {
                        "✗ Some tests failed. Check logs for details."
                    }
                    Toast.makeText(this@VendingActivity, message, Toast.LENGTH_LONG).show()
                }
                
            } catch (e: Exception) {
                Log.e("VendingActivity", "Exception during system diagnostics", e)
                runOnUiThread {
                    Toast.makeText(this@VendingActivity, "Diagnostics error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        inactivityTimer.cleanup()
        
        // Don't shutdown hardware here - it's managed by Application class
        // Hardware stays alive for the entire app lifecycle
        Log.d("VendingActivity", "VendingActivity destroyed (hardware remains active)")
    }
}