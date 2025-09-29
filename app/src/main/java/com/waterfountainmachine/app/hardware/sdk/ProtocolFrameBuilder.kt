package com.waterfountainmachine.app.hardware.sdk

/**
 * Builder for creating protocol frames
 */
class ProtocolFrameBuilder {
    private var header: Byte = ProtocolFrame.APP_HEADER
    private var command: Byte = 0x00
    private var data: ByteArray = byteArrayOf()

    fun appHeader(): ProtocolFrameBuilder {
        header = ProtocolFrame.APP_HEADER
        return this
    }

    fun vmcHeader(): ProtocolFrameBuilder {
        header = ProtocolFrame.VMC_HEADER
        return this
    }

    fun command(cmd: Byte): ProtocolFrameBuilder {
        command = cmd
        return this
    }

    fun data(data: ByteArray): ProtocolFrameBuilder {
        this.data = data.copyOf()
        return this
    }

    fun dataBytes(vararg bytes: Byte): ProtocolFrameBuilder {
        this.data = bytes
        return this
    }

    fun build(): ProtocolFrame {
        val dataLength = data.size.toByte()

        // Calculate checksum: header + command + dataLength + data
        var checksum = (header.toInt() and 0xFF) +
                      (command.toInt() and 0xFF) +
                      (dataLength.toInt() and 0xFF)

        for (b in data) {
            checksum += (b.toInt() and 0xFF)
        }

        val finalChecksum = (checksum and 0xFF).toByte()

        return ProtocolFrame(
            address = ProtocolFrame.FIXED_ADDRESS,
            frameNumber = ProtocolFrame.FIXED_FRAME_NUMBER,
            header = header,
            command = command,
            dataLength = dataLength,
            data = data.copyOf(),
            checksum = finalChecksum
        )
    }
}
