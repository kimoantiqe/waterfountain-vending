# Next Steps - Action Plan

**Date:** November 5, 2025  
**Current Status:** Phase 3 In Progress (Blockers Fixed)

---

## ✅ Just Fixed (Immediate Blockers)

### 1. Added `isCertificateExpired()` method
- **File:** `CertificateManager.kt`
- **Issue:** RealAuthenticationRepository was calling non-existent method
- **Fix:** Added method that checks expiry timestamp
- **Status:** ✅ FIXED

### 2. Fixed Certificate Retrieval
- **File:** `RealAuthenticationRepository.kt`
- **Issue:** Called `getCertificate()` (returns CertificateData) instead of `getCertificatePem()` (returns String)
- **Lines Fixed:** 71, 137
- **Status:** ✅ FIXED

---

## 🎯 What to Do Next

### Option A: Complete Phase 3 (Real API Integration)
**Time Estimate:** 4-6 hours  
**Priority:** HIGH - Needed before Phase 4

#### Tasks:
1. **Test Current Implementation** (30 mins)
   - Build the app and verify no compilation errors
   - Test with mock mode first
   - Check all security components initialize correctly

2. **Configure Firebase Backend** (1 hour)
   - Set up Firebase project URL in app
   - Configure development environment
   - Test backend endpoints are accessible
   - Verify CA certificates are properly configured

3. **Add Network Dependencies** (15 mins)
   ```kotlin
   // Add to app/build.gradle.kts
   implementation("com.squareup.okhttp3:okhttp:4.12.0")
   implementation("com.squareup.retrofit2:retrofit:2.9.0")
   implementation("com.squareup.retrofit2:converter-gson:2.9.0")
   implementation("com.google.code.gson:gson:2.10.1")
   ```

4. **Improve RealAuthenticationRepository** (2-3 hours)
   - Upgrade from HttpURLConnection to OkHttp
   - Add proper connection pooling
   - Implement retry logic
   - Better error handling and parsing
   - Add timeout configuration

5. **Test Real API Calls** (1 hour)
   - Test requestOtp with real backend
   - Test verifyOtp with real backend
   - Test error scenarios
   - Verify certificate authentication works
   - Check rate limiting behavior

6. **Add SSL Pinning (Optional for Dev)** (1 hour)
   - Configure certificate pinning for production
   - Test with development certificates
   - Add pinning bypass for emulator testing

---

### Option B: Build Certificate Enrollment UI (Phase 4)
**Time Estimate:** 8-12 hours  
**Priority:** HIGH - Blocking production use

#### Why This Is Critical:
Currently there is **NO WAY** for a machine to get enrolled. You need:
1. UI to input machine ID
2. UI to input one-time token (from admin)
3. CSR generation and display
4. QR code generation for admin scanning
5. Certificate installation flow
6. Status monitoring

#### Detailed Tasks:

##### 1. Create CertificateSetupActivity/Fragment (3-4 hours)

**File to Create:** `CertificateSetupActivity.kt` or `CertificateSetupFragment.kt`

```kotlin
class CertificateSetupActivity : AppCompatActivity() {
    
    enum class EnrollmentState {
        NOT_STARTED,          // Initial state
        GENERATING_KEYS,      // Generating key pair
        DISPLAYING_QR,        // Showing QR code for admin
        WAITING_FOR_CERT,     // Waiting for admin to scan & approve
        INSTALLING_CERT,      // Installing received certificate
        COMPLETE,             // Enrollment successful
        ERROR                 // Error occurred
    }
    
    // UI Components needed:
    // - Machine ID display/input
    // - One-time token input field
    // - "Start Enrollment" button
    // - QR code image view
    // - Progress indicator
    // - Status text
    // - Error message display
    // - "Retry" button
}
```

**Layout to Create:** `activity_certificate_setup.xml`

**Features:**
- Step-by-step wizard UI
- QR code generation from CSR
- Real-time status updates
- Error handling with retry
- Success confirmation

##### 2. Implement CSR Generation (1 hour)

