package com.waterfountainmachine.app.setup

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.functions.FirebaseFunctions
import com.waterfountainmachine.app.databinding.ActivityCertificateSetupBinding
import com.waterfountainmachine.app.security.SecurityModule
import com.waterfountainmachine.app.utils.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Certificate Setup Activity
 * 
 * Guides users through the machine enrollment process:
 * 1. Enter machine ID and one-time token
 * 2. Generate RSA key pair and extract public key in PEM format
 * 3. Call backend enrollMachineKey API with machineId, token, and public key
 * 4. Backend returns certificate immediately (synchronous response)
 * 5. Install certificate and complete enrollment
 * 
 * IMPORTANT: This is a direct API call, NOT a QR code workflow.
 * The backend's enrollMachineKey endpoint returns the certificate synchronously.
 */
class CertificateSetupActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityCertificateSetupBinding
    private lateinit var functions: FirebaseFunctions
    
    private enum class EnrollmentState {
        NOT_STARTED,
        GENERATING_KEYS,
        CALLING_BACKEND,
        INSTALLING_CERT,
        COMPLETE,
        ERROR
    }
    
    private var currentState = EnrollmentState.NOT_STARTED
    
    companion object {
        private const val TAG = "CertificateSetup"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCertificateSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize Firebase Functions
        functions = FirebaseFunctions.getInstance()
        
        setupUI()
        checkExistingEnrollment()
    }
    
    private fun setupUI() {
        binding.startEnrollmentButton.setOnClickListener {
            startEnrollment()
        }
        
        binding.retryButton.setOnClickListener {
            resetUI()
        }
        
        binding.cancelButton.setOnClickListener {
            finish()
        }
    }
    
    private fun checkExistingEnrollment() {
        if (SecurityModule.isEnrolled()) {
            showAlreadyEnrolled()
        }
    }
    
    private fun startEnrollment() {
        val machineId = binding.machineIdInput.text?.toString()?.trim()
        val token = binding.tokenInput.text?.toString()?.trim()
        
        // Validate inputs
        if (machineId.isNullOrBlank()) {
            binding.machineIdLayout.error = "Machine ID is required"
            return
        }
        
        if (token.isNullOrBlank()) {
            binding.tokenLayout.error = "One-time token is required"
            return
        }
        
        // Clear errors
        binding.machineIdLayout.error = null
        binding.tokenLayout.error = null
        
        // Disable inputs during enrollment
        binding.machineIdInput.isEnabled = false
        binding.tokenInput.isEnabled = false
        binding.startEnrollmentButton.isEnabled = false
        
        // Start enrollment process
        lifecycleScope.launch {
            try {
                enrollMachine(machineId, token)
            } catch (e: Exception) {
                AppLog.e(TAG, "Enrollment failed", e)
                showError("Enrollment failed: ${e.message}")
            }
        }
    }
    
    private suspend fun enrollMachine(machineId: String, token: String) {
        // Step 1: Generate RSA key pair and extract public key in PEM format
        setState(EnrollmentState.GENERATING_KEYS)
        showProgress("Generating cryptographic keys...")
        delay(500) // Brief delay for UI feedback
        
        val publicKeyPem = try {
            SecurityModule.generatePublicKeyPem(machineId)
        } catch (e: Exception) {
            throw Exception("Failed to generate keys: ${e.message}", e)
        }
        
        AppLog.d(TAG, "Public key generated for machine: $machineId")
        
        // Step 2: Call backend enrollMachineKey API
        setState(EnrollmentState.CALLING_BACKEND)
        showProgress("Enrolling with backend...")
        
        val certificate = try {
            callEnrollMachineKey(machineId, token, publicKeyPem)
        } catch (e: Exception) {
            throw Exception("Backend enrollment failed: ${e.message}", e)
        }
        
        AppLog.d(TAG, "Certificate received from backend")
        
        // Step 3: Install certificate
        setState(EnrollmentState.INSTALLING_CERT)
        showProgress("Installing certificate...")
        delay(500)
        
        try {
            SecurityModule.installCertificate(certificate)
        } catch (e: Exception) {
            throw Exception("Failed to install certificate: ${e.message}", e)
        }
        
        // Step 4: Complete
        setState(EnrollmentState.COMPLETE)
        showSuccess()
    }
    
    /**
     * Call backend enrollMachineKey Firebase Function.
     * 
     * Backend expects: { machineId, oneTimeToken, publicKeyPem }
     * Backend returns: { success: true, certificate: "...", validUntil: "..." }
     */
    private suspend fun callEnrollMachineKey(
        machineId: String,
        oneTimeToken: String,
        publicKeyPem: String
    ): String {
        val data = hashMapOf(
            "machineId" to machineId,
            "oneTimeToken" to oneTimeToken,
            "publicKeyPem" to publicKeyPem
        )
        
        val result = functions
            .getHttpsCallable("enrollMachineKey")
            .call(data)
            .await()
        
        val resultData = result.data as? Map<*, *>
            ?: throw Exception("Invalid response from backend")
        
        val success = resultData["success"] as? Boolean
        if (success != true) {
            val error = resultData["error"] as? String ?: "Unknown error"
            throw Exception(error)
        }
        
        val certificate = resultData["certificate"] as? String
            ?: throw Exception("Certificate not found in response")
        
        return certificate
    }
    
    private fun setState(state: EnrollmentState) {
        currentState = state
        AppLog.d(TAG, "Enrollment state: $state")
    }
    
    private fun showProgress(message: String) {
        binding.statusText.text = message
        binding.statusText.setTextColor(Color.parseColor("#1976D2"))
        binding.progressBar.visibility = View.VISIBLE
        binding.retryButton.visibility = View.GONE
    }
    
    private fun showSuccess() {
        binding.statusText.text = "✓ Enrollment Complete!"
        binding.statusText.setTextColor(Color.parseColor("#4CAF50"))
        binding.progressBar.visibility = View.GONE
        binding.statusDetailsText.visibility = View.VISIBLE
        binding.statusDetailsText.text = 
            "Machine successfully enrolled.\nYou can now use certificate-based authentication."
        
        // Show completion dialog
        AlertDialog.Builder(this)
            .setTitle("Enrollment Complete")
            .setMessage("Your machine has been successfully enrolled with a security certificate. You can now close this screen.")
            .setPositiveButton("Close") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }
    
    private fun showError(message: String) {
        binding.statusText.text = "✗ Enrollment Failed"
        binding.statusText.setTextColor(Color.parseColor("#F44336"))
        binding.progressBar.visibility = View.GONE
        binding.retryButton.visibility = View.VISIBLE
        binding.statusDetailsText.visibility = View.VISIBLE
        binding.statusDetailsText.text = message
        
        // Re-enable inputs
        binding.machineIdInput.isEnabled = true
        binding.tokenInput.isEnabled = true
        binding.startEnrollmentButton.isEnabled = true
    }
    
    private fun showAlreadyEnrolled() {
        val certInfo = SecurityModule.getCertificateInfo()
        
        binding.machineIdLayout.visibility = View.GONE
        binding.tokenLayout.visibility = View.GONE
        binding.startEnrollmentButton.visibility = View.GONE
        binding.cancelButton.text = "Close"
        
        binding.statusText.text = "✓ Already Enrolled"
        binding.statusText.setTextColor(Color.parseColor("#4CAF50"))
        
        binding.statusDetailsText.visibility = View.VISIBLE
        binding.statusDetailsText.text = buildString {
            append("This machine is already enrolled.\n\n")
            if (certInfo != null) {
                append("Machine ID: ${certInfo["machineId"]}\n")
                append("Expires: ${certInfo["expiryDate"]}\n")
                append("Status: ${certInfo["status"]}")
            }
        }
    }
    
    private fun resetUI() {
        currentState = EnrollmentState.NOT_STARTED
        
        binding.machineIdInput.isEnabled = true
        binding.tokenInput.isEnabled = true
        binding.startEnrollmentButton.isEnabled = true
        
        binding.statusText.text = "Ready to enroll"
        binding.statusText.setTextColor(Color.parseColor("#666666"))
        binding.progressBar.visibility = View.GONE
        binding.statusDetailsText.visibility = View.GONE
        binding.retryButton.visibility = View.GONE
    }
}
