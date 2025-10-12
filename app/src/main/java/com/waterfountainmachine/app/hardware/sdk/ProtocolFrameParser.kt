package com.waterfountainmachine.app.hardware.sdk

import com.waterfountainmachine.app.utils.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parser for protocol frames from byte arrays
 */
object ProtocolFrameParser {

    private const val TAG = "ProtocolFrameParser"

    /**
     * Parse byte array into ProtocolFrame with validation
     * @param bytes Raw bytes received from VMC
     * @return Parsed frame or null if invalid
     */
    fun parse(bytes: ByteArray): ProtocolFrame? {
        if (bytes.size < ProtocolFrame.MIN_FRAME_SIZE) {
            AppLog.w(TAG, "Frame too short: ${bytes.size} bytes (min: ${ProtocolFrame.MIN_FRAME_SIZE})")
            return null
        }

        try {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            val address = buffer.get()
            val frameNumber = buffer.get()
            val header = buffer.get()
            val command = buffer.get()
            val dataLength = buffer.get()

            // Validate address (should be 0xFF)
            if (address != ProtocolFrame.FIXED_ADDRESS) {
                AppLog.w(TAG, "Invalid address: 0x${String.format("%02X", address)} (expected: 0xFF)")
                return null
            }

            // Validate frame number (should be 0x00)
            if (frameNumber != ProtocolFrame.FIXED_FRAME_NUMBER) {
                AppLog.w(TAG, "Invalid frame number: 0x${String.format("%02X", frameNumber)} (expected: 0x00)")
                return null
            }

            // Validate header (should be 0xAA for VMC responses)
            if (header != ProtocolFrame.VMC_HEADER && header != ProtocolFrame.APP_HEADER) {
                AppLog.w(TAG, "Invalid header: 0x${String.format("%02X", header)} (expected: 0x55 or 0xAA)")
                return null
            }

            val dataLengthInt = dataLength.toInt() and 0xFF
            if (buffer.remaining() < dataLengthInt + 1) { // +1 for checksum
                AppLog.w(TAG, "Incomplete frame: data length=$dataLengthInt, remaining bytes=${buffer.remaining()}")
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

            // Validate checksum
            if (!frame.isChecksumValid()) {
                val calculated = frame.calculateChecksum()
                AppLog.w(TAG, "Checksum mismatch: received=0x${String.format("%02X", checksum)}, calculated=0x${String.format("%02X", calculated)}")
                return null
            }

            AppLog.d(TAG, "Frame validated: CMD=0x${String.format("%02X", command)}, LEN=$dataLengthInt bytes")
            return frame

        } catch (e: Exception) {
            AppLog.e(TAG, "Error parsing frame", e)
            return null
        }
    }
}
