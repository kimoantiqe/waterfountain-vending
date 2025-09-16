package com.waterfountainmachine.app.hardware.sdk

/**
 * VMC Protocol Commands as defined in the specification
 */
object VmcCommands {
    // 1. Device Information
    const val GET_DEVICE_ID: Byte = 0x31
    
    // 2. Control Commands
    const val DELIVERY_COMMAND: Byte = 0x41
    const val REMOVE_FAULT: Byte = 0xA2.toByte()
    const val PAYMENT_INSTRUCTION: Byte = 0x11
    const val COIN_CHANGE: Byte = 0xB1.toByte()
    const val CASHLESS_CANCEL: Byte = 0xB2.toByte()
    const val DEBIT_INSTRUCTION: Byte = 0xB3.toByte()
    const val AGE_RECOGNITION: Byte = 0x12
    
    // 3. Status Query Commands
    const val QUERY_STATUS: Byte = 0xE1.toByte()
    const val QUERY_BALANCE: Byte = 0xE1.toByte() // Same as status but different data
    const val QUERY_COIN_CHANGE_STATUS: Byte = 0x07
    const val QUERY_AGE_VERIFICATION: Byte = 0x06
}

/**
 * VMC Error Codes
 */
object VmcErrorCodes {
    const val SUCCESS: Byte = 0x01
    const val MOTOR_FAILURE: Byte = 0x02
    const val OPTICAL_EYE_FAILURE: Byte = 0x03
}

/**
 * Payment Methods
 */
object PaymentMethods {
    const val CANCEL_PAYMENT: Byte = 0x00
    const val OFFLINE_COIN: Byte = 0x01
    const val OFFLINE_CASHLESS: Byte = 0x02
    const val OFFLINE_BILL_ACCEPTOR: Byte = 0x03
}

/**
 * Command request data classes
 */
data class GetDeviceIdRequest(
    val placeholder: Byte = 0xAD.toByte() // As specified in protocol
)

data class DeliveryRequest(
    val cargoLane: Byte,    // Slot number (1-255)
    val quantity: Byte      // Delivery quantity (typically 1)
)

data class PaymentRequest(
    val amount: Int,        // Payment amount * 100 (4 bytes)
    val paymentMethod: Byte, // Payment method (see PaymentMethods)
    val slot: Byte          // Slot number for delivery
)

data class DebitRequest(
    val amount: Int         // Debit amount * 100 (4 bytes)
)

data class AgeRecognitionRequest(
    val requiredAge: Byte   // Age requirement (1-99)
)

data class StatusQueryRequest(
    val slot: Byte,         // Slot to query
    val quantity: Byte      // Expected delivery quantity
)

data class BalanceQueryRequest(
    val placeholder: Int = 0 // 4 bytes of 0x00
)

data class CoinChangeStatusRequest(
    val placeholder: Byte = 0x01 // Fixed value as per protocol
)

data class AgeVerificationStatusRequest(
    val placeholder: Byte = 0x01 // Fixed value as per protocol
)

/**
 * Command response data classes
 */
sealed class VmcResponse {
    data class DeviceIdResponse(
        val deviceId: String    // 15-byte string
    ) : VmcResponse()
    
    data class DeliveryResponse(
        val cargoLane: Byte,
        val quantity: Byte
    ) : VmcResponse()
    
    data class StatusResponse(
        val success: Boolean,
        val errorCode: Byte? = null,
        val amount: Int? = null  // For payment completed responses
    ) : VmcResponse()
    
    data class BalanceResponse(
        val balance: Int        // 4-byte integer, actual balance * 100
    ) : VmcResponse()
    
    data class PaymentResponse(
        val success: Boolean
    ) : VmcResponse()
    
    data class CoinChangeStatusResponse(
        val canRefund: Boolean  // true if coins can be refunded, false if insufficient
    ) : VmcResponse()
    
    data class AgeVerificationResponse(
        val verified: Boolean   // true if age verification successful
    ) : VmcResponse()
    
    data class SimpleResponse(
        val success: Boolean
    ) : VmcResponse()
    
