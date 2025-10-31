package com.waterfountainmachine.app.admin

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import com.waterfountainmachine.app.databinding.ActivityAdminAuthBinding
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.utils.FullScreenUtils
import com.waterfountainmachine.app.utils.HardwareKeyHandler

class AdminAuthActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityAdminAuthBinding
    private var pinCode = ""
    private val correctPin = "01121999" // Hardcoded for now, will use TOTP later
    private var isValidating = false
    
    companion object {
        private const val TAG = "AdminAuthActivity"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupFullScreen()
        setupKeypad()
        setupUI()
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
        isValidating = true
        
        AppLog.i(TAG, "PIN validation attempted")
        
        if (pinCode == correctPin) {
            // Successful authentication
            AppLog.i(TAG, "Admin authentication successful")
            
            // Navigate to admin panel
            val intent = Intent(this, AdminPanelActivity::class.java)
            startActivity(intent)
            finish()
        } else {
            // Failed authentication
            AppLog.w(TAG, "Failed admin authentication attempt")
            
            // Visual feedback for wrong PIN
            binding.pinDisplay.setTextColor(getColor(android.R.color.holo_red_dark))
            
            // Clear PIN and reset after delay
            binding.root.postDelayed({
                binding.pinDisplay.setTextColor(getColor(android.R.color.white))
                pinCode = ""
                updatePinDisplay()
                isValidating = false
            }, 1000)
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
