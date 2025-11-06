package com.waterfountainmachine.app.admin.fragments

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.waterfountainmachine.app.databinding.FragmentCertificateStatusBinding
import com.waterfountainmachine.app.security.SecurityModule
import com.waterfountainmachine.app.setup.CertificateSetupActivity
import com.waterfountainmachine.app.utils.AppLog

/**
 * Certificate Status Fragment
 * 
 * Displays current certificate enrollment status and provides
 * management options for administrators.
 * 
 * Features:
 * - View enrollment status
 * - View certificate details
 * - Enroll new machine
 * - Re-enroll (replace certificate)
 * - Test API connection
 * - Unenroll machine
 */
class CertificateStatusFragment : Fragment() {
    
    private var _binding: FragmentCertificateStatusBinding? = null
    private val binding get() = _binding!!
    
    companion object {
        private const val TAG = "CertificateStatus"
        
        fun newInstance() = CertificateStatusFragment()
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCertificateStatusBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupButtons()
        loadCertificateStatus()
    }
    
    override fun onResume() {
        super.onResume()
        // Refresh status when returning to fragment
        loadCertificateStatus()
    }
    
    private fun setupButtons() {
        binding.enrollButton.setOnClickListener {
            startEnrollment()
        }
        
        binding.testConnectionButton.setOnClickListener {
            testConnection()
        }
        
        binding.reenrollButton.setOnClickListener {
            confirmReenrollment()
        }
        
        binding.unenrollButton.setOnClickListener {
            confirmUnenrollment()
        }
    }
    
    private fun loadCertificateStatus() {
        if (SecurityModule.isEnrolled()) {
            showEnrolledState()
        } else {
            showNotEnrolledState()
        }
    }
    
    private fun showEnrolledState() {
        // Update enrollment status
        binding.enrollmentStatusText.text = "✓ Enrolled"
        binding.enrollmentStatusText.setTextColor(Color.parseColor("#4CAF50"))
        
        // Load certificate details
        val certInfo = SecurityModule.getCertificateInfo()
        
        if (certInfo != null) {
            binding.detailsCard.visibility = View.VISIBLE
            
            binding.machineIdText.text = certInfo["machineId"] ?: "-"
            binding.serialNumberText.text = certInfo["serialNumber"] ?: "-"
            binding.expiryDateText.text = certInfo["expiryDate"] ?: "-"
            
            val daysRemaining = certInfo["daysRemaining"]?.toIntOrNull() ?: 0
            binding.daysRemainingText.text = "$daysRemaining days"
            
            // Color code days remaining
            binding.daysRemainingText.setTextColor(when {
                daysRemaining < 7 -> Color.parseColor("#F44336") // Red
                daysRemaining < 30 -> Color.parseColor("#FF9800") // Orange
                else -> Color.parseColor("#4CAF50") // Green
            })
            
            val status = certInfo["status"] ?: "Unknown"
            binding.certificateStatusText.text = status
            
            // Color code status
            binding.certificateStatusText.setTextColor(when (status) {
                "Expired" -> Color.parseColor("#F44336")
                "Expiring Soon" -> Color.parseColor("#FF9800")
                "Valid" -> Color.parseColor("#4CAF50")
                else -> Color.parseColor("#666666")
            })
        }
        
        // Show enrolled buttons
        binding.enrollButton.visibility = View.GONE
        binding.testConnectionButton.visibility = View.VISIBLE
        binding.reenrollButton.visibility = View.VISIBLE
        binding.unenrollButton.visibility = View.VISIBLE
    }
    
    private fun showNotEnrolledState() {
        // Update enrollment status
        binding.enrollmentStatusText.text = "Not Enrolled"
        binding.enrollmentStatusText.setTextColor(Color.parseColor("#F44336"))
        
        // Hide details
        binding.detailsCard.visibility = View.GONE
        
        // Show not enrolled buttons
        binding.enrollButton.visibility = View.VISIBLE
        binding.testConnectionButton.visibility = View.GONE
        binding.reenrollButton.visibility = View.GONE
        binding.unenrollButton.visibility = View.GONE
    }
    
    private fun startEnrollment() {
        AppLog.d(TAG, "Starting enrollment process")
        val intent = Intent(requireContext(), CertificateSetupActivity::class.java)
        startActivity(intent)
    }
    
    private fun testConnection() {
        AppLog.d(TAG, "Testing API connection")
        
        // TODO: Implement actual API connection test
        // For now, show a simple dialog
        
        val isEnrolled = SecurityModule.isEnrolled()
        val isValid = SecurityModule.isEnrolled() && !SecurityModule.isCertificateExpiringSoon()
        
        AlertDialog.Builder(requireContext())
            .setTitle("Connection Test")
            .setMessage(buildString {
                append("Enrollment: ${if (isEnrolled) "✓ Yes" else "✗ No"}\n")
                append("Certificate: ${if (isValid) "✓ Valid" else "⚠ Expiring/Invalid"}\n")
                append("\n")
                append("To test real API connection, make a request to the backend.")
            })
            .setPositiveButton("OK", null)
            .show()
    }
    
    private fun confirmReenrollment() {
        AlertDialog.Builder(requireContext())
            .setTitle("Re-enroll Machine?")
            .setMessage("This will replace your current certificate with a new one. You'll need a new enrollment token from an administrator.")
            .setPositiveButton("Re-enroll") { _, _ ->
                performReenrollment()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun performReenrollment() {
        AppLog.d(TAG, "Performing re-enrollment")
        
        // Delete existing certificate
        SecurityModule.unenroll()
        
        // Refresh UI
        loadCertificateStatus()
        
        // Start enrollment
        startEnrollment()
    }
    
    private fun confirmUnenrollment() {
        AlertDialog.Builder(requireContext())
            .setTitle("Unenroll Machine?")
            .setMessage("This will remove your certificate and disable API access. You'll need to re-enroll to use the machine again.")
            .setPositiveButton("Unenroll") { _, _ ->
                performUnenrollment()
            }
            .setNegativeButton("Cancel", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }
    
    private fun performUnenrollment() {
        AppLog.d(TAG, "Performing unenrollment")
        
        try {
            SecurityModule.unenroll()
            
            // Refresh UI
            loadCertificateStatus()
            
            // Show success
            AlertDialog.Builder(requireContext())
                .setTitle("Unenrollment Complete")
                .setMessage("Certificate has been removed. The machine is no longer enrolled.")
                .setPositiveButton("OK", null)
                .show()
                
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to unenroll", e)
            
            AlertDialog.Builder(requireContext())
                .setTitle("Unenrollment Failed")
                .setMessage("Failed to remove certificate: ${e.message}")
                .setPositiveButton("OK", null)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show()
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