    data class ErrorResponse(
        val errorCode: Byte,
        val message: String
    ) : VmcResponse()
}

/**
 * Command builders for creating protocol frames
 */
object VmcCommandBuilder {
    
    fun getDeviceId(): ProtocolFrame {
        return ProtocolFrameBuilder()
            .appHeader()
            .command(VmcCommands.GET_DEVICE_ID)
            .dataBytes(GetDeviceIdRequest().placeholder)
            .build()
    }
    
    fun deliveryCommand(cargoLane: Byte, quantity: Byte = 1): ProtocolFrame {
        return ProtocolFrameBuilder()
            .appHeader()
            .command(VmcCommands.DELIVERY_COMMAND)
            .dataBytes(cargoLane, quantity)
            .build()
    }
    
    fun removeFault(): ProtocolFrame {
        return ProtocolFrameBuilder()
            .appHeader()
            .command(VmcCommands.REMOVE_FAULT)
            .dataBytes(0xFF.toByte())
            .build()
    }
    
    fun queryStatus(slot: Byte, quantity: Byte = 1): ProtocolFrame {
        return ProtocolFrameBuilder()
            .appHeader()
            .command(VmcCommands.QUERY_STATUS)
            .dataBytes(slot, quantity)
            .build()
    }
    
    fun queryBalance(): ProtocolFrame {
        // Query balance uses 4 bytes of 0x00
        return ProtocolFrameBuilder()
            .appHeader()
            .command(VmcCommands.QUERY_BALANCE)
            .dataBytes(0x00, 0x00, 0x00, 0x00)
            .build()
    }
    
    fun paymentInstruction(amount: Int, paymentMethod: Byte, slot: Byte): ProtocolFrame {
        val amountBytes = intToLittleEndianBytes(amount)
        return ProtocolFrameBuilder()
            .appHeader()
            .command(VmcCommands.PAYMENT_INSTRUCTION)
            .data(byteArrayOf(
                amountBytes[0], amountBytes[1], amountBytes[2], amountBytes[3],
                paymentMethod,
                slot
            ))
            .build()
    }
    
    fun coinChange(): ProtocolFrame {
        return ProtocolFrameBuilder()
            .appHeader()
            .command(VmcCommands.COIN_CHANGE)
            .dataBytes(0xFF.toByte())
            .build()
    }
    
    fun cashlessCancel(): ProtocolFrame {
        return ProtocolFrameBuilder()
            .appHeader()
            .command(VmcCommands.CASHLESS_CANCEL)
            .dataBytes(0xFF.toByte())
            .build()
    }
    
    fun debitInstruction(amount: Int): ProtocolFrame {
        val amountBytes = intToLittleEndianBytes(amount)
        return ProtocolFrameBuilder()
            .appHeader()
            .command(VmcCommands.DEBIT_INSTRUCTION)
            .data(amountBytes)
            .build()
    }
    
    fun ageRecognition(requiredAge: Byte): ProtocolFrame {
        return ProtocolFrameBuilder()
            .appHeader()
            .command(VmcCommands.AGE_RECOGNITION)
            .dataBytes(requiredAge)
            .build()
    }
    
    fun queryCoinChangeStatus(): ProtocolFrame {
        return ProtocolFrameBuilder()
            .appHeader()
            .command(VmcCommands.QUERY_COIN_CHANGE_STATUS)
            .dataBytes(0x01)
            .build()
    }
    
    fun queryAgeVerificationStatus(): ProtocolFrame {
        return ProtocolFrameBuilder()
            .appHeader()
            .command(VmcCommands.QUERY_AGE_VERIFICATION)
            .dataBytes(0x01)
            .build()
    }
    
    private fun intToLittleEndianBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }
}

/**
 * Response parser for VMC responses
 */
object VmcResponseParser {
    
