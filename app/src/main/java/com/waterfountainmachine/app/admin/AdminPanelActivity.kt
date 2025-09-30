package com.waterfountainmachine.app.admin

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.waterfountainmachine.app.R
import com.waterfountainmachine.app.admin.fragments.*
import com.waterfountainmachine.app.databinding.ActivityAdminPanelBinding

class AdminPanelActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityAdminPanelBinding
    private var currentFragment: Fragment? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminPanelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupFullScreen()
        setupNavigation()
        
        // Start with connection management
        navigateToFragment(ConnectionFragment(), "Connection")
    }
    
    private fun setupFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        
        binding.root.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }
    
    private fun setupNavigation() {
        binding.exitButton.setOnClickListener {
            finish()
        }
        
        // Navigation buttons
        binding.connectionButton.setOnClickListener {
            navigateToFragment(ConnectionFragment(), "Connection")
            updateNavigationState(binding.connectionButton)
        }
        
        binding.hardwareButton.setOnClickListener {
            navigateToFragment(HardwareFragment(), "Hardware")
            updateNavigationState(binding.hardwareButton)
        }
        
        binding.logsButton.setOnClickListener {
            navigateToFragment(LogsFragment(), "Logs & Diagnostics")
            updateNavigationState(binding.logsButton)
        }
        
        binding.systemButton.setOnClickListener {
            navigateToFragment(SystemFragment(), "System")
            updateNavigationState(binding.systemButton)
        }
        
        // Initially select connection
        updateNavigationState(binding.connectionButton)
    }
    
    private fun navigateToFragment(fragment: Fragment, title: String) {
        currentFragment = fragment
        binding.panelTitle.text = title
        
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }
    
    private fun updateNavigationState(selectedButton: View) {
        // Reset all buttons
        listOf(binding.connectionButton, binding.hardwareButton, binding.logsButton, binding.systemButton).forEach { button ->
            button.isSelected = false
            button.alpha = 0.6f
        }
        
        // Highlight selected button
        selectedButton.isSelected = true
        selectedButton.alpha = 1.0f
    }
    
    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }
    
    // Prevent hardware keys from exiting
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_RECENT_APPS -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }
    
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setupFullScreen()
        }
    }
}
