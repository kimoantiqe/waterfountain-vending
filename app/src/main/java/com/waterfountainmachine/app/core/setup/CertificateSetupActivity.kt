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
import com.waterfountainmachine.app.workers.CertificateRenewalWorker
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
 * 6. Schedule automatic certificate renewal worker
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
        
        // Initialize Firebase Functions (uses deployed backend in us-central1)
        functions = FirebaseFunctions.getInstance()
        
        AppLog.d(TAG, "Firebase Functions initialized")
        AppLog.d(TAG, "Connecting to DEPLOYED backend (not emulator)")
        AppLog.d(TAG, "Project ID: waterfountain-dev")
        AppLog.d(TAG, "Region: us-central1")
        
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
        AppLog.d(TAG, "========================================")
        AppLog.d(TAG, "Starting machine enrollment process")
        AppLog.d(TAG, "Machine ID: $machineId")
        AppLog.d(TAG, "Token: ${token.take(5)}...${token.takeLast(5)}")
        AppLog.d(TAG, "========================================")
        
        // Step 1: Generate RSA key pair and extract public key in PEM format
        setState(EnrollmentState.GENERATING_KEYS)
        showProgress("Generating cryptographic keys...")
        delay(500) // Brief delay for UI feedback
        
        AppLog.d(TAG, "Step 1/3: Generating RSA key pair")
        val publicKeyPem = try {
            SecurityModule.generatePublicKeyPem(machineId)
        } catch (e: Exception) {
            AppLog.e(TAG, "Key generation failed", e)
            throw Exception("Failed to generate keys: ${e.message}", e)
        }
        
        AppLog.d(TAG, "✓ Public key generated successfully")
        AppLog.d(TAG, "Public key length: ${publicKeyPem.length} bytes")
        
        // Step 2: Call backend enrollMachineKey API
        setState(EnrollmentState.CALLING_BACKEND)
        showProgress("Enrolling with backend...")
        
        AppLog.d(TAG, "Step 2/3: Calling backend API")
        val certificate = try {
            callEnrollMachineKey(machineId, token, publicKeyPem)
        } catch (e: Exception) {
            AppLog.e(TAG, "Backend enrollment failed", e)
            throw Exception("Backend enrollment failed: ${e.message}", e)
        }
        
        AppLog.d(TAG, "✓ Certificate received from backend")
        
        // Step 3: Install certificate
        setState(EnrollmentState.INSTALLING_CERT)
        showProgress("Installing certificate...")
        delay(500)
        
        AppLog.d(TAG, "Step 3/3: Installing certificate")
        try {
            SecurityModule.installCertificate(certificate)
            AppLog.d(TAG, "✓ Certificate installed successfully")
        } catch (e: Exception) {
            AppLog.e(TAG, "Certificate installation failed", e)
            throw Exception("Failed to install certificate: ${e.message}", e)
        }
        
        // Step 4: Schedule automatic certificate renewal
        AppLog.d(TAG, "Step 4/4: Scheduling certificate renewal")
        try {
            CertificateRenewalWorker.schedule(this)
            AppLog.d(TAG, "✓ Certificate renewal worker scheduled")
        } catch (e: Exception) {
            // Don't fail enrollment if worker scheduling fails
            AppLog.w(TAG, "Failed to schedule renewal worker (non-critical)", e)
        }
        
        // Step 5: Complete
        setState(EnrollmentState.COMPLETE)
        showSuccess()
        
        AppLog.d(TAG, "========================================")
        AppLog.d(TAG, "✓✓✓ ENROLLMENT COMPLETED SUCCESSFULLY ✓✓✓")
        AppLog.d(TAG, "Machine ID: $machineId")
        AppLog.d(TAG, "Status: Enrolled and Active")
        AppLog.d(TAG, "Auto-renewal: Scheduled (daily check)")
        AppLog.d(TAG, "========================================")
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
        
        AppLog.d(TAG, "=== Enrollment API Call Start ===")
        AppLog.d(TAG, "Calling enrollMachineKey endpoint")
        AppLog.d(TAG, "Machine ID: $machineId")
        AppLog.d(TAG, "Token length: ${oneTimeToken.length} chars")
        AppLog.d(TAG, "Public key length: ${publicKeyPem.length} chars")
        AppLog.d(TAG, "Public key preview: ${publicKeyPem.take(100)}...")
        
        try {
            val result = functions
                .getHttpsCallable("enrollMachineKey")
                .call(data)
                .await()
            
            AppLog.d(TAG, "=== Backend Response Received ===")
            AppLog.d(TAG, "Raw result data type: ${result.data?.javaClass?.simpleName}")
            
            val resultData = result.data as? Map<*, *>
            if (resultData == null) {
                AppLog.e(TAG, "Backend response is not a Map! Data: ${result.data}")
                throw Exception("Invalid response from backend")
            }
            
            AppLog.d(TAG, "Response keys: ${resultData.keys}")
            AppLog.d(TAG, "Full response: $resultData")
            
            val success = resultData["success"] as? Boolean
            AppLog.d(TAG, "Success field: $success")
            
            if (success != true) {
                val error = resultData["error"] as? String ?: "Unknown error"
                AppLog.e(TAG, "Enrollment failed with error: $error")
                throw Exception(error)
            }
            
            val certificate = resultData["certificate"] as? String
            if (certificate == null) {
                AppLog.e(TAG, "Certificate not found in response!")
                AppLog.e(TAG, "Available fields: ${resultData.keys}")
                throw Exception("Certificate not found in response")
            }
            
            val validUntil = resultData["validUntil"] as? String
            AppLog.d(TAG, "Certificate received successfully")
            AppLog.d(TAG, "Certificate length: ${certificate.length} chars")
            AppLog.d(TAG, "Certificate valid until: $validUntil")
            AppLog.d(TAG, "Certificate preview: ${certificate.take(100)}...")
            AppLog.d(TAG, "=== Enrollment API Call Complete ===")
            
            return certificate
            
        } catch (e: com.google.firebase.functions.FirebaseFunctionsException) {
            AppLog.e(TAG, "=== Firebase Functions Exception ===")
            AppLog.e(TAG, "Error code: ${e.code}")
            AppLog.e(TAG, "Error message: ${e.message}")
            AppLog.e(TAG, "Error details: ${e.details}")
            
            // Map Firebase error codes to user-friendly messages
            val userMessage = when (e.code) {
                com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND -> 
                    "Enrollment token not found. Please generate a new token from the admin panel."
                com.google.firebase.functions.FirebaseFunctionsException.Code.PERMISSION_DENIED -> 
                    "Invalid enrollment token. Please check your token and try again."
                com.google.firebase.functions.FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> 
                    "Enrollment token has expired. Please generate a new token."
                com.google.firebase.functions.FirebaseFunctionsException.Code.ALREADY_EXISTS -> 
                    "Token already used. Please generate a new token."
                com.google.firebase.functions.FirebaseFunctionsException.Code.FAILED_PRECONDITION ->
                    "Token already used or revoked. Please generate a new token."
                com.google.firebase.functions.FirebaseFunctionsException.Code.UNAVAILABLE ->
                    "Backend service unavailable. Please check your internet connection and try again."
                com.google.firebase.functions.FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                    "Authentication failed. Please restart the app and try again."
                com.google.firebase.functions.FirebaseFunctionsException.Code.INTERNAL ->
                    "Internal Firebase error. This may be due to:\n" +
                    "• No internet connection\n" +
                    "• Emulator doesn't have Google Play Services\n" +
                    "• Firebase not fully initialized\n\n" +
                    "Try: Use a real device or emulator with Google Play, ensure internet connection, and restart the app."
                else -> "Enrollment failed: ${e.message}"
            }
            
            AppLog.e(TAG, "User-friendly message: $userMessage")
            throw Exception(userMessage)
            
        } catch (e: Exception) {
            AppLog.e(TAG, "=== Unexpected Exception ===")
            AppLog.e(TAG, "Exception type: ${e.javaClass.simpleName}")
            AppLog.e(TAG, "Exception message: ${e.message}")
            AppLog.e(TAG, "Stack trace:", e)
            throw e
        }
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
