package com.waterfountainmachine.app.hardware.sdk

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Represents a VMC communication protocol frame
 * Frame format: [ADDR][FRAME_NUMBER][HEADER][CMD][DATA_LENGTH][DATA][CHK]
 */
data class ProtocolFrame(
    val address: Byte = FIXED_ADDRESS,
    val frameNumber: Byte = FIXED_FRAME_NUMBER,
    val header: Byte,
    val command: Byte,
    val dataLength: Byte,
    val data: ByteArray,
    val checksum: Byte
) {
    companion object {
        const val FIXED_ADDRESS: Byte = 0xFF.toByte()
        const val FIXED_FRAME_NUMBER: Byte = 0x00
        const val APP_HEADER: Byte = 0x55
        const val VMC_HEADER: Byte = 0xAA.toByte()
        
        // Minimum frame size: ADDR + FRAME_NUMBER + HEADER + CMD + DATA_LENGTH + CHK
        const val MIN_FRAME_SIZE = 6
        const val MAX_DATA_SIZE = 255
    }

    /**
     * Convert frame to byte array for transmission
     */
    fun toByteArray(): ByteArray {
        val frameSize = MIN_FRAME_SIZE + data.size
        val buffer = ByteBuffer.allocate(frameSize).order(ByteOrder.LITTLE_ENDIAN)
        
        buffer.put(address)
        buffer.put(frameNumber)
        buffer.put(header)
        buffer.put(command)
        buffer.put(dataLength)
        buffer.put(data)
        buffer.put(checksum)
        
        return buffer.array()
    }

    /**
     * Calculate checksum for this frame
     * Per VMC spec: Accumulation checksum of all bytes except ADDR, FRAME_NUMBER, and CHK
     * Result is lower 8-bit data
     */
    fun calculateChecksum(): Byte {
        var sum = 0
        sum += header.toInt() and 0xFF
        sum += command.toInt() and 0xFF
        sum += dataLength.toInt() and 0xFF
        data.forEach { sum += it.toInt() and 0xFF }
        return (sum and 0xFF).toByte()
    }
    
    /**
     * Verify checksum matches calculated value
     */
    fun isChecksumValid(): Boolean {
        return checksum == calculateChecksum()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ProtocolFrame

        if (address != other.address) return false
        if (frameNumber != other.frameNumber) return false
        if (header != other.header) return false
        if (command != other.command) return false
        if (dataLength != other.dataLength) return false
        if (!data.contentEquals(other.data)) return false
        if (checksum != other.checksum) return false

        return true
    }

    override fun hashCode(): Int {
        var result = address.toInt()
        result = 31 * result + frameNumber.toInt()
        result = 31 * result + header.toInt()
        result = 31 * result + command.toInt()
        result = 31 * result + dataLength.toInt()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + checksum.toInt()
        return result
    }
}
