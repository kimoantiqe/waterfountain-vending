package com.waterfountainmachine.app.hardware.sdk

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for VMC Protocol commands and responses
 */
class VmcProtocolTest {

    @Test
    fun `VmcCommandBuilder getDeviceId should create correct frame`() {
        val frame = VmcCommandBuilder.getDeviceId()

        assertEquals(ProtocolFrame.APP_HEADER, frame.header)
        assertEquals(VmcCommands.GET_DEVICE_ID, frame.command)
        assertEquals(1, frame.dataLength.toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(0xAD.toByte()), frame.data)
        assertTrue(frame.isValid())
    }

    @Test
    fun `VmcCommandBuilder deliveryCommand should create correct frame`() {
        val cargoLane: Byte = 5
        val quantity: Byte = 2
        val frame = VmcCommandBuilder.deliveryCommand(cargoLane, quantity)

        assertEquals(ProtocolFrame.APP_HEADER, frame.header)
        assertEquals(VmcCommands.DELIVERY_COMMAND, frame.command)
        assertEquals(2, frame.dataLength.toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(cargoLane, quantity), frame.data)
        assertTrue(frame.isValid())
    }

    @Test
    fun `VmcCommandBuilder deliveryCommand with default quantity should use 1`() {
        val cargoLane: Byte = 3
        val frame = VmcCommandBuilder.deliveryCommand(cargoLane)

        assertEquals(2, frame.dataLength.toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(cargoLane, 1), frame.data)
    }

    @Test
    fun `VmcCommandBuilder removeFault should create correct frame`() {
        val frame = VmcCommandBuilder.removeFault()

        assertEquals(ProtocolFrame.APP_HEADER, frame.header)
        assertEquals(VmcCommands.REMOVE_FAULT, frame.command)
        assertEquals(1, frame.dataLength.toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(0xFF.toByte()), frame.data)
        assertTrue(frame.isValid())
    }

    @Test
    fun `VmcCommandBuilder queryStatus should create correct frame`() {
        val slot: Byte = 7
        val quantity: Byte = 1
        val frame = VmcCommandBuilder.queryStatus(slot, quantity)

        assertEquals(ProtocolFrame.APP_HEADER, frame.header)
        assertEquals(VmcCommands.QUERY_STATUS, frame.command)
        assertEquals(2, frame.dataLength.toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(slot, quantity), frame.data)
        assertTrue(frame.isValid())
    }