```kotlin
// Already exists in SecurityModule.generateCSR()
// Just need to wire it to UI

fun onStartEnrollment() {
    lifecycleScope.launch {
        try {
            setState(EnrollmentState.GENERATING_KEYS)
            
            val machineId = getMachineId() // From device or user input
            val csrJson = SecurityModule.generateCSR(machineId)
            
            setState(EnrollmentState.DISPLAYING_QR)
            displayQRCode(csrJson)
            
            // Wait for admin to scan and backend to issue certificate
            pollForCertificate(machineId)
            
        } catch (e: Exception) {
            setState(EnrollmentState.ERROR)
            showError(e.message)
        }
    }
}
```

##### 3. Add QR Code Generation (1 hour)

**Already have ZXing dependency!** (Check `build.gradle.kts`)

```kotlin
fun generateQRCode(data: String): Bitmap {
    val writer = QRCodeWriter()
    val bitMatrix = writer.encode(
        data,
        BarcodeFormat.QR_CODE,
        512, 512
    )
    
    val width = bitMatrix.width
    val height = bitMatrix.height
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
    
    for (x in 0 until width) {
        for (y in 0 until height) {
            bitmap.setPixel(
                x, y,
                if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
            )
        }
    }
    
    return bitmap
}
```

##### 4. Create Enrollment Backend API (2 hours)

**Option A: Polling Method** (Simpler)
```kotlin
// Machine polls backend to check if certificate is ready
suspend fun pollForCertificate(machineId: String) {
    repeat(60) { // 5 minutes max (5 second intervals)
        delay(5000)
        
        val cert = checkCertificateStatus(machineId)
        if (cert != null) {
            installCertificate(cert)
            setState(EnrollmentState.COMPLETE)
            return
        }
    }
    
    throw TimeoutException("Certificate not received within 5 minutes")
}
```

**Option B: Push Notification** (Better UX)
- Use Firebase Cloud Messaging
- Backend notifies app when certificate ready
- App fetches and installs certificate

##### 5. Create Certificate Status Screen (2 hours)

**File to Create:** `CertificateStatusFragment.kt`

```kotlin
class CertificateStatusFragment : Fragment() {
    
    // Display:
    // - Machine ID
    // - Certificate serial number
    // - Expiry date
    // - Days remaining
    // - Status (Valid, Expiring Soon, Expired)
    // - "Re-enroll" button
    // - "Test Connection" button
    
    fun loadCertificateInfo() {
        val info = SecurityModule.getCertificateInfo()
        
        if (info != null) {
            machineIdText.text = info["machineId"]
            serialNumberText.text = info["serialNumber"]
            expiryDateText.text = info["expiryDate"]
            daysRemainingText.text = info["daysRemaining"]
            statusText.text = info["status"]
            
            // Color code status
            when (info["status"]) {
                "Expired" -> statusText.setTextColor(Color.RED)
                "Expiring Soon" -> statusText.setTextColor(Color.ORANGE)
                else -> statusText.setTextColor(Color.GREEN)
            }
        } else {
            showNotEnrolledState()
        }
    }
}
```

##### 6. Add to Admin Panel (1-2 hours)

**Location:** `SystemFragment.kt` or new dedicated fragment

```kotlin
// Add buttons to admin panel:
// - "View Certificate Status"
// - "Enroll Machine"
// - "Re-enroll Machine"
// - "Test API Connection"
// - "Generate Enrollment Token"
```

##### 7. Create Enrollment Token Flow (2 hours)

This requires admin portal integration:

**Admin Portal Side:**
1. Admin selects "Add New Machine"
2. Admin enters machine ID
3. Backend generates one-time token
4. Admin provides token to machine operator

**Machine Side:**
1. Operator enters machine ID + token
2. Machine generates CSR with QR code
3. Admin scans QR code (or manually enters)
4. Backend validates token + CSR
5. Backend issues certificate
6. Machine installs certificate

---

### Option C: Testing & Documentation
**Time Estimate:** 2-4 hours  
**Priority:** MEDIUM - Important but not blocking

