package com.waterfountainmachine.app.admin.fragments

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.waterfountainmachine.app.databinding.FragmentCertificateStatusBinding
import com.waterfountainmachine.app.security.SecurityModule
import com.waterfountainmachine.app.setup.CertificateSetupActivity
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.utils.AdminDebugConfig
import com.waterfountainmachine.app.config.ApiEnvironment
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

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
        AdminDebugConfig.logAdmin(requireContext(), TAG, "Starting enrollment process")
        val intent = Intent(requireContext(), CertificateSetupActivity::class.java)
        startActivity(intent)
    }
    
    private fun testConnection() {
        AdminDebugConfig.logAdmin(requireContext(), TAG, "Testing API connection with certificate")
        
        if (!SecurityModule.isEnrolled()) {
            AlertDialog.Builder(requireContext())
                .setTitle("Not Enrolled")
                .setMessage("Machine must be enrolled before testing connection.")
                .setPositiveButton("OK", null)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show()
            return
        }
        
        // Show loading state
        binding.testConnectionButton.isEnabled = false
        binding.testConnectionButton.text = "Testing..."
        
        lifecycleScope.launch {
            try {
                val result = testBackendConnection()
                
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(requireContext())
                        .setTitle(if (result.success) "✓ Connection Successful" else "✗ Connection Failed")
                        .setMessage(buildString {
                            append("Endpoint: ${result.endpoint}\n")
                            append("Status: ${result.statusCode}\n")
                            append("Response Time: ${result.responseTime}ms\n\n")
                            
                            if (result.success) {
                                append("✓ Certificate authentication successful\n")
                                append("✓ Backend is reachable\n")
                                append("✓ Machine is properly enrolled")
                            } else {
                                append("Error: ${result.error}\n\n")
                                append("Possible causes:\n")
                                append("• Certificate expired or invalid\n")
                                append("• Network connectivity issues\n")
                                append("• Backend server unavailable")
                            }
                        })
                        .setPositiveButton("OK", null)
                        .setIcon(if (result.success) 
                            android.R.drawable.ic_dialog_info 
                            else android.R.drawable.ic_dialog_alert)
                        .show()
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "Connection test failed", e)
                
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Connection Test Failed")
                        .setMessage("Failed to test connection: ${e.message}")
                        .setPositiveButton("OK", null)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.testConnectionButton.isEnabled = true
                    binding.testConnectionButton.text = "Test Connection"
                }
            }
        }
    }
    
    /**
     * Test backend connection with certificate authentication
     */
    private suspend fun testBackendConnection(): ConnectionTestResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        try {
            // Get backend URL
            val baseUrl = ApiEnvironment.getCurrent().baseUrl
            val healthEndpoint = "$baseUrl/api/v1/health"
            
            AdminDebugConfig.logAdmin(requireContext(), TAG, "Testing connection to: $healthEndpoint")
            
            // Build HTTP client with certificate authentication
            val sslContext = SecurityModule.getSslContext()
            val client = OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, SecurityModule.getTrustManager())
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            
            // Make request
            val request = Request.Builder()
                .url(healthEndpoint)
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            val responseTime = System.currentTimeMillis() - startTime
            val statusCode = response.code()
            
            response.use {
                when {
                    statusCode in 200..299 -> {
                        AdminDebugConfig.logAdminInfo(requireContext(), TAG, "✓ Connection test successful: $statusCode in ${responseTime}ms")
                        ConnectionTestResult(
                            success = true,
                            endpoint = healthEndpoint,
                            statusCode = statusCode,
                            responseTime = responseTime,
                            error = null
                        )
                    }
                    statusCode == 401 -> {
                        AdminDebugConfig.logAdminWarning(requireContext(), TAG, "✗ Connection test failed: Unauthorized (certificate issue)")
                        ConnectionTestResult(
                            success = false,
                            endpoint = healthEndpoint,
                            statusCode = statusCode,
                            responseTime = responseTime,
                            error = "Certificate authentication failed (401 Unauthorized)"
                        )
                    }
                    else -> {
                        AdminDebugConfig.logAdminWarning(requireContext(), TAG, "✗ Connection test failed: HTTP $statusCode")
                        ConnectionTestResult(
                            success = false,
                            endpoint = healthEndpoint,
                            statusCode = statusCode,
                            responseTime = responseTime,
                            error = "HTTP $statusCode: ${response.message()}"
                        )
                    }
                }
            }
        } catch (e: java.net.UnknownHostException) {
            AppLog.e(TAG, "Connection test failed: Unknown host", e)
            ConnectionTestResult(
                success = false,
                endpoint = ApiEnvironment.getCurrent().baseUrl,
                statusCode = 0,
                responseTime = System.currentTimeMillis() - startTime,
                error = "Cannot reach backend server (DNS/Network issue)"
            )
        } catch (e: javax.net.ssl.SSLException) {
            AppLog.e(TAG, "Connection test failed: SSL error", e)
            ConnectionTestResult(
                success = false,
                endpoint = ApiEnvironment.getCurrent().baseUrl,
                statusCode = 0,
                responseTime = System.currentTimeMillis() - startTime,
                error = "SSL/Certificate error: ${e.message}"
            )
        } catch (e: java.net.SocketTimeoutException) {
            AppLog.e(TAG, "Connection test failed: Timeout", e)
            ConnectionTestResult(
                success = false,
                endpoint = ApiEnvironment.getCurrent().baseUrl,
                statusCode = 0,
                responseTime = System.currentTimeMillis() - startTime,
                error = "Connection timeout (backend not responding)"
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "Connection test failed: ${e.javaClass.simpleName}", e)
            ConnectionTestResult(
                success = false,
                endpoint = ApiEnvironment.getCurrent().baseUrl,
                statusCode = 0,
                responseTime = System.currentTimeMillis() - startTime,
                error = "${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }
    
    /**
     * Result of connection test
     */
    private data class ConnectionTestResult(
        val success: Boolean,
        val endpoint: String,
        val statusCode: Int,
        val responseTime: Long,
        val error: String?
    )
    
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
        AdminDebugConfig.logAdmin(requireContext(), TAG, "Performing re-enrollment")
        
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
        AdminDebugConfig.logAdmin(requireContext(), TAG, "Performing unenrollment")
        
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