    @Test
    fun `VmcCommandBuilder queryBalance should create correct frame`() {
        val frame = VmcCommandBuilder.queryBalance()

        assertEquals(ProtocolFrame.APP_HEADER, frame.header)
        assertEquals(VmcCommands.QUERY_BALANCE, frame.command)
        assertEquals(4, frame.dataLength.toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00, 0x00), frame.data)
        assertTrue(frame.isValid())
    }

    @Test
    fun `VmcResponseParser parseDeviceIdResponse should extract device ID`() {
        val deviceId = "WF001234567890\u0000"
        val deviceIdBytes = deviceId.toByteArray(Charsets.UTF_8).take(15).toByteArray()
        
        val frame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.GET_DEVICE_ID)
            .data(deviceIdBytes)
            .build()

        val response = VmcResponseParser.parseResponse(frame)

        assertTrue(response is VmcResponse.DeviceIdResponse)
        val deviceResponse = response as VmcResponse.DeviceIdResponse
        assertEquals("WF001234567890\u0000", deviceResponse.deviceId)
    }

    @Test
    fun `VmcResponseParser parseDeviceIdResponse with invalid length should return error`() {
        val invalidData = byteArrayOf(1, 2, 3) // Only 3 bytes instead of 15
        
        val frame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.GET_DEVICE_ID)
            .data(invalidData)
            .build()

        val response = VmcResponseParser.parseResponse(frame)

        assertTrue(response is VmcResponse.ErrorResponse)
        val errorResponse = response as VmcResponse.ErrorResponse
        assertTrue(errorResponse.message.contains("Invalid device ID response length"))
    }

    @Test
    fun `VmcResponseParser parseDeliveryResponse should extract slot and quantity`() {
        val cargoLane: Byte = 5
        val quantity: Byte = 2
        
        val frame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.DELIVERY_COMMAND)
            .dataBytes(cargoLane, quantity)
            .build()

        val response = VmcResponseParser.parseResponse(frame)

        assertTrue(response is VmcResponse.DeliveryResponse)
        val deliveryResponse = response as VmcResponse.DeliveryResponse
        assertEquals(cargoLane, deliveryResponse.cargoLane)
        assertEquals(quantity, deliveryResponse.quantity)
    }

    @Test
    fun `VmcResponseParser parseStatusResponse success should indicate success`() {
        val frame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.QUERY_STATUS)
            .dataBytes(VmcErrorCodes.SUCCESS)
            .build()

        val response = VmcResponseParser.parseResponse(frame)

        assertTrue(response is VmcResponse.StatusResponse)
        val statusResponse = response as VmcResponse.StatusResponse
        assertTrue(statusResponse.success)
        assertNull(statusResponse.errorCode)
        assertNull(statusResponse.amount)
    }

    @Test
    fun `VmcResponseParser parseStatusResponse with error code should indicate failure`() {
        val frame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.QUERY_STATUS)
            .dataBytes(VmcErrorCodes.MOTOR_FAILURE)
            .build()

        val response = VmcResponseParser.parseResponse(frame)

        assertTrue(response is VmcResponse.StatusResponse)
        val statusResponse = response as VmcResponse.StatusResponse
        assertFalse(statusResponse.success)
        assertEquals(VmcErrorCodes.MOTOR_FAILURE, statusResponse.errorCode)
        assertNull(statusResponse.amount)
    }

    @Test
    fun `VmcResponseParser parseStatusResponse with payment amount should extract amount`() {
        // Payment amount: 1250 cents = $12.50 in little-endian format
        val amount = 1250
        val amountBytes = intToLittleEndianBytes(amount)
        
        val frame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.QUERY_STATUS)
            .data(amountBytes)
            .build()

        val response = VmcResponseParser.parseResponse(frame)

        assertTrue(response is VmcResponse.StatusResponse)
        val statusResponse = response as VmcResponse.StatusResponse
        assertTrue(statusResponse.success)
        assertEquals(amount, statusResponse.amount)
    }

    @Test
    fun `VmcResponseParser parseBalanceResponse should extract balance`() {
        val balance = 5000 // $50.00 in cents
        val balanceBytes = intToLittleEndianBytes(balance)
        
        val frame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.QUERY_BALANCE)
            .data(balanceBytes)
            .build()

        val response = VmcResponseParser.parseResponse(frame)

        assertTrue(response is VmcResponse.BalanceResponse)
        val balanceResponse = response as VmcResponse.BalanceResponse
        assertEquals(balance, balanceResponse.balance)
    }

    @Test
    fun `VmcResponseParser parseSimpleResponse should indicate success`() {
        val frame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.REMOVE_FAULT)
            .dataBytes(VmcErrorCodes.SUCCESS)
            .build()

        val response = VmcResponseParser.parseResponse(frame)

        assertTrue(response is VmcResponse.SimpleResponse)
        val simpleResponse = response as VmcResponse.SimpleResponse
        assertTrue(simpleResponse.success)
    }

    @Test
    fun `VmcResponseParser parseSimpleResponse with failure should indicate failure`() {
        val frame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.REMOVE_FAULT)
            .dataBytes(0x00) // Not success
            .build()

        val response = VmcResponseParser.parseResponse(frame)

        assertTrue(response is VmcResponse.SimpleResponse)
        val simpleResponse = response as VmcResponse.SimpleResponse
        assertFalse(simpleResponse.success)
    }

    @Test
    fun `VmcResponseParser with unknown command should return error`() {
        val frame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(0x99.toByte()) // Unknown command
            .dataBytes(0x01)
            .build()

        val response = VmcResponseParser.parseResponse(frame)

        assertTrue(response is VmcResponse.ErrorResponse)
        val errorResponse = response as VmcResponse.ErrorResponse
        assertTrue(errorResponse.message.contains("Unknown command response"))
    }

    @Test
    fun `little endian byte conversion should work correctly`() {
        // Test the private bytesToInt function through public API
        val testValue = 0x12345678
        val bytes = intToLittleEndianBytes(testValue)
        
        // Verify little-endian format: least significant byte first
        assertEquals(0x78.toByte(), bytes[0])
        assertEquals(0x56.toByte(), bytes[1])
        assertEquals(0x34.toByte(), bytes[2])
        assertEquals(0x12.toByte(), bytes[3])
        
        // Test parsing back through balance response
        val frame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.QUERY_BALANCE)
            .data(bytes)
            .build()

        val response = VmcResponseParser.parseResponse(frame)
        assertTrue(response is VmcResponse.BalanceResponse)
        assertEquals(testValue, (response as VmcResponse.BalanceResponse).balance)
    }

    @Test
    fun `error codes should have correct values`() {
        assertEquals(0x01.toByte(), VmcErrorCodes.SUCCESS)
        assertEquals(0x02.toByte(), VmcErrorCodes.MOTOR_FAILURE)
        assertEquals(0x03.toByte(), VmcErrorCodes.OPTICAL_EYE_FAILURE)
    }

    @Test
    fun `payment methods should have correct values`() {
        assertEquals(0x00.toByte(), PaymentMethods.CANCEL_PAYMENT)
        assertEquals(0x01.toByte(), PaymentMethods.OFFLINE_COIN)
        assertEquals(0x02.toByte(), PaymentMethods.OFFLINE_CASHLESS)
        assertEquals(0x03.toByte(), PaymentMethods.OFFLINE_BILL_ACCEPTOR)
    }

    // ========== COMPLETE API TESTS ==========
    // Tests for all payment and advanced features

    @Test
    fun `paymentInstruction command builder should create correct frame`() {
        val amount = 1250 // $12.50 in cents
        val paymentMethod = PaymentMethods.OFFLINE_CASHLESS
        val slot: Byte = 5
        
        val frame = VmcCommandBuilder.paymentInstruction(amount, paymentMethod, slot)
        
        assertEquals(ProtocolFrame.APP_HEADER, frame.header)
        assertEquals(VmcCommands.PAYMENT_INSTRUCTION, frame.command)
        assertEquals(6, frame.dataLength.toInt() and 0xFF) // 4 bytes amount + 1 byte method + 1 byte slot
        
        // Verify amount is encoded in little-endian
        val expectedData = byteArrayOf(
            (1250 and 0xFF).toByte(),           // Amount LSB
            ((1250 shr 8) and 0xFF).toByte(),   // Amount
            ((1250 shr 16) and 0xFF).toByte(),  // Amount
            ((1250 shr 24) and 0xFF).toByte(),  // Amount MSB
            paymentMethod,                       // Payment method
            slot                                 // Slot
        )
        assertArrayEquals(expectedData, frame.data)
        assertTrue(frame.isValid())
    }

    @Test
    fun `coinChange command builder should create correct frame`() {
        val frame = VmcCommandBuilder.coinChange()
        
        assertEquals(ProtocolFrame.APP_HEADER, frame.header)
        assertEquals(VmcCommands.COIN_CHANGE, frame.command)
        assertEquals(1, frame.dataLength.toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(0xFF.toByte()), frame.data)
        assertTrue(frame.isValid())
    }

    @Test
    fun `cashlessCancel command builder should create correct frame`() {
        val frame = VmcCommandBuilder.cashlessCancel()
        
        assertEquals(ProtocolFrame.APP_HEADER, frame.header)
        assertEquals(VmcCommands.CASHLESS_CANCEL, frame.command)
        assertEquals(1, frame.dataLength.toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(0xFF.toByte()), frame.data)
        assertTrue(frame.isValid())
    }

    @Test
    fun `debitInstruction command builder should create correct frame`() {
        val amount = 500 // $5.00 in cents
        val frame = VmcCommandBuilder.debitInstruction(amount)
        
        assertEquals(ProtocolFrame.APP_HEADER, frame.header)
        assertEquals(VmcCommands.DEBIT_INSTRUCTION, frame.command)
        assertEquals(4, frame.dataLength.toInt() and 0xFF)
        
        // Verify amount is encoded in little-endian
        val expectedData = byteArrayOf(
            (500 and 0xFF).toByte(),
            ((500 shr 8) and 0xFF).toByte(),
            ((500 shr 16) and 0xFF).toByte(),
            ((500 shr 24) and 0xFF).toByte()
        )
        assertArrayEquals(expectedData, frame.data)
        assertTrue(frame.isValid())
    }

    @Test
    fun `ageRecognition command builder should create correct frame`() {
        val requiredAge: Byte = 21
        val frame = VmcCommandBuilder.ageRecognition(requiredAge)
        
        assertEquals(ProtocolFrame.APP_HEADER, frame.header)
        assertEquals(VmcCommands.AGE_RECOGNITION, frame.command)
        assertEquals(1, frame.dataLength.toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(requiredAge), frame.data)
        assertTrue(frame.isValid())
    }

    @Test
    fun `queryCoinChangeStatus command builder should create correct frame`() {
        val frame = VmcCommandBuilder.queryCoinChangeStatus()
        
        assertEquals(ProtocolFrame.APP_HEADER, frame.header)
        assertEquals(VmcCommands.QUERY_COIN_CHANGE_STATUS, frame.command)
        assertEquals(1, frame.dataLength.toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(0x01), frame.data)
        assertTrue(frame.isValid())
    }

    @Test
    fun `queryAgeVerificationStatus command builder should create correct frame`() {
        val frame = VmcCommandBuilder.queryAgeVerificationStatus()
        
        assertEquals(ProtocolFrame.APP_HEADER, frame.header)
        assertEquals(VmcCommands.QUERY_AGE_VERIFICATION, frame.command)
        assertEquals(1, frame.dataLength.toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(0x01), frame.data)
        assertTrue(frame.isValid())
    }

    @Test
    fun `VmcResponseParser parsePaymentResponse should indicate success`() {
        val frame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.PAYMENT_INSTRUCTION)
            .dataBytes(VmcErrorCodes.SUCCESS)
            .build()

        val response = VmcResponseParser.parseResponse(frame)

        assertTrue(response is VmcResponse.PaymentResponse)
        val paymentResponse = response as VmcResponse.PaymentResponse
        assertTrue(paymentResponse.success)
    }

    @Test
    fun `VmcResponseParser parseCoinChangeStatusResponse should parse correctly`() {
        // Test can refund (status 0)
        val canRefundFrame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.QUERY_COIN_CHANGE_STATUS)
            .dataBytes(0x00) // Can refund
            .build()

        val canRefundResponse = VmcResponseParser.parseResponse(canRefundFrame)
        assertTrue(canRefundResponse is VmcResponse.CoinChangeStatusResponse)
        assertTrue((canRefundResponse as VmcResponse.CoinChangeStatusResponse).canRefund)

        // Test cannot refund (status 1)
        val cannotRefundFrame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.QUERY_COIN_CHANGE_STATUS)
            .dataBytes(0x01) // Cannot refund
            .build()

        val cannotRefundResponse = VmcResponseParser.parseResponse(cannotRefundFrame)
        assertTrue(cannotRefundResponse is VmcResponse.CoinChangeStatusResponse)
        assertFalse((cannotRefundResponse as VmcResponse.CoinChangeStatusResponse).canRefund)
    }

    @Test
    fun `VmcResponseParser parseAgeVerificationResponse should parse correctly`() {
        // Test verification failed (status 0)
        val failedFrame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.QUERY_AGE_VERIFICATION)
            .dataBytes(0x00) // Failed
            .build()

        val failedResponse = VmcResponseParser.parseResponse(failedFrame)
        assertTrue(failedResponse is VmcResponse.AgeVerificationResponse)
        assertFalse((failedResponse as VmcResponse.AgeVerificationResponse).verified)

        // Test verification successful (status 1)
        val successFrame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.QUERY_AGE_VERIFICATION)
            .dataBytes(0x01) // Success
            .build()

        val successResponse = VmcResponseParser.parseResponse(successFrame)
        assertTrue(successResponse is VmcResponse.AgeVerificationResponse)
        assertTrue((successResponse as VmcResponse.AgeVerificationResponse).verified)
    }

    @Test
    fun `little endian conversion should work for large amounts`() {
        val largeAmount = 4294967295L.toInt() // Maximum value
        val frame = VmcCommandBuilder.debitInstruction(largeAmount)
        
        // Verify the data is encoded correctly
        val expectedData = byteArrayOf(
            0xFF.toByte(), // LSB
            0xFF.toByte(),
            0xFF.toByte(),
            0xFF.toByte()  // MSB
        )
        assertArrayEquals(expectedData, frame.data)
    }

    @Test
    fun `all command constants should have correct hex values`() {
        assertEquals(0x31.toByte(), VmcCommands.GET_DEVICE_ID)
        assertEquals(0x41.toByte(), VmcCommands.DELIVERY_COMMAND)
        assertEquals(0xA2.toByte(), VmcCommands.REMOVE_FAULT)
        assertEquals(0x11.toByte(), VmcCommands.PAYMENT_INSTRUCTION)
        assertEquals(0xB1.toByte(), VmcCommands.COIN_CHANGE)
        assertEquals(0xB2.toByte(), VmcCommands.CASHLESS_CANCEL)
        assertEquals(0xB3.toByte(), VmcCommands.DEBIT_INSTRUCTION)
        assertEquals(0x12.toByte(), VmcCommands.AGE_RECOGNITION)
        assertEquals(0xE1.toByte(), VmcCommands.QUERY_STATUS)
        assertEquals(0xE1.toByte(), VmcCommands.QUERY_BALANCE)
        assertEquals(0x07.toByte(), VmcCommands.QUERY_COIN_CHANGE_STATUS)
        assertEquals(0x06.toByte(), VmcCommands.QUERY_AGE_VERIFICATION)
    }

    /**
     * Helper function to convert int to little-endian byte array
     */
    private fun intToLittleEndianBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }
}
