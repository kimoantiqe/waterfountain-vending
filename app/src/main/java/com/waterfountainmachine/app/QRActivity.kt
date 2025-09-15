package com.waterfountainmachine.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

class QRActivity : AppCompatActivity() {

    private lateinit var qrCodeImage: ImageView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var statusText: TextView

    private var inactivityHandler = Handler(Looper.getMainLooper())
    private var inactivityRunnable: Runnable? = null
    private val inactivityTimeout = 60000L // 60 seconds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr)

        initializeViews()
        setupFullscreen()
        setupClickListeners()

        // Show spinner for 2 seconds then display mock QR code
        generateMockQRCode()
        setupInactivityTimer()
    }

    private fun initializeViews() {
        qrCodeImage = findViewById(R.id.qrCodeImage)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        statusText = findViewById(R.id.statusText)
    }

    private fun setupFullscreen() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    private fun setupClickListeners() {
        findViewById<View>(R.id.backButton).setOnClickListener {
            finish()
        }

        findViewById<View>(R.id.refreshButton).setOnClickListener {
            resetInactivityTimer()
            // Show spinner again and regenerate QR code
            showLoadingState()
            generateMockQRCode()
        }
    }

    private fun generateMockQRCode() {
        // Show loading state initially
        showLoadingState()

        // After 2 seconds, hide spinner and show QR code
        Handler(Looper.getMainLooper()).postDelayed({
            hideLoadingAndShowQR()
        }, 2000)
    }

    private fun showLoadingState() {
        loadingIndicator.visibility = View.VISIBLE
        qrCodeImage.visibility = View.GONE
        statusText.text = getString(R.string.generating_qr)
        findViewById<View>(R.id.refreshButton).visibility = View.GONE
    }

    private fun hideLoadingAndShowQR() {
        loadingIndicator.visibility = View.GONE
        qrCodeImage.visibility = View.VISIBLE
        statusText.text = "Ready to scan"
        findViewById<View>(R.id.refreshButton).visibility = View.VISIBLE

        // Generate mock QR code bitmap
        val mockQRData = "https://waterfountain.app/verify?session=${System.currentTimeMillis()}"
        val qrBitmap = generateQRCodeBitmap(mockQRData, 500, 500)
        qrCodeImage.setImageBitmap(qrBitmap)
    }

    private fun generateQRCodeBitmap(text: String, width: Int, height: Int): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix: BitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: WriterException) {
            e.printStackTrace()
            null
        }
    }

    private fun setupInactivityTimer() {
        resetInactivityTimer()
    }

    private fun resetInactivityTimer() {
        inactivityRunnable?.let { inactivityHandler.removeCallbacks(it) }

        inactivityRunnable = Runnable {
            // Return to main activity after inactivity
            finish()
        }

        inactivityHandler.postDelayed(inactivityRunnable!!, inactivityTimeout)
    }

    override fun onDestroy() {
        super.onDestroy()
        inactivityRunnable?.let { inactivityHandler.removeCallbacks(it) }
    }

    override fun onResume() {
        super.onResume()
        setupFullscreen()
        resetInactivityTimer()
    }

    override fun onPause() {
        super.onPause()
        inactivityRunnable?.let { inactivityHandler.removeCallbacks(it) }
    }
}
