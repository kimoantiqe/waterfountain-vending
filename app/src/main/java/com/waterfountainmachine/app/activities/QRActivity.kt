package com.waterfountainmachine.app.activities

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.waterfountainmachine.app.R
import com.waterfountainmachine.app.utils.FullScreenUtils
import com.waterfountainmachine.app.utils.InactivityTimer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class QRActivity : AppCompatActivity() {

    private lateinit var qrCodeImage: ImageView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var inactivityTimer: InactivityTimer
    
    companion object {
        private const val QR_GENERATION_DELAY_MS = 2000L
        private const val QR_CODE_SIZE = 500
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr)

        initializeViews()
        FullScreenUtils.setupFullScreen(window, findViewById(android.R.id.content))
        setupClickListeners()

        inactivityTimer = InactivityTimer(60000L) { finish() }
        inactivityTimer.start()

        // Show spinner for 2 seconds then display mock QR code
        generateMockQRCode()
    }

    private fun initializeViews() {
        qrCodeImage = findViewById(R.id.qrCodeImage)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        statusText = findViewById(R.id.statusText)
    }

    private fun setupClickListeners() {
        findViewById<View>(R.id.backButton).setOnClickListener {
            finish()
        }

        findViewById<View>(R.id.refreshButton).setOnClickListener {
            inactivityTimer.reset()
            // Show spinner again and regenerate QR code
            showLoadingState()
            generateMockQRCode()
        }
    }

    private fun generateMockQRCode() {
        // Show loading state initially
        showLoadingState()

        // After 2 seconds, hide spinner and show QR code using coroutines
        lifecycleScope.launch {
            delay(QR_GENERATION_DELAY_MS)
            hideLoadingAndShowQR()
        }
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
        val qrBitmap = generateQRCodeBitmap(mockQRData, QR_CODE_SIZE, QR_CODE_SIZE)
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

    override fun onDestroy() {
        super.onDestroy()
        inactivityTimer.cleanup()
    }

    override fun onResume() {
        super.onResume()
        FullScreenUtils.reapplyFullScreen(window, findViewById(android.R.id.content))
        inactivityTimer.reset()
    }

    override fun onPause() {
        super.onPause()
        inactivityTimer.stop()
    }
}
