package com.waterfountainmachine.app.hardware.sdk

import kotlinx.coroutines.flow.Flow

/**
 * Interface for serial communication with the VMC
 * Abstraction layer for UART/USB serial communication
 */
interface SerialCommunicator {
    
    /**
     * Initialize and open serial connection
     * @param config Serial configuration parameters
     * @return true if connection successful
     */
    suspend fun connect(config: SerialConfig): Boolean
    
    /**
     * Close serial connection
     */
    suspend fun disconnect()
    
    /**
     * Check if connection is active
     */
    fun isConnected(): Boolean
    
    /**
     * Send data to VMC
     * @param data Byte array to send
     * @return true if send successful
     */
    suspend fun sendData(data: ByteArray): Boolean
    
    /**
     * Read data from VMC
     * @param timeoutMs Timeout in milliseconds
     * @return Received bytes or null if timeout/error
     */
    suspend fun readData(timeoutMs: Long = 5000): ByteArray?
    
    /**
     * Flow of incoming data (for continuous monitoring)
     */
    fun getDataFlow(): Flow<ByteArray>
    
    /**
     * Clear input/output buffers
     */
    suspend fun clearBuffers()
}

/**
 * Serial configuration parameters
 */
data class SerialConfig(
    val baudRate: Int = 9600,
    val dataBits: Int = 8,
    val stopBits: Int = 1,
    val parity: Parity = Parity.NONE,
    val flowControl: FlowControl = FlowControl.NONE,
    val devicePath: String? = null // For USB devices
) {
    enum class Parity { NONE, ODD, EVEN }
    enum class FlowControl { NONE, HARDWARE, SOFTWARE }
}

// MockSerialCommunicator has been moved to test directory to avoid including test code in production

// USB Serial implementation will be added when hardware integration is required
// For now, the SDK uses MockSerialCommunicator for testing and simulation
