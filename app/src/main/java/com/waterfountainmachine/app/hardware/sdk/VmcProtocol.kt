package com.waterfountainmachine.app.hardware.sdk

/**
 * VMC Protocol Commands - Based on Control Board Communication API
 * Only includes commands needed for water fountain operation
 */
object VmcCommands {
    // Core commands for water fountain
    const val GET_DEVICE_ID: Byte = 0x31        // Get device ID (15 bytes)
    const val DELIVERY_COMMAND: Byte = 0x41     // Delivery command for water dispensing
    const val REMOVE_FAULT: Byte = 0xA2.toByte() // Remove fault command
    const val QUERY_STATUS: Byte = 0xE1.toByte() // Query VMC status
}

/**
 * VMC Error Codes - Based on API document
 */
object VmcErrorCodes {
    const val SUCCESS: Byte = 0x01              // Delivery successful
    const val MOTOR_FAILURE: Byte = 0x02        // Freightway motor failure
    const val OPTICAL_EYE_FAILURE: Byte = 0x03  // Device optical eye failure
}

/**
 * VMC Response Data Classes - Simplified for water fountain use
 */
sealed class VmcResponse {
    
    /**
     * Device ID response containing 15-byte device identifier
     */
    data class DeviceIdResponse(
        val deviceId: String
    ) : VmcResponse()

    /**
     * Delivery command response - echoes back slot and quantity
     */
    data class DeliveryResponse(
        val cargoLane: Byte,
        val quantity: Byte
    ) : VmcResponse()

    /**
     * Status query response for delivery operations
     */
    data class StatusResponse(
        val success: Boolean,
        val errorCode: Byte? = null
    ) : VmcResponse()

    /**
     * Generic success/failure response
     */
    data class SuccessResponse(
        val success: Boolean
    ) : VmcResponse()

    /**
     * Error response for failed operations
     */
    data class ErrorResponse(
        val message: String,
        val errorCode: Byte? = null
    ) : VmcResponse()
}

/**
 * Command builders for VMC protocol frames
 */
object VmcCommandBuilder {
    
    /**
     * Build get device ID command
     * APP: 0xFF 0x00 0x55 0x31 0x01 0xAD CHK
     */
    fun getDeviceId(): ProtocolFrame {
        return ProtocolFrameBuilder()
            .appHeader()
            .command(VmcCommands.GET_DEVICE_ID)
            .dataBytes(0xAD.toByte())
            .build()
    }

    /**
     * Build delivery command for water dispensing
     * APP: 0xFF 0x00 0x55 0x41 0x02 slot(1Byte) quantity(1Byte) CHK
     */
    fun deliveryCommand(slot: Byte, quantity: Byte): ProtocolFrame {
        return ProtocolFrameBuilder()
            .appHeader()
            .command(VmcCommands.DELIVERY_COMMAND)
            .dataBytes(slot, quantity)
            .build()
    }

    /**
     * Build delivery command for water dispensing with default quantity of 1
     * APP: 0xFF 0x00 0x55 0x41 0x02 slot(1Byte) quantity(1Byte) CHK
     */
    fun deliveryCommand(slot: Byte): ProtocolFrame {
        return deliveryCommand(slot, 1)
    }

    /**
     * Build remove fault command
     * APP: 0xFF 0x00 0x55 0xA2 0x01 0xFF CHK
     */
    fun removeFault(): ProtocolFrame {
        return ProtocolFrameBuilder()
            .appHeader()
            .command(VmcCommands.REMOVE_FAULT)
            .dataBytes(0xFF.toByte())
            .build()
    }

    /**
     * Build query status command
     * APP: 0xFF 0x00 0x55 0xE1 0x02 slot(1Byte) quantity(1Byte) CHK
     */
    fun queryStatus(slot: Byte, quantity: Byte): ProtocolFrame {
        return ProtocolFrameBuilder()
            .appHeader()
            .command(VmcCommands.QUERY_STATUS)
            .dataBytes(slot, quantity)
            .build()
    }
}

/**
 * Response parser for VMC protocol frames
 */
object VmcResponseParser {
    
    fun parseResponse(frame: ProtocolFrame): VmcResponse {
        return when (frame.command) {
            VmcCommands.GET_DEVICE_ID -> {
                // VMC: 0xFF 0x00 0xAA 0x31 0x0F ID(15Bytes) CHK
                val deviceId = String(frame.data, Charsets.UTF_8).trimEnd('\u0000')
                VmcResponse.DeviceIdResponse(deviceId)
            }
            
            VmcCommands.DELIVERY_COMMAND -> {
                // VMC: 0xFF 0x00 0xAA 0x41 0x02 slot(1Byte) quantity(1Byte) CHK
                if (frame.data.size >= 2) {
                    VmcResponse.DeliveryResponse(frame.data[0], frame.data[1])
                } else {
                    VmcResponse.ErrorResponse("Invalid delivery response data")
                }
            }
            
            VmcCommands.REMOVE_FAULT -> {
                // VMC: 0xFF 0x00 0xAA 0xA2 0x01 0x01 CHK
                val success = frame.data.isNotEmpty() && frame.data[0] == 0x01.toByte()
                VmcResponse.SuccessResponse(success)
            }
            
            VmcCommands.QUERY_STATUS -> {
                // Multiple response types based on data length
                when (frame.dataLength.toInt() and 0xFF) {
                    1 -> {
                        // Single byte response - success (0x01) or error code
                        val statusByte = frame.data[0]
                        if (statusByte == VmcErrorCodes.SUCCESS) {
                            VmcResponse.StatusResponse(success = true)
                        } else {
                            VmcResponse.StatusResponse(success = false, errorCode = statusByte)
                        }
                    }
                    4 -> {
                        // 4-byte response would be payment amount - not used in water fountain
                        VmcResponse.ErrorResponse("Payment response not supported in water fountain mode")
                    }
                    else -> {
                        VmcResponse.ErrorResponse("Unknown status response format")
                    }
                }
            }
            
            else -> {
                VmcResponse.ErrorResponse("Unknown command response: 0x${frame.command.toString(16)}")
            }
        }
    }
}

/**
 * VMC Exception Types
 */
sealed class VmcException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ConnectionException(message: String) : VmcException(message)
    class TimeoutException(message: String) : VmcException(message)
    class ProtocolException(message: String) : VmcException(message)
    class HardwareException(message: String, val errorCode: Byte) : VmcException(message)
}
