package com.waterfountainmachine.app.hardware.sdk

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.ProlificSerialDriver
import com.waterfountainmachine.app.hardware.UsbPermissionHelper
import com.waterfountainmachine.app.utils.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay

/**
 * Real USB Serial Communicator using usb-serial-for-android library
 * Supports FTDI, Prolific, CH340, CP210x and other USB-to-serial chips
 */
class UsbSerialCommunicator(private val context: Context) : SerialCommunicator {
    
    private val permissionHelper = UsbPermissionHelper(context)
    
    private var usbManager: UsbManager? = null
    private var usbDevice: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var serialPort: UsbSerialPort? = null
    private var isConnected = false
    
    private val readBuffer = ByteArray(8192)
    
    companion object {
        private const val TAG = "UsbSerialCommunicator"
        private const val WRITE_TIMEOUT_MS = 5000  // Increased from 2000ms
        private const val READ_TIMEOUT_MS = 10000  // Increased from 5000ms
        private const val VMC_WARMUP_DELAY_MS = 500L  // VMC startup delay
    }
    
    override suspend fun connect(config: SerialConfig): Boolean {
        try {
            AppLog.i(TAG, "===== USB Serial Connection Start =====")
            AppLog.d(TAG, "Config: ${config.baudRate} baud, ${config.dataBits}${config.parity.name[0]}${config.stopBits}")
            
            // Step 1: Get USB manager
            AppLog.d(TAG, "[1/8] Getting USB Manager...")
            usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            
            // Step 2: Find available USB serial devices
            AppLog.d(TAG, "[2/8] Probing for USB serial devices...")
            val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            AppLog.i(TAG, "Found ${availableDrivers.size} USB serial device(s)")
            
            if (availableDrivers.isEmpty()) {
                AppLog.e(TAG, "No USB serial devices found")
                
                // List all connected USB devices for debugging
                val allDevices = usbManager?.deviceList
                AppLog.d(TAG, "Total USB devices: ${allDevices?.size ?: 0}")
                allDevices?.values?.forEach { device ->
                    AppLog.d(TAG, "  USB: ${device.productName ?: "Unknown"} (VID:${String.format("0x%04X", device.vendorId)}, PID:${String.format("0x%04X", device.productId)})")
                }
                
                return false
            }
            
            // Step 3: Select USB serial driver
            AppLog.d(TAG, "[3/8] Selecting USB serial driver...")
            val driver: UsbSerialDriver = availableDrivers[0]
            usbDevice = driver.device
            
            // Enhanced chipset detection logging for admin panel diagnostics
            val supportedChipset = when(driver) {
                is FtdiSerialDriver -> "FTDI"
                is Cp21xxSerialDriver -> "CP210x"
                is Ch34xSerialDriver -> "CH340/CH341"
                is ProlificSerialDriver -> "Prolific PL2303"
                is CdcAcmSerialDriver -> "CDC-ACM"
                else -> "Unknown"
            }
            
            AppLog.i(TAG, "===== USB Chipset Detected =====")
            AppLog.i(TAG, "  Supported: $supportedChipset")
            AppLog.i(TAG, "  Driver: ${driver.javaClass.simpleName}")
            AppLog.i(TAG, "  Ports: ${driver.ports.size}")
            AppLog.i(TAG, "  Device: ${usbDevice?.productName ?: "Unknown"}")
            AppLog.i(TAG, "  Manufacturer: ${usbDevice?.manufacturerName ?: "Unknown"}")
            AppLog.i(TAG, "  VID:PID: ${String.format("0x%04X", usbDevice?.vendorId ?: 0)}:${String.format("0x%04X", usbDevice?.productId ?: 0)}")
            AppLog.i(TAG, "  Serial: ${usbDevice?.serialNumber ?: "N/A"}")
            AppLog.i(TAG, "===============================")
            
            // Step 4: Check and request USB permission
            AppLog.d(TAG, "[4/8] Checking USB permissions...")
            val hasPermission = permissionHelper.hasPermission(usbDevice!!)
            
            if (!hasPermission) {
                AppLog.w(TAG, "Permission not granted, requesting...")
                val granted = permissionHelper.requestPermission(usbDevice!!)
                
                if (!granted) {
                    AppLog.e(TAG, "USB permission denied by user")
                    return false
                }
                
                AppLog.i(TAG, "Permission granted by user")
            } else {
                AppLog.d(TAG, "Permission already granted")
            }
            
            // Step 5: Open USB device connection
            AppLog.d(TAG, "[5/8] Opening USB device...")
            connection = usbManager?.openDevice(usbDevice)
            if (connection == null) {
                AppLog.e(TAG, "Failed to open USB device")
                return false
            }
            AppLog.d(TAG, "USB device opened (FD: ${connection?.fileDescriptor})")
            
            // Step 6: Get serial port
            AppLog.d(TAG, "[6/8] Getting serial port...")
            AppLog.d(TAG, "Available ports: ${driver.ports.size}")
            serialPort = driver.ports.firstOrNull()
            if (serialPort == null) {
                AppLog.e(TAG, "No serial ports available")
                return false
            }
            
            // Step 7: Open serial port
            AppLog.d(TAG, "[7/8] Opening serial port...")
            serialPort?.open(connection)
            
            // VMC Warmup delay - critical for device initialization
            AppLog.d(TAG, "Waiting ${VMC_WARMUP_DELAY_MS}ms for VMC warmup...")
            delay(VMC_WARMUP_DELAY_MS)
            
            // Clear any startup messages from VMC
            serialPort?.purgeHwBuffers(true, true)
            AppLog.d(TAG, "Buffers cleared, VMC ready")
            
            // Step 8: Configure serial port
            AppLog.d(TAG, "[8/8] Configuring serial parameters...")
            val stopBits = when (config.stopBits) {
                1 -> UsbSerialPort.STOPBITS_1
                2 -> UsbSerialPort.STOPBITS_2
                else -> UsbSerialPort.STOPBITS_1
            }
            val parity = when (config.parity) {
                SerialConfig.Parity.NONE -> UsbSerialPort.PARITY_NONE
                SerialConfig.Parity.ODD -> UsbSerialPort.PARITY_ODD
                SerialConfig.Parity.EVEN -> UsbSerialPort.PARITY_EVEN
            }
            
            serialPort?.setParameters(
                config.baudRate,
                config.dataBits,
                stopBits,
                parity
            )
            
            // Set flow control
            when (config.flowControl) {
                SerialConfig.FlowControl.HARDWARE -> {
                    AppLog.d(TAG, "Flow control: Hardware (RTS/CTS)")
                    serialPort?.setRTS(true)
                    serialPort?.setDTR(true)
                }
                SerialConfig.FlowControl.SOFTWARE -> {
                    AppLog.d(TAG, "Flow control: Software (XON/XOFF)")
                    // XON/XOFF handled at protocol level
                }
                SerialConfig.FlowControl.NONE -> {
                    AppLog.d(TAG, "Flow control: None (DTR/RTS left at default)")
                    // Don't modify DTR/RTS - VMC spec says "Flow control: none"
                    // Let signals stay at their default values
                }
            }
            
            isConnected = true
            AppLog.i(TAG, "===== USB Serial Connected =====")
            AppLog.i(TAG, "Device: ${usbDevice?.productName}, ${config.baudRate} baud")
            
            return true
            
        } catch (e: Exception) {
            AppLog.e(TAG, "USB Serial connection failed: ${e.message}", e)
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
            
            AppLog.i(TAG, "USB Serial disconnected")
            
        } catch (e: Exception) {
            AppLog.e(TAG, "Error disconnecting USB", e)
        }
    }
    
