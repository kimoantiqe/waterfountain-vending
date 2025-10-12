package com.waterfountainmachine.app.hardware

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.waterfountainmachine.app.utils.AppLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Helper class to request and manage USB device permissions
 */
class UsbPermissionHelper(private val context: Context) {
    
    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    
    companion object {
        private const val TAG = "UsbPermissionHelper"
        private const val ACTION_USB_PERMISSION = "com.waterfountainmachine.app.USB_PERMISSION"
    }
    
    /**
     * Check if app has permission for the given USB device
     */
    fun hasPermission(device: UsbDevice): Boolean {
        val hasPermission = usbManager.hasPermission(device)
        AppLog.d(TAG, "Permission check: ${device.productName} = $hasPermission")
        return hasPermission
    }
    
    /**
     * Request permission for USB device (suspending coroutine function)
     * Returns true if permission granted, false if denied
     */
    suspend fun requestPermission(device: UsbDevice): Boolean = suspendCancellableCoroutine { continuation ->
        AppLog.i(TAG, "Requesting permission: ${device.productName}")
        
        // Check if we already have permission
        if (usbManager.hasPermission(device)) {
            AppLog.d(TAG, "Permission already granted")
            continuation.resume(true)
            return@suspendCancellableCoroutine
        }
        
        // Create broadcast receiver for permission result
        val permissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (ACTION_USB_PERMISSION == intent.action) {
                    synchronized(this) {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        
                        if (device != null) {
                            if (granted) {
                                AppLog.i(TAG, "Permission GRANTED: ${device.productName}")
                            } else {
                                AppLog.w(TAG, "Permission DENIED: ${device.productName}")
                            }
                        }
                        
                        // Unregister receiver
                        try {
                            context.unregisterReceiver(this)
                        } catch (e: Exception) {
                            AppLog.e(TAG, "Error unregistering receiver", e)
                        }
                        
                        // Resume coroutine with result
                        continuation.resume(granted)
                    }
                }
            }
        }
        
        // Register broadcast receiver
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(permissionReceiver, filter)
        }
        
        // Request permission
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION),
            pendingIntentFlags
        )
        
        AppLog.d(TAG, "Showing permission dialog...")
        usbManager.requestPermission(device, permissionIntent)
        
        // Cancel cleanup
        continuation.invokeOnCancellation {
            try {
                context.unregisterReceiver(permissionReceiver)
            } catch (e: Exception) {
                // Already unregistered
            }
        }
    }
    
    /**
     * Get all USB devices connected
     */
    fun getConnectedDevices(): List<UsbDevice> {
        val devices = usbManager.deviceList.values.toList()
        AppLog.d(TAG, "Found ${devices.size} USB devices")
        devices.forEach { device ->
            AppLog.d(TAG, "  ${device.productName} (VID:${String.format("0x%04X", device.vendorId)}, PID:${String.format("0x%04X", device.productId)})")
        }
        return devices
    }
    
    /**
     * Request permission for all connected USB devices
     */
    suspend fun requestPermissionForAllDevices(): Boolean {
        val devices = getConnectedDevices()
        
        if (devices.isEmpty()) {
            AppLog.w(TAG, "No USB devices to request permission for")
            return false
        }
        
        var allGranted = true
        for (device in devices) {
            val granted = requestPermission(device)
            if (!granted) {
                allGranted = false
            }
        }
        
        return allGranted
    }
}