#### Tasks:
1. **Write Unit Tests** (2 hours)
   - Test CertificateManager methods
   - Test RequestSigner signing/verification
   - Test NonceGenerator uniqueness
   - Test SecurityModule integration

2. **Integration Testing** (1 hour)
   - Test full enrollment flow
   - Test OTP request flow
   - Test OTP verification flow
   - Test error scenarios

3. **Update Documentation** (1 hour)
   - Update PHASE3_SUMMARY.md
   - Document enrollment process
   - Add troubleshooting guide
   - Create admin guide

---

## 🎲 Recommended Approach

### **Recommended: Do Option B First (Certificate Enrollment UI)**

**Why?**
- Without enrollment UI, the app is **unusable** for production
- Real API testing (Option A) requires enrolled machines
- Can test enrollment flow with mock backend first
- Builds towards complete Phase 4

**Sequence:**
1. **Day 1 (4 hours):** Build basic enrollment UI
   - Create CertificateSetupActivity
   - Add machine ID input
   - Add one-time token input
   - Generate CSR and display as QR code

2. **Day 2 (4 hours):** Complete enrollment flow
   - Add certificate polling/waiting
   - Implement certificate installation
   - Add error handling
   - Create status screen

3. **Day 3 (4 hours):** Test & integrate
   - Test with mock backend first
   - Integrate with real backend
   - Add to admin panel
   - Update documentation

4. **Then:** Complete Option A (Real API with enrolled machine)

---

## 📋 Specific Code Tasks (Copy-Paste Ready)

### Task 1: Create Certificate Setup Layout

**File:** `app/src/main/res/layout/activity_certificate_setup.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout 
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:padding="24dp">

    <TextView
        android:id="@+id/titleText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Machine Enrollment"
        android:textSize="24sp"
        android:textStyle="bold"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent" />

    <com.google.android.material.textfield.TextInputLayout
        android:id="@+id/machineIdLayout"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="32dp"
        app:layout_constraintTop_toBottomOf="@id/titleText">

        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/machineIdInput"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="Machine ID" />
    </com.google.android.material.textfield.TextInputLayout>

    <com.google.android.material.textfield.TextInputLayout
        android:id="@+id/tokenLayout"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        app:layout_constraintTop_toBottomOf="@id/machineIdLayout">

        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/tokenInput"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="One-Time Token" />
    </com.google.android.material.textfield.TextInputLayout>

    <Button
        android:id="@+id/startEnrollmentButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="24dp"
        android:text="Start Enrollment"
        app:layout_constraintTop_toBottomOf="@id/tokenLayout" />

    <ImageView
        android:id="@+id/qrCodeImage"
        android:layout_width="300dp"
        android:layout_height="300dp"
        android:layout_marginTop="32dp"
        android:visibility="gone"
        app:layout_constraintTop_toBottomOf="@id/startEnrollmentButton"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <TextView
        android:id="@+id/statusText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="Ready to enroll"
        app:layout_constraintTop_toBottomOf="@id/qrCodeImage"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <ProgressBar
        android:id="@+id/progressBar"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:visibility="gone"
        app:layout_constraintTop_toBottomOf="@id/statusText"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

</androidx.constraintlayout.widget.ConstraintLayout>
```

### Task 2: Create Certificate Setup Activity

**File:** `app/src/main/java/com/waterfountainmachine/app/setup/CertificateSetupActivity.kt`