    override fun isConnected(): Boolean {
        return isConnected && serialPort != null
    }
    
    override suspend fun sendData(data: ByteArray): Boolean {
        if (!isConnected()) {
            AppLog.e(TAG, "Cannot send - not connected")
            return false
        }
        
        return try {
            AppLog.d(TAG, ">>> Sending ${data.size} bytes")
            AppLog.d(TAG, "HEX: ${data.joinToString(" ") { "0x%02X".format(it) }}")
            
            val bytesWritten = serialPort?.write(data, WRITE_TIMEOUT_MS) ?: 0
            
            if (bytesWritten == data.size) {
                AppLog.d(TAG, "Sent ${data.size} bytes successfully")
                true
            } else {
                AppLog.e(TAG, "Write incomplete: ${bytesWritten}/${data.size} bytes")
                false
            }
            
        } catch (e: Exception) {
            AppLog.e(TAG, "Error sending data", e)
            false
        }
    }
    
    override suspend fun readData(timeoutMs: Long): ByteArray? {
        if (!isConnected()) {
            AppLog.e(TAG, "Cannot read - not connected")
            return null
        }
        
        return try {
            AppLog.d(TAG, "<<< Waiting for response (timeout: ${timeoutMs}ms)")
            val startTime = System.currentTimeMillis()
            var bytesRead = 0
            var attemptCount = 0
            
            // Try to read data within timeout
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                attemptCount++
                bytesRead = serialPort?.read(readBuffer, READ_TIMEOUT_MS.toInt()) ?: 0
                
                if (bytesRead > 0) {
                    val elapsed = System.currentTimeMillis() - startTime
                    val receivedData = readBuffer.copyOfRange(0, bytesRead)
                    
                    AppLog.d(TAG, "Received ${bytesRead} bytes after ${elapsed}ms")
                    AppLog.d(TAG, "HEX: ${receivedData.joinToString(" ") { "0x%02X".format(it) }}")
                    
                    return receivedData
                }
                
                // Log progress every second
                if (attemptCount % 100 == 0) {
                    val elapsed = System.currentTimeMillis() - startTime
                    AppLog.d(TAG, "Still waiting... (${elapsed}ms, ${attemptCount} attempts)")
                }
                
                delay(10)
            }
            
            val totalTime = System.currentTimeMillis() - startTime
            AppLog.w(TAG, "Read timeout after ${totalTime}ms (${attemptCount} attempts)")
            
            null
            
        } catch (e: Exception) {
            AppLog.e(TAG, "Error reading data", e)
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
                AppLog.e(TAG, "Error in data flow", e)
                break
            }
        }
    }
    
    override suspend fun clearBuffers() {
        try {
            serialPort?.purgeHwBuffers(true, true)
            AppLog.d(TAG, "Buffers cleared")
        } catch (e: Exception) {
            AppLog.e(TAG, "Error clearing buffers", e)
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
