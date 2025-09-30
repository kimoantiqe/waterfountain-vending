package com.waterfountainmachine.app.hardware.sdk

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay

/**
 * Real USB Serial Communicator using usb-serial-for-android library
 * Supports FTDI, Prolific, CH340, CP210x and other USB-to-serial chips
 */
class UsbSerialCommunicator(private val context: Context) : SerialCommunicator {
    
    private var usbManager: UsbManager? = null
    private var usbDevice: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var serialPort: UsbSerialPort? = null
    private var isConnected = false
    
    private val readBuffer = ByteArray(8192)
    
    companion object {
        private const val TAG = "UsbSerialCommunicator"
        private const val WRITE_TIMEOUT_MS = 2000
        private const val READ_TIMEOUT_MS = 5000
    }
    
    override suspend fun connect(config: SerialConfig): Boolean {
        try {
            // Get USB manager
            usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            
            // Find available USB serial devices
            val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            
            if (availableDrivers.isEmpty()) {
                android.util.Log.e(TAG, "No USB serial devices found")
                return false
            }
            
            // Use the first available driver
            val driver: UsbSerialDriver = availableDrivers[0]
            usbDevice = driver.device
            
            android.util.Log.i(TAG, "Found USB device: ${usbDevice?.productName} (${usbDevice?.vendorId}:${usbDevice?.productId})")
            
            // Open connection
            connection = usbManager?.openDevice(usbDevice)
            if (connection == null) {
                android.util.Log.e(TAG, "Failed to open USB device. Check permissions.")
                return false
            }
            
            // Get the first port (most devices have only one port)
            serialPort = driver.ports.firstOrNull()
            if (serialPort == null) {
                android.util.Log.e(TAG, "No serial ports available on device")
                return false
            }
            
            // Open serial port
            serialPort?.open(connection)
            
            // Configure serial port
            serialPort?.setParameters(
                config.baudRate,
                config.dataBits,
                when (config.stopBits) {
                    1 -> UsbSerialPort.STOPBITS_1
                    2 -> UsbSerialPort.STOPBITS_2
                    else -> UsbSerialPort.STOPBITS_1
                },
                when (config.parity) {
                    SerialConfig.Parity.NONE -> UsbSerialPort.PARITY_NONE
                    SerialConfig.Parity.ODD -> UsbSerialPort.PARITY_ODD
                    SerialConfig.Parity.EVEN -> UsbSerialPort.PARITY_EVEN
                }
            )
            
            // Set flow control if needed
            when (config.flowControl) {
                SerialConfig.FlowControl.HARDWARE -> {
                    serialPort?.setRTS(true)
                    serialPort?.setDTR(true)
                }
                SerialConfig.FlowControl.SOFTWARE -> {
                    // XON/XOFF not directly supported, handled at protocol level
                }
                SerialConfig.FlowControl.NONE -> {
                    serialPort?.setRTS(false)
                    serialPort?.setDTR(false)
                }
            }
            
            isConnected = true
            android.util.Log.i(TAG, "USB Serial connection established: ${config.baudRate} baud, ${config.dataBits}N${config.stopBits}")
            
            return true
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to connect to USB serial device", e)
            disconnect()
            return false
        }
    }
    
    override suspend fun disconnect() {
        try {
            serialPort?.close()
            connection?.close()
            
            serialPort = null
            connection = null
            usbDevice = null
            usbManager = null
            isConnected = false
            
            android.util.Log.i(TAG, "USB Serial connection closed")
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error disconnecting USB serial", e)
        }
    }
    
    override fun isConnected(): Boolean {
        return isConnected && serialPort != null
    }
    
    override suspend fun sendData(data: ByteArray): Boolean {
        if (!isConnected()) {
            android.util.Log.e(TAG, "Cannot send data - not connected")
            return false
        }
        
        return try {
            val bytesWritten = serialPort?.write(data, WRITE_TIMEOUT_MS) ?: 0
            
            if (bytesWritten == data.size) {
                android.util.Log.d(TAG, "Sent ${data.size} bytes: ${data.joinToString(" ") { "0x%02X".format(it) }}")
                true
            } else {
                android.util.Log.e(TAG, "Write incomplete: ${bytesWritten}/${data.size} bytes")
                false
            }
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error sending data", e)
            false
        }
    }
    
    override suspend fun readData(timeoutMs: Long): ByteArray? {
        if (!isConnected()) {
            android.util.Log.e(TAG, "Cannot read data - not connected")
            return null
        }
        
        return try {
            val startTime = System.currentTimeMillis()
            var bytesRead = 0
            
            // Try to read data within timeout
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                bytesRead = serialPort?.read(readBuffer, READ_TIMEOUT_MS.toInt()) ?: 0
                
                if (bytesRead > 0) {
                    val receivedData = readBuffer.copyOfRange(0, bytesRead)
                    android.util.Log.d(TAG, "Received $bytesRead bytes: ${receivedData.joinToString(" ") { "0x%02X".format(it) }}")
                    return receivedData
                }
                
                // Small delay before retry
                delay(10)
            }
            
            android.util.Log.w(TAG, "Read timeout after ${timeoutMs}ms")
            null
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error reading data", e)
            null
        }
    }
    
    override fun getDataFlow(): Flow<ByteArray> = flow {
        while (isConnected()) {
            try {
                val data = readData(1000)
                if (data != null) {
                    emit(data)
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error in data flow", e)
                break
            }
        }
    }
    
    override suspend fun clearBuffers() {
        try {
            serialPort?.purgeHwBuffers(true, true)
            android.util.Log.d(TAG, "USB Serial buffers cleared")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error clearing buffers", e)
        }
    }
    
    /**
     * Get list of available USB serial devices
     */
    fun getAvailableDevices(): List<UsbDevice> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        return drivers.map { it.device }
    }
    
    /**
     * Get device information
     */
    fun getDeviceInfo(): String? {
        return usbDevice?.let {
            "Device: ${it.productName}\nVendor ID: ${it.vendorId}\nProduct ID: ${it.productId}\nSerial: ${it.serialNumber}"
        }
    }
}
