package com.waterfountainmachine.app.admin

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import com.waterfountainmachine.app.databinding.ActivityAdminAuthBinding
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.utils.FullScreenUtils
import com.waterfountainmachine.app.utils.HardwareKeyHandler
import com.waterfountainmachine.app.config.WaterFountainConfig

class AdminAuthActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityAdminAuthBinding
    private var pinCode = ""
    // PIN now stored as salted hash in EncryptedSharedPreferences
    // Managed by AdminPinManager for security
    private var isValidating = false
    
    // Rate limiting: 3 attempts, then 1-hour lockout
    private var failedAttempts = 0
    private var lockoutUntil: Long = 0
    
    companion object {
        private const val TAG = "AdminAuthActivity"
        private const val PREFS_NAME = "admin_auth_prefs"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Load rate limiting state from encrypted preferences
        loadRateLimitState()
        
        setupFullScreen()
        setupKeypad()
        setupUI()
        
        // Check if currently locked out
        checkLockoutStatus()
    }
    
    /**
     * Load rate limiting state from encrypted preferences
     */
    private fun loadRateLimitState() {
        val prefs = com.waterfountainmachine.app.utils.SecurePreferences.getSystemSettings(this)
        failedAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL, 0)
        
        AppLog.d(TAG, "Loaded rate limit state: $failedAttempts attempts, lockout until: $lockoutUntil")
    }
    
    /**
     * Save rate limiting state to encrypted preferences
     */
    private fun saveRateLimitState() {
        val prefs = com.waterfountainmachine.app.utils.SecurePreferences.getSystemSettings(this)
        prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, failedAttempts)
            .putLong(KEY_LOCKOUT_UNTIL, lockoutUntil)
            .apply()
        
        AppLog.d(TAG, "Saved rate limit state: $failedAttempts attempts, lockout until: $lockoutUntil")
    }
    
    /**
     * Check if currently locked out and show message
     */
    private fun checkLockoutStatus() {
        val currentTime = System.currentTimeMillis()
        
        if (currentTime < lockoutUntil) {
            val remainingMs = lockoutUntil - currentTime
            val remainingMinutes = remainingMs / 60000
            
            binding.subtitleText.text = "Locked: Try again in ${remainingMinutes} minutes"
            binding.subtitleText.setTextColor(getColor(android.R.color.holo_red_dark))
            setKeypadEnabled(false)
            
            AppLog.w(TAG, "Admin access locked out for ${remainingMinutes} minutes")
            
            // Schedule UI update when lockout expires
            binding.root.postDelayed({
                if (!isFinishing) {
                    checkLockoutStatus()
                }
            }, 60000) // Check every minute
        } else {
            // Lockout expired or no lockout
            if (lockoutUntil > 0) {
                // Just expired, reset counter
                failedAttempts = 0
                lockoutUntil = 0
                saveRateLimitState()
                AppLog.i(TAG, "Lockout expired, reset attempts counter")
            }
            
            binding.subtitleText.text = "Enter Administrator PIN"
            binding.subtitleText.setTextColor(getColor(com.waterfountainmachine.app.R.color.white_70))
            setKeypadEnabled(true)
        }
    }
    
    /**
     * Enable or disable the keypad
     */
    private fun setKeypadEnabled(enabled: Boolean) {
        binding.btn0.isEnabled = enabled
        binding.btn1.isEnabled = enabled
        binding.btn2.isEnabled = enabled
        binding.btn3.isEnabled = enabled
        binding.btn4.isEnabled = enabled
        binding.btn5.isEnabled = enabled
        binding.btn6.isEnabled = enabled
        binding.btn7.isEnabled = enabled
        binding.btn8.isEnabled = enabled
        binding.btn9.isEnabled = enabled
        binding.clearButton.isEnabled = enabled
        binding.deleteButton.isEnabled = enabled
        
        val alpha = if (enabled) 1f else 0.3f
        binding.btn0.alpha = alpha
        binding.btn1.alpha = alpha
        binding.btn2.alpha = alpha
        binding.btn3.alpha = alpha
        binding.btn4.alpha = alpha
        binding.btn5.alpha = alpha
        binding.btn6.alpha = alpha
        binding.btn7.alpha = alpha
        binding.btn8.alpha = alpha
        binding.btn9.alpha = alpha
        binding.clearButton.alpha = alpha
        binding.deleteButton.alpha = alpha
    }
    
    private fun setupFullScreen() {
        FullScreenUtils.setupFullScreen(window, binding.root)
    }
    
    private fun setupUI() {
        binding.titleText.text = "Admin Access"
        binding.subtitleText.text = "Enter Administrator PIN"
        updatePinDisplay()
        
        binding.backButton.setOnClickListener {
            finish()
        }
        
        binding.clearButton.setOnClickListener {
            clearPin()
        }
        
        binding.deleteButton.setOnClickListener {
            deleteLastDigit()
        }
    }
    
    private fun setupKeypad() {
        // Number buttons
        binding.btn0.setOnClickListener { addDigit("0") }
        binding.btn1.setOnClickListener { addDigit("1") }
        binding.btn2.setOnClickListener { addDigit("2") }
        binding.btn3.setOnClickListener { addDigit("3") }
        binding.btn4.setOnClickListener { addDigit("4") }
        binding.btn5.setOnClickListener { addDigit("5") }
        binding.btn6.setOnClickListener { addDigit("6") }
        binding.btn7.setOnClickListener { addDigit("7") }
        binding.btn8.setOnClickListener { addDigit("8") }
        binding.btn9.setOnClickListener { addDigit("9") }
    }
    
    private fun addDigit(digit: String) {
        if (isValidating) return
        
        if (pinCode.length < 8) {
            pinCode += digit
            updatePinDisplay()
            
            if (pinCode.length == 8) {
                validatePin()
            }
        }
    }
    
    private fun deleteLastDigit() {
        if (isValidating) return
        
        if (pinCode.isNotEmpty()) {
            pinCode = pinCode.dropLast(1)
            updatePinDisplay()
        }
    }
    
    private fun clearPin() {
        if (isValidating) return
        
        pinCode = ""
        updatePinDisplay()
    }
    
    private fun updatePinDisplay() {
        val display = StringBuilder()
        repeat(8) { index ->
            if (index < pinCode.length) {
                display.append("●")
            } else {
                display.append("○")
            }
            if (index < 7) display.append(" ")
        }
        binding.pinDisplay.text = display.toString()
    }
    
    private fun validatePin() {
        if (isValidating) return
        
        // Check if locked out
        val currentTime = System.currentTimeMillis()
        if (currentTime < lockoutUntil) {
            val remainingMinutes = (lockoutUntil - currentTime) / 60000
            binding.subtitleText.text = "Locked: Try again in ${remainingMinutes} minutes"
            binding.subtitleText.setTextColor(getColor(android.R.color.holo_red_dark))
            pinCode = ""
            updatePinDisplay()
            return
        }
        
        isValidating = true
        AppLog.i(TAG, "PIN validation attempted (attempt ${failedAttempts + 1})")
        
        // Verify PIN using secure hash comparison
        if (AdminPinManager.verifyPin(this, pinCode)) {
            // Successful authentication
            AppLog.i(TAG, "Admin authentication successful")
            
            // Check if user is still using default PIN
            if (AdminPinManager.isDefaultPin(this)) {
                AppLog.w(TAG, "⚠️ Admin is using default PIN - should be changed!")
                // TODO: Show warning to change PIN in admin panel
            }
            
            // Reset failed attempts on success
            failedAttempts = 0
            lockoutUntil = 0
            saveRateLimitState()
            
            // Navigate to admin panel
            val intent = Intent(this, AdminPanelActivity::class.java)
            startActivity(intent)
            finish()
        } else {
            // Failed authentication
            failedAttempts++
            AppLog.w(TAG, "Failed admin authentication attempt (${failedAttempts}/${WaterFountainConfig.ADMIN_MAX_ATTEMPTS})")
            
            // Check if we've hit the limit
            if (failedAttempts >= WaterFountainConfig.ADMIN_MAX_ATTEMPTS) {
                lockoutUntil = currentTime + WaterFountainConfig.ADMIN_LOCKOUT_DURATION_MS
                saveRateLimitState()
                
                AppLog.w(TAG, "Max attempts reached - locking out for 1 hour")
                
                // Show lockout message
                binding.subtitleText.text = "Too many attempts! Locked for 1 hour"
                binding.subtitleText.setTextColor(getColor(android.R.color.holo_red_dark))
                binding.pinDisplay.setTextColor(getColor(android.R.color.holo_red_dark))
                
                // Disable keypad
                binding.root.postDelayed({
                    setKeypadEnabled(false)
                    pinCode = ""
                    updatePinDisplay()
                    binding.pinDisplay.setTextColor(getColor(android.R.color.white))
                    isValidating = false
                    
                    // Start checking lockout status
                    checkLockoutStatus()
                }, 2000)
            } else {
                // Save incremented failed attempts
                saveRateLimitState()
                
                // Visual feedback for wrong PIN
                binding.pinDisplay.setTextColor(getColor(android.R.color.holo_red_dark))
                
                val remainingAttempts = WaterFountainConfig.ADMIN_MAX_ATTEMPTS - failedAttempts
                binding.subtitleText.text = "Wrong PIN - $remainingAttempts ${if (remainingAttempts == 1) "attempt" else "attempts"} remaining"
                binding.subtitleText.setTextColor(getColor(android.R.color.holo_orange_light))
                
                // Clear PIN and reset after delay
                binding.root.postDelayed({
                    binding.pinDisplay.setTextColor(getColor(android.R.color.white))
                    binding.subtitleText.text = "Enter Administrator PIN"
                    binding.subtitleText.setTextColor(getColor(com.waterfountainmachine.app.R.color.white_70))
                    pinCode = ""
                    updatePinDisplay()
                    isValidating = false
                }, 1500)
            }
        }
    }
    
    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }
    
    // Prevent hardware keys from exiting
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return HardwareKeyHandler.handleKeyDown(keyCode) { finish() }
            || super.onKeyDown(keyCode, event)
    }
    
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            FullScreenUtils.reapplyFullScreen(window, binding.root)
        }
    }
}