    fun parseResponse(frame: ProtocolFrame): VmcResponse {
        return when (frame.command) {
            VmcCommands.GET_DEVICE_ID -> {
                if (frame.data.size == 15) {
                    VmcResponse.DeviceIdResponse(String(frame.data, Charsets.UTF_8))
                } else {
                    VmcResponse.ErrorResponse(0xFF.toByte(), "Invalid device ID response length")
                }
            }
            
            VmcCommands.DELIVERY_COMMAND -> {
                if (frame.data.size == 2) {
                    VmcResponse.DeliveryResponse(frame.data[0], frame.data[1])
                } else {
                    VmcResponse.ErrorResponse(0xFF.toByte(), "Invalid delivery response length")
                }
            }
            
            VmcCommands.REMOVE_FAULT -> {
                VmcResponse.SimpleResponse(frame.data.isNotEmpty() && frame.data[0] == VmcErrorCodes.SUCCESS)
            }
            
            VmcCommands.QUERY_STATUS -> {
                when (frame.data.size) {
                    1 -> {
                        val status = frame.data[0]
                        if (status == VmcErrorCodes.SUCCESS) {
                            VmcResponse.StatusResponse(success = true)
                        } else {
                            VmcResponse.StatusResponse(success = false, errorCode = status)
                        }
                    }
                    4 -> {
                        // Payment completed response - 4 bytes amount
                        val amount = bytesToInt(frame.data)
                        VmcResponse.StatusResponse(success = true, amount = amount)
                    }
                    else -> {
                        VmcResponse.ErrorResponse(0xFF.toByte(), "Invalid status response length")
                    }
                }
            }
            
            VmcCommands.QUERY_BALANCE -> {
                if (frame.data.size == 4) {
                    val balance = bytesToInt(frame.data)
                    VmcResponse.BalanceResponse(balance)
                } else {
                    VmcResponse.ErrorResponse(0xFF.toByte(), "Invalid balance response length")
                }
            }
            
            VmcCommands.PAYMENT_INSTRUCTION -> {
                VmcResponse.PaymentResponse(frame.data.isNotEmpty() && frame.data[0] == VmcErrorCodes.SUCCESS)
            }
            
            VmcCommands.COIN_CHANGE -> {
                VmcResponse.SimpleResponse(frame.data.isNotEmpty() && frame.data[0] == VmcErrorCodes.SUCCESS)
            }
            
            VmcCommands.CASHLESS_CANCEL -> {
                VmcResponse.SimpleResponse(frame.data.isNotEmpty() && frame.data[0] == VmcErrorCodes.SUCCESS)
            }
            
            VmcCommands.DEBIT_INSTRUCTION -> {
                VmcResponse.SimpleResponse(frame.data.isNotEmpty() && frame.data[0] == VmcErrorCodes.SUCCESS)
            }
            
            VmcCommands.AGE_RECOGNITION -> {
                VmcResponse.SimpleResponse(frame.data.isNotEmpty() && frame.data[0] == VmcErrorCodes.SUCCESS)
            }
            
            VmcCommands.QUERY_COIN_CHANGE_STATUS -> {
                if (frame.data.size == 1) {
                    VmcResponse.CoinChangeStatusResponse(frame.data[0] == 0x00.toByte())
                } else {
                    VmcResponse.ErrorResponse(0xFF.toByte(), "Invalid coin change status response length")
                }
            }
            
            VmcCommands.QUERY_AGE_VERIFICATION -> {
                if (frame.data.size == 1) {
                    VmcResponse.AgeVerificationResponse(frame.data[0] == 0x01.toByte())
                } else {
                    VmcResponse.ErrorResponse(0xFF.toByte(), "Invalid age verification response length")
                }
            }
            
            else -> {
                VmcResponse.ErrorResponse(0xFF.toByte(), "Unknown command response: ${frame.command}")
            }
        }
    }
    
    private fun bytesToInt(bytes: ByteArray): Int {
        require(bytes.size == 4) { "Expected 4 bytes for integer conversion" }
        
        // Little-endian conversion as specified in protocol
        return (bytes[0].toInt() and 0xFF) or
               ((bytes[1].toInt() and 0xFF) shl 8) or
               ((bytes[2].toInt() and 0xFF) shl 16) or
               ((bytes[3].toInt() and 0xFF) shl 24)
    }
}
