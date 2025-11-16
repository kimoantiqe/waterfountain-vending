package com.waterfountainmachine.app.admin

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.waterfountainmachine.app.R
import com.waterfountainmachine.app.admin.fragments.*
import com.waterfountainmachine.app.databinding.ActivityAdminPanelBinding
import com.waterfountainmachine.app.utils.FullScreenUtils
import com.waterfountainmachine.app.utils.HardwareKeyHandler

class AdminPanelActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityAdminPanelBinding
    private var currentFragment: Fragment? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminPanelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupFullScreen()
        setupNavigation()
        
        // Start with certificate status
        navigateToFragment(CertificateStatusFragment(), "Certificate Status")
    }
    
    private fun setupFullScreen() {
        FullScreenUtils.setupFullScreen(window, binding.root)
    }
    
    private fun setupNavigation() {
        binding.exitButton.setOnClickListener {
            finish()
        }
        
        // Navigation buttons (Connection removed - was useless stub code)
        binding.certificateButton.setOnClickListener {
            navigateToFragment(CertificateStatusFragment(), "Certificate Status")
            updateNavigationState(binding.certificateButton)
        }
        
        binding.hardwareButton.setOnClickListener {
            navigateToFragment(HardwareTabsFragment(), "Hardware Diagnostics")
            updateNavigationState(binding.hardwareButton)
        }
        
        binding.logsButton.setOnClickListener {
            navigateToFragment(LogsFragment(), "Logs & Diagnostics")
            updateNavigationState(binding.logsButton)
        }
        
        binding.systemButton.setOnClickListener {
            navigateToFragment(SystemFragment(), "System Settings")
            updateNavigationState(binding.systemButton)
        }
        
        // Initially select certificate
        updateNavigationState(binding.certificateButton)
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
        listOf(
            binding.certificateButton,
            binding.hardwareButton, 
            binding.logsButton, 
            binding.systemButton
        ).forEach { button ->
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
