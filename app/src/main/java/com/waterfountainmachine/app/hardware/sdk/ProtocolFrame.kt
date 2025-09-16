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
     * Checksum = sum of all bytes except ADDR, FRAME_NUMBER, and CHK (lower 8 bits)
     */
    fun calculateChecksum(): Byte {
        var sum = 0
        sum += header.toInt() and 0xFF
        sum += command.toInt() and 0xFF
        sum += dataLength.toInt() and 0xFF
        
        for (byte in data) {
            sum += byte.toInt() and 0xFF
        }
        
        return (sum and 0xFF).toByte()
    }

    /**
     * Validate frame integrity
     */
    fun isValid(): Boolean {
        return address == FIXED_ADDRESS &&
               frameNumber == FIXED_FRAME_NUMBER &&
               dataLength.toInt() and 0xFF == data.size &&
               checksum == calculateChecksum()
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

/**
 * Builder for creating protocol frames
 */
class ProtocolFrameBuilder {
    private var header: Byte = ProtocolFrame.APP_HEADER
    private var command: Byte = 0
    private var data: ByteArray = byteArrayOf()

    fun appHeader() = apply { header = ProtocolFrame.APP_HEADER }
    fun vmcHeader() = apply { header = ProtocolFrame.VMC_HEADER }
    fun command(cmd: Byte) = apply { command = cmd }
    fun data(data: ByteArray) = apply { this.data = data }
    fun dataBytes(vararg bytes: Byte) = apply { this.data = bytes }

    fun build(): ProtocolFrame {
        val dataLength = (data.size and 0xFF).toByte()
        val frame = ProtocolFrame(
            header = header,
            command = command,
            dataLength = dataLength,
            data = data,
            checksum = 0 // Temporary
        )
        
        return frame.copy(checksum = frame.calculateChecksum())
    }
}

/**
 * Parser for incoming protocol frames
 */
object ProtocolFrameParser {
    
    /**
     * Parse byte array into ProtocolFrame
     * @param bytes Raw bytes received from VMC
     * @return Parsed frame or null if invalid
     */
    fun parse(bytes: ByteArray): ProtocolFrame? {
        if (bytes.size < ProtocolFrame.MIN_FRAME_SIZE) {
            return null
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        
        val address = buffer.get()
        val frameNumber = buffer.get()
        val header = buffer.get()
        val command = buffer.get()
        val dataLength = buffer.get()
        
        val dataSize = dataLength.toInt() and 0xFF
        if (buffer.remaining() < dataSize + 1) { // +1 for checksum
            return null
        }
        
        val data = ByteArray(dataSize)
        buffer.get(data)
        val checksum = buffer.get()
        
        val frame = ProtocolFrame(
            address = address,
            frameNumber = frameNumber,
            header = header,
            command = command,
            dataLength = dataLength,
            data = data,
            checksum = checksum
        )
        
        return if (frame.isValid()) frame else null
    }
}
