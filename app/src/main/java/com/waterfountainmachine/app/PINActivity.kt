package com.waterfountainmachine.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import com.waterfountainmachine.app.databinding.ActivityPinBinding
import com.waterfountainmachine.app.utils.FullScreenUtils
import com.waterfountainmachine.app.utils.InactivityTimer
import com.waterfountainmachine.app.utils.HardwareKeyHandler

class PINActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPinBinding
    private lateinit var inactivityTimer: InactivityTimer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPinBinding.inflate(layoutInflater)
        setContentView(binding.root)

        FullScreenUtils.setupFullScreen(window, binding.root)
        setupClickListeners()
        
        inactivityTimer = InactivityTimer(30000L) { returnToMainScreen() }
        inactivityTimer.start()
    }

    private fun setupClickListeners() {
        binding.backButton.setOnClickListener {
            returnToVendingScreen()
        }
    }

    private fun returnToVendingScreen() {
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun returnToMainScreen() {
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        inactivityTimer.reset()
    }

    // Prevent back button from exiting app
    override fun onBackPressed() {
        super.onBackPressed()
        returnToVendingScreen()
    }

    // Prevent hardware keys from exiting
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return HardwareKeyHandler.handleKeyDown(keyCode) { returnToVendingScreen() }
            || super.onKeyDown(keyCode, event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            FullScreenUtils.reapplyFullScreen(window, binding.root)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        inactivityTimer.cleanup()
    }
}