```kotlin
package com.waterfountainmachine.app.setup

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.waterfountainmachine.app.databinding.ActivityCertificateSetupBinding
import com.waterfountainmachine.app.security.SecurityModule
import com.waterfountainmachine.app.utils.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CertificateSetupActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityCertificateSetupBinding
    
    companion object {
        private const val TAG = "CertificateSetup"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCertificateSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
    }
    
    private fun setupUI() {
        binding.startEnrollmentButton.setOnClickListener {
            startEnrollment()
        }
        
        // Check if already enrolled
        if (SecurityModule.isEnrolled()) {
            showAlreadyEnrolled()
        }
    }
    
    private fun startEnrollment() {
        val machineId = binding.machineIdInput.text?.toString()
        val token = binding.tokenInput.text?.toString()
        
        if (machineId.isNullOrBlank()) {
            binding.machineIdLayout.error = "Machine ID required"
            return
        }
        
        if (token.isNullOrBlank()) {
            binding.tokenLayout.error = "One-time token required"
            return
        }
        
        binding.machineIdLayout.error = null
        binding.tokenLayout.error = null
        
        lifecycleScope.launch {
            try {
                showProgress("Generating keys...")
                
                // Generate CSR
                val csrJson = SecurityModule.generateCSR(machineId)
                
                showProgress("Display QR code to admin...")
                
                // Display QR code
                val qrBitmap = generateQRCode(csrJson)
                binding.qrCodeImage.setImageBitmap(qrBitmap)
                binding.qrCodeImage.visibility = View.VISIBLE
                
                showProgress("Waiting for admin approval...")
                
                // TODO: Implement backend polling or push notification
                // For now, simulate waiting
                delay(3000)
                
                // TODO: Call backend to check for certificate
                // val certificate = pollForCertificate(machineId, token)
                // SecurityModule.installCertificate(certificate)
                
                showSuccess("Enrollment complete!")
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Enrollment failed", e)
                showError("Enrollment failed: ${e.message}")
            }
        }
    }
    
    private fun generateQRCode(data: String): Bitmap {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, 512, 512)
        
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(
                    x, y,
                    if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
                )
            }
        }
        
        return bitmap
    }
    
    private fun showProgress(message: String) {
        binding.statusText.text = message
        binding.progressBar.visibility = View.VISIBLE
        binding.startEnrollmentButton.isEnabled = false
    }
    
    private fun showSuccess(message: String) {
        binding.statusText.text = message
        binding.statusText.setTextColor(Color.GREEN)
        binding.progressBar.visibility = View.GONE
        binding.startEnrollmentButton.isEnabled = true
    }
    
    private fun showError(message: String) {
        binding.statusText.text = message
        binding.statusText.setTextColor(Color.RED)
        binding.progressBar.visibility = View.GONE
        binding.startEnrollmentButton.isEnabled = true
    }
    
    private fun showAlreadyEnrolled() {
        binding.machineIdLayout.visibility = View.GONE
        binding.tokenLayout.visibility = View.GONE
        binding.startEnrollmentButton.visibility = View.GONE
        binding.statusText.text = "Machine already enrolled"
        binding.statusText.setTextColor(Color.GREEN)
    }
}
```

### Task 3: Add to AndroidManifest.xml

```xml
<activity
    android:name=".setup.CertificateSetupActivity"
    android:label="Machine Enrollment"
    android:theme="@style/Theme.AppCompat.Light" />
```

---

## 🔍 Testing Checklist

### Before Starting New Development:
- [ ] Build current code: `./gradlew build`
- [ ] Check for compilation errors
- [ ] Test app launches without crashes
- [ ] Verify SecurityModule initializes
- [ ] Check certificate methods work

### After Certificate UI Complete:
- [ ] Can enter machine ID and token
- [ ] CSR generates successfully
- [ ] QR code displays correctly
- [ ] Error handling works
- [ ] Status updates display

### After Backend Integration:
- [ ] Enrollment completes successfully
- [ ] Certificate installs correctly
- [ ] Can make authenticated API calls
- [ ] Error messages are clear
- [ ] Rate limiting works

---

## 📞 Questions to Answer

1. **Machine ID Strategy:**
   - Auto-generate from device ID?
   - Admin assigns and enters manually?
   - Scan from barcode/QR sticker?

2. **Token Distribution:**
   - Admin portal generates and displays?
   - Sent via email/SMS?
   - Printed on paper for technician?

3. **Admin Workflow:**
   - Admin scans QR directly on mobile?
   - Admin enters CSR manually in web portal?
   - Automated approval or manual review?

4. **Certificate Renewal:**
   - Automatic before expiry?
   - Manual re-enrollment required?
   - Grace period after expiry?

---

**Ready to start? Pick Option B (Certificate Enrollment UI) and let me know which task you want to tackle first!**
