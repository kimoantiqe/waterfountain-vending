package com.waterfountainmachine.app.hardware.sdk

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parser for protocol frames from byte arrays
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

        try {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            val address = buffer.get()
            val frameNumber = buffer.get()
            val header = buffer.get()
            val command = buffer.get()
            val dataLength = buffer.get()

            val dataLengthInt = dataLength.toInt() and 0xFF
            if (buffer.remaining() < dataLengthInt + 1) { // +1 for checksum
                return null
            }

            val data = ByteArray(dataLengthInt)
            if (dataLengthInt > 0) {
                buffer.get(data)
            }

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

        } catch (e: Exception) {
            return null
        }
    }
}
