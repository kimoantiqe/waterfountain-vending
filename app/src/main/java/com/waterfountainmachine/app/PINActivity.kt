package com.waterfountainmachine.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.waterfountainmachine.app.databinding.ActivityPinBinding

class PINActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPinBinding
    private val inactivityHandler = Handler(Looper.getMainLooper())
    private val inactivityTimeout = 30000L // 30 seconds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPinBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupFullScreen()
        setupClickListeners()
        startInactivityTimer()
    }

    private fun setupFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Hide system UI completely
        binding.root.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
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

    private fun startInactivityTimer() {
        inactivityHandler.postDelayed({
            returnToMainScreen()
        }, inactivityTimeout)
    }

    private fun returnToMainScreen() {
        // Navigate back to MainActivity
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun resetInactivityTimer() {
        inactivityHandler.removeCallbacksAndMessages(null)
        startInactivityTimer()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        resetInactivityTimer()
    }

    // Prevent back button from exiting app
    override fun onBackPressed() {
        returnToVendingScreen()
    }

    // Prevent hardware keys from exiting
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_RECENT_APPS -> true // Block these keys
            KeyEvent.KEYCODE_BACK -> {
                returnToVendingScreen()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setupFullScreen()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        inactivityHandler.removeCallbacksAndMessages(null)
    }
}
