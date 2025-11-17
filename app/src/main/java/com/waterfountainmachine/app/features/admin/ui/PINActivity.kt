package com.waterfountainmachine.app.activities

import android.app.Activity.OVERRIDE_TRANSITION_CLOSE
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import com.waterfountainmachine.app.databinding.ActivityPinBinding
import com.waterfountainmachine.app.utils.FullScreenUtils
import com.waterfountainmachine.app.utils.InactivityTimer
import com.waterfountainmachine.app.utils.HardwareKeyHandler

class PINActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPinBinding
    private lateinit var inactivityTimer: InactivityTimer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set window background to black to prevent white flash during transitions
        window.setBackgroundDrawableResource(android.R.color.black)
        
        binding = ActivityPinBinding.inflate(layoutInflater)
        setContentView(binding.root)

        FullScreenUtils.setupFullScreen(window, binding.root)
        setupClickListeners()
        
        // Setup back button handler using modern API
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                returnToVendingScreen()
            }
        })
        
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private fun returnToMainScreen() {
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        inactivityTimer.reset()
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
