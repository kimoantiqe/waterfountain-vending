package com.waterfountainmachine.app.hardware.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Mock implementation of SerialCommunicator for testing
 */
class MockSerialCommunicator : SerialCommunicator {

    private var _isConnected = false
    private val sentCommands = mutableListOf<ByteArray>()
    private val responseQueue = ConcurrentLinkedQueue<ByteArray>()

    override suspend fun connect(config: SerialConfig): Boolean {
        _isConnected = true
        return true
    }

    override suspend fun disconnect() {
        _isConnected = false
        sentCommands.clear()
        responseQueue.clear()
    }

    override fun isConnected(): Boolean {
        return _isConnected
    }

    override suspend fun sendData(data: ByteArray): Boolean {
        if (!_isConnected) {
            return false
        }

        sentCommands.add(data.copyOf())
        return true
    }

    override suspend fun readData(timeoutMs: Long): ByteArray? {
        if (!_isConnected) {
            return null
        }
        return responseQueue.poll()
    }

    override fun getDataFlow(): Flow<ByteArray> {
        return flowOf() // Empty flow for testing
    }

    override suspend fun clearBuffers() {
        responseQueue.clear()
    }

    // Test helper methods
    fun queueResponse(response: ByteArray) {
        responseQueue.offer(response.copyOf())
    }

    fun getSentCommands(): List<ByteArray> {
        return sentCommands.map { it.copyOf() }
    }

    fun clearSentCommands() {
        sentCommands.clear()
    }

    // Removed legacy sendCommand method - use sendData() and readData() directly
}
