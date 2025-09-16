package com.waterfountainmachine.app.hardware.sdk

import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

/**
 * Comprehensive unit tests for VendingMachineSDK
 */
class VendingMachineSDKTest {

    private lateinit var mockSerial: MockSerialCommunicator
    private lateinit var sdk: VendingMachineSDK

    @Before
    fun setUp() {
        mockSerial = MockSerialCommunicator()
        sdk = VendingMachineSDKImpl(
            serialCommunicator = mockSerial,
            commandTimeoutMs = 1000,
            statusPollingIntervalMs = 100,
            maxStatusPollingAttempts = 5
        )
    }

    @Test
    fun `connect should delegate to serial communicator`() = runTest {
        val config = SerialConfig(baudRate = 115200)
        
        val result = sdk.connect(config)
        
        assertTrue(result)
        assertTrue(sdk.isConnected())
    }

    @Test
    fun `disconnect should delegate to serial communicator`() = runTest {
        sdk.connect()
        
        sdk.disconnect()
        
        assertFalse(sdk.isConnected())
    }

    @Test
    fun `getDeviceId should send correct command and parse response`() = runTest {
        sdk.connect()
        
        // Queue mock response
        val deviceId = "WF001234567890\u0000"
        val responseFrame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.GET_DEVICE_ID)
            .data(deviceId.toByteArray(Charsets.UTF_8).take(15).toByteArray())
            .build()
        mockSerial.queueResponse(responseFrame.toByteArray())
        
        val result = sdk.getDeviceId()
        
        assertTrue(result.isSuccess)
        assertEquals(deviceId, result.getOrNull())
        
        // Verify correct command was sent
        val sentCommands = mockSerial.getSentCommands()
        assertEquals(1, sentCommands.size)
        val sentFrame = ProtocolFrameParser.parse(sentCommands[0])
        assertNotNull(sentFrame)
        assertEquals(VmcCommands.GET_DEVICE_ID, sentFrame!!.command)
        assertEquals(ProtocolFrame.APP_HEADER, sentFrame.header)
    }

    @Test
    fun `getDeviceId when not connected should fail`() = runTest {
        // Don't connect
        
        val result = sdk.getDeviceId()
        
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VmcException.ConnectionException)
    }

    @Test
    fun `getDeviceId with invalid response should fail`() = runTest {
        sdk.connect()
        
        // Queue invalid response (wrong command)
        val wrongResponse = ProtocolFrameBuilder()
            .vmcHeader()
            .command(0x99.toByte()) // Wrong command
            .data(byteArrayOf(1, 2, 3))
            .build()
        mockSerial.queueResponse(wrongResponse.toByteArray())
        
        val result = sdk.getDeviceId()
        
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VmcException.ProtocolException)
    }

    @Test
    fun `sendDeliveryCommand should send correct command and parse response`() = runTest {
        sdk.connect()
        
        val slot = 5
        val quantity = 2
        
        // Queue mock response
        val responseFrame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.DELIVERY_COMMAND)
            .dataBytes(slot.toByte(), quantity.toByte())
            .build()
        mockSerial.queueResponse(responseFrame.toByteArray())
        
        val result = sdk.sendDeliveryCommand(slot, quantity)
        
        assertTrue(result.isSuccess)
        val response = result.getOrNull()!!
        assertEquals(slot.toByte(), response.cargoLane)
        assertEquals(quantity.toByte(), response.quantity)
        
        // Verify correct command was sent
        val sentCommands = mockSerial.getSentCommands()
        assertEquals(1, sentCommands.size)
        val sentFrame = ProtocolFrameParser.parse(sentCommands[0])
        assertNotNull(sentFrame)
        assertEquals(VmcCommands.DELIVERY_COMMAND, sentFrame!!.command)
        assertArrayEquals(byteArrayOf(slot.toByte(), quantity.toByte()), sentFrame.data)
    }

    @Test
    fun `sendDeliveryCommand with invalid slot should fail`() = runTest {
        sdk.connect()
        
        assertThrows(IllegalArgumentException::class.java) {
            runTest { sdk.sendDeliveryCommand(0) } // Slot 0 is invalid
        }
        
        assertThrows(IllegalArgumentException::class.java) {
            runTest { sdk.sendDeliveryCommand(256) } // Slot 256 is invalid
        }
    }

    @Test
    fun `queryDeliveryStatus success should return success response`() = runTest {
        sdk.connect()
        
        // Queue success response
        val responseFrame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.QUERY_STATUS)
            .dataBytes(VmcErrorCodes.SUCCESS)
            .build()
        mockSerial.queueResponse(responseFrame.toByteArray())
        
        val result = sdk.queryDeliveryStatus(1, 1)
        
        assertTrue(result.isSuccess)
        val response = result.getOrNull()!!
        assertTrue(response.success)
        assertNull(response.errorCode)
    }

    @Test
    fun `queryDeliveryStatus failure should return error response`() = runTest {
        sdk.connect()
        
        // Queue error response
        val responseFrame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.QUERY_STATUS)
            .dataBytes(VmcErrorCodes.MOTOR_FAILURE)
            .build()
        mockSerial.queueResponse(responseFrame.toByteArray())
        
        val result = sdk.queryDeliveryStatus(1, 1)
        
        assertTrue(result.isSuccess)
        val response = result.getOrNull()!!
        assertFalse(response.success)
        assertEquals(VmcErrorCodes.MOTOR_FAILURE, response.errorCode)
    }

    @Test
    fun `clearFaults should send correct command`() = runTest {
        sdk.connect()
        
        // Queue success response
        val responseFrame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.REMOVE_FAULT)
            .dataBytes(VmcErrorCodes.SUCCESS)
            .build()
        mockSerial.queueResponse(responseFrame.toByteArray())
        
        val result = sdk.clearFaults()
        
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()!!)
        
        // Verify correct command was sent
        val sentCommands = mockSerial.getSentCommands()
        assertEquals(1, sentCommands.size)
        val sentFrame = ProtocolFrameParser.parse(sentCommands[0])
        assertNotNull(sentFrame)
        assertEquals(VmcCommands.REMOVE_FAULT, sentFrame!!.command)
        assertArrayEquals(byteArrayOf(0xFF.toByte()), sentFrame.data)
    }

    @Test
    fun `queryBalance should return correct balance`() = runTest {
        sdk.connect()
        
        val expectedBalance = 2500 // $25.00 in cents
        val balanceBytes = intToLittleEndianBytes(expectedBalance)
        
        // Queue balance response
        val responseFrame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.QUERY_BALANCE)
            .data(balanceBytes)
            .build()
        mockSerial.queueResponse(responseFrame.toByteArray())
        
        val result = sdk.queryBalance()
        
        assertTrue(result.isSuccess)
        assertEquals(expectedBalance, result.getOrNull())
    }

    @Test
    fun `dispenseWater success should complete operation`() = runTest {
        sdk.connect()
        
        val slot = 3
        
        // Queue delivery command response
        val deliveryResponse = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.DELIVERY_COMMAND)
            .dataBytes(slot.toByte(), 1.toByte())
            .build()
        mockSerial.queueResponse(deliveryResponse.toByteArray())
        
        // Queue status query response (success)
        val statusResponse = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.QUERY_STATUS)
            .dataBytes(VmcErrorCodes.SUCCESS)
            .build()
        mockSerial.queueResponse(statusResponse.toByteArray())
        
        val result = sdk.dispenseWater(slot)
        
        assertTrue(result.isSuccess)
        val dispenseResult = result.getOrNull()!!
        assertTrue(dispenseResult.success)
        assertEquals(slot, dispenseResult.slot)
        assertNull(dispenseResult.errorCode)
        assertTrue(dispenseResult.dispensingTimeMs > 0)
    }

    @Test
    fun `dispenseWater with motor failure should return error result`() = runTest {
        sdk.connect()
        
        val slot = 3
        
        // Queue delivery command response
        val deliveryResponse = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.DELIVERY_COMMAND)
            .dataBytes(slot.toByte(), 1.toByte())
            .build()
        mockSerial.queueResponse(deliveryResponse.toByteArray())
        
        // Queue status query response (motor failure)
        val statusResponse = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.QUERY_STATUS)
            .dataBytes(VmcErrorCodes.MOTOR_FAILURE)
            .build()
        mockSerial.queueResponse(statusResponse.toByteArray())
        
        val result = sdk.dispenseWater(slot)
        
        assertTrue(result.isSuccess)
        val dispenseResult = result.getOrNull()!!
        assertFalse(dispenseResult.success)
        assertEquals(slot, dispenseResult.slot)
        assertEquals(VmcErrorCodes.MOTOR_FAILURE, dispenseResult.errorCode)
        assertTrue(dispenseResult.errorMessage!!.contains("Motor failure"))
    }

    @Test
    fun `dispenseWater with delivery command failure should return error result`() = runTest {
        sdk.connect()
        
        val slot = 3
        
        // Don't queue any response - simulate communication failure
        
        val result = sdk.dispenseWater(slot)
        
        assertTrue(result.isSuccess)
        val dispenseResult = result.getOrNull()!!
        assertFalse(dispenseResult.success)
        assertEquals(slot, dispenseResult.slot)
        assertTrue(dispenseResult.errorMessage!!.contains("Failed to send delivery command"))
    }

    @Test
    fun `command timeout should throw TimeoutException`() = runTest {
        sdk.connect()
        
        // Don't queue any response - will cause timeout
        
        val result = sdk.getDeviceId()
        
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VmcException.TimeoutException)
    }

    @Test
    fun `send command failure should throw ProtocolException`() = runTest {
        sdk.connect()
        mockSerial.disconnect() // Force send failure
        
        val result = sdk.getDeviceId()
        
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VmcException.ProtocolException)
    }

    @Test
    fun `invalid response frame should throw ProtocolException`() = runTest {
        sdk.connect()
        
        // Queue invalid frame data
        val invalidFrame = byteArrayOf(1, 2, 3) // Too short
        mockSerial.queueResponse(invalidFrame)
        
        val result = sdk.getDeviceId()
        
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VmcException.ProtocolException)
    }

    @Test
    fun `response with wrong header should throw ProtocolException`() = runTest {
        sdk.connect()
        
        // Queue response with wrong header
        val wrongHeaderResponse = ProtocolFrameBuilder()
            .appHeader() // Should be VMC header
            .command(VmcCommands.GET_DEVICE_ID)
            .data(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15))
            .build()
        mockSerial.queueResponse(wrongHeaderResponse.toByteArray())
        
        val result = sdk.getDeviceId()
        
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VmcException.ProtocolException)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Invalid response header"))
    }

    @Test
    fun `sendPaymentInstruction should send correct command and parse response`() = runTest {
        sdk.connect()
        
        val amount = 1500 // $15.00 in cents
        val paymentMethod = PaymentMethods.OFFLINE_CASHLESS
        val slot = 3
        
        // Queue mock response
        val responseFrame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.PAYMENT_INSTRUCTION)
            .dataBytes(VmcErrorCodes.SUCCESS)
            .build()
        mockSerial.queueResponse(responseFrame.toByteArray())
        
        val result = sdk.sendPaymentInstruction(amount, paymentMethod, slot)
        
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()!!)
        
        // Verify correct command was sent
        val sentCommands = mockSerial.getSentCommands()
        assertEquals(1, sentCommands.size)
        val sentFrame = ProtocolFrameParser.parse(sentCommands[0])
        assertNotNull(sentFrame)
        assertEquals(VmcCommands.PAYMENT_INSTRUCTION, sentFrame!!.command)
        assertEquals(6, sentFrame.dataLength.toInt() and 0xFF)
    }

    @Test
    fun `sendPaymentInstruction with invalid slot should fail`() = runTest {
        sdk.connect()
        
        assertThrows(IllegalArgumentException::class.java) {
            runTest { sdk.sendPaymentInstruction(100, PaymentMethods.OFFLINE_COIN, 0) }
        }
        
        assertThrows(IllegalArgumentException::class.java) {
            runTest { sdk.sendPaymentInstruction(100, PaymentMethods.OFFLINE_COIN, 256) }
        }
    }

    @Test
    fun `sendPaymentInstruction with negative amount should fail`() = runTest {
        sdk.connect()
        
        assertThrows(IllegalArgumentException::class.java) {
            runTest { sdk.sendPaymentInstruction(-100, PaymentMethods.OFFLINE_COIN, 1) }
        }
    }

    @Test
    fun `requestCoinChange should send correct command`() = runTest {
        sdk.connect()
        
        // Queue success response
        val responseFrame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.COIN_CHANGE)
            .dataBytes(VmcErrorCodes.SUCCESS)
            .build()
        mockSerial.queueResponse(responseFrame.toByteArray())
        
        val result = sdk.requestCoinChange()
        
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()!!)
        
        // Verify correct command was sent
        val sentCommands = mockSerial.getSentCommands()
        assertEquals(1, sentCommands.size)
        val sentFrame = ProtocolFrameParser.parse(sentCommands[0])
        assertNotNull(sentFrame)
        assertEquals(VmcCommands.COIN_CHANGE, sentFrame!!.command)
        assertArrayEquals(byteArrayOf(0xFF.toByte()), sentFrame.data)
    }

    @Test
    fun `cancelCashlessPayment should send correct command`() = runTest {
        sdk.connect()
        
        // Queue success response
        val responseFrame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.CASHLESS_CANCEL)
            .dataBytes(VmcErrorCodes.SUCCESS)
            .build()
        mockSerial.queueResponse(responseFrame.toByteArray())
        
        val result = sdk.cancelCashlessPayment()
        
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()!!)
        
        // Verify correct command was sent
        val sentCommands = mockSerial.getSentCommands()
        assertEquals(1, sentCommands.size)
        val sentFrame = ProtocolFrameParser.parse(sentCommands[0])
        assertNotNull(sentFrame)
        assertEquals(VmcCommands.CASHLESS_CANCEL, sentFrame!!.command)
        assertArrayEquals(byteArrayOf(0xFF.toByte()), sentFrame.data)
    }

    @Test
    fun `sendDebitInstruction should send correct command`() = runTest {
        sdk.connect()
        
        val amount = 750 // $7.50 in cents
        
        // Queue success response
        val responseFrame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.DEBIT_INSTRUCTION)
            .dataBytes(VmcErrorCodes.SUCCESS)
            .build()
        mockSerial.queueResponse(responseFrame.toByteArray())
        
        val result = sdk.sendDebitInstruction(amount)
        
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()!!)
        
        // Verify correct command was sent
        val sentCommands = mockSerial.getSentCommands()
        assertEquals(1, sentCommands.size)
        val sentFrame = ProtocolFrameParser.parse(sentCommands[0])
        assertNotNull(sentFrame)
        assertEquals(VmcCommands.DEBIT_INSTRUCTION, sentFrame!!.command)
        assertEquals(4, sentFrame.dataLength.toInt() and 0xFF)
    }

    @Test
    fun `sendDebitInstruction with negative amount should fail`() = runTest {
        sdk.connect()
        
        assertThrows(IllegalArgumentException::class.java) {
            runTest { sdk.sendDebitInstruction(-100) }
        }
    }

    @Test
    fun `startAgeRecognition should send correct command`() = runTest {
        sdk.connect()
        
        val requiredAge = 21
        
        // Queue success response
        val responseFrame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.AGE_RECOGNITION)
            .dataBytes(VmcErrorCodes.SUCCESS)
            .build()
        mockSerial.queueResponse(responseFrame.toByteArray())
        
        val result = sdk.startAgeRecognition(requiredAge)
        
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()!!)
        
        // Verify correct command was sent
        val sentCommands = mockSerial.getSentCommands()
        assertEquals(1, sentCommands.size)
        val sentFrame = ProtocolFrameParser.parse(sentCommands[0])
        assertNotNull(sentFrame)
        assertEquals(VmcCommands.AGE_RECOGNITION, sentFrame!!.command)
        assertArrayEquals(byteArrayOf(requiredAge.toByte()), sentFrame.data)
    }

    @Test
    fun `startAgeRecognition with invalid age should fail`() = runTest {
        sdk.connect()
        
        assertThrows(IllegalArgumentException::class.java) {
            runTest { sdk.startAgeRecognition(0) }
        }
        
        assertThrows(IllegalArgumentException::class.java) {
            runTest { sdk.startAgeRecognition(100) }
        }
    }

    @Test
    fun `queryCoinChangeStatus should return can refund status`() = runTest {
        sdk.connect()
        
        // Queue "can refund" response (status 0)
        val responseFrame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.QUERY_COIN_CHANGE_STATUS)
            .dataBytes(0x00) // Can refund
            .build()
        mockSerial.queueResponse(responseFrame.toByteArray())
        
        val result = sdk.queryCoinChangeStatus()
        
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()!!) // Can refund
    }

    @Test
    fun `queryCoinChangeStatus should return cannot refund status`() = runTest {
        sdk.connect()
        
        // Queue "cannot refund" response (status 1)
        val responseFrame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.QUERY_COIN_CHANGE_STATUS)
            .dataBytes(0x01) // Cannot refund
            .build()
        mockSerial.queueResponse(responseFrame.toByteArray())
        
        val result = sdk.queryCoinChangeStatus()
        
        assertTrue(result.isSuccess)
        assertFalse(result.getOrNull()!!) // Cannot refund
    }

    @Test
    fun `queryAgeVerificationStatus should return verification successful`() = runTest {
        sdk.connect()
        
        // Queue "verification successful" response (status 1)
        val responseFrame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.QUERY_AGE_VERIFICATION)
            .dataBytes(0x01) // Verification successful
            .build()
        mockSerial.queueResponse(responseFrame.toByteArray())
        
        val result = sdk.queryAgeVerificationStatus()
        
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()!!) // Verification successful
    }

    @Test
    fun `queryAgeVerificationStatus should return verification failed`() = runTest {
        sdk.connect()
        
        // Queue "verification failed" response (status 0)
        val responseFrame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.QUERY_AGE_VERIFICATION)
            .dataBytes(0x00) // Verification failed
            .build()
        mockSerial.queueResponse(responseFrame.toByteArray())
        
        val result = sdk.queryAgeVerificationStatus()
        
        assertTrue(result.isSuccess)
        assertFalse(result.getOrNull()!!) // Verification failed
    }

    @Test
    fun `all payment methods when not connected should fail`() = runTest {
        // Don't connect
        
        val paymentResult = sdk.sendPaymentInstruction(100, PaymentMethods.OFFLINE_COIN, 1)
        assertTrue(paymentResult.isFailure)
        assertTrue(paymentResult.exceptionOrNull() is VmcException.ConnectionException)
        
        val coinChangeResult = sdk.requestCoinChange()
        assertTrue(coinChangeResult.isFailure)
        assertTrue(coinChangeResult.exceptionOrNull() is VmcException.ConnectionException)
        
        val cancelResult = sdk.cancelCashlessPayment()
        assertTrue(cancelResult.isFailure)
        assertTrue(cancelResult.exceptionOrNull() is VmcException.ConnectionException)
    }

    @Test
    fun `complex payment workflow should work correctly`() = runTest {
        sdk.connect()
        
        val amount = 200 // $2.00
        val slot = 1
        
        // Step 1: Send payment instruction
        val paymentResponseFrame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.PAYMENT_INSTRUCTION)
            .dataBytes(VmcErrorCodes.SUCCESS)
            .build()
        mockSerial.queueResponse(paymentResponseFrame.toByteArray())
        
        val paymentResult = sdk.sendPaymentInstruction(amount, PaymentMethods.OFFLINE_CASHLESS, slot)
        assertTrue(paymentResult.isSuccess)
        assertTrue(paymentResult.getOrNull()!!)
        
        // Step 2: Send delivery command
        val deliveryResponseFrame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.DELIVERY_COMMAND)
            .dataBytes(slot.toByte(), 1.toByte())
            .build()
        mockSerial.queueResponse(deliveryResponseFrame.toByteArray())
        
        val deliveryResult = sdk.sendDeliveryCommand(slot, 1)
        assertTrue(deliveryResult.isSuccess)
        
        // Step 3: Query status
        val statusResponseFrame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.QUERY_STATUS)
            .dataBytes(VmcErrorCodes.SUCCESS)
            .build()
        mockSerial.queueResponse(statusResponseFrame.toByteArray())
        
        val statusResult = sdk.queryDeliveryStatus(slot, 1)
        assertTrue(statusResult.isSuccess)
        assertTrue(statusResult.getOrNull()!!.success)
        
        // Verify all commands were sent in correct order
        val sentCommands = mockSerial.getSentCommands()
        assertEquals(3, sentCommands.size)
        
        val paymentFrame = ProtocolFrameParser.parse(sentCommands[0])
        assertEquals(VmcCommands.PAYMENT_INSTRUCTION, paymentFrame!!.command)
        
        val deliveryFrame = ProtocolFrameParser.parse(sentCommands[1])
        assertEquals(VmcCommands.DELIVERY_COMMAND, deliveryFrame!!.command)
        
        val statusFrame = ProtocolFrameParser.parse(sentCommands[2])
        assertEquals(VmcCommands.QUERY_STATUS, statusFrame!!.command)
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

/**
 * Tests for exception classes
 */
class VmcExceptionTest {

    @Test
    fun `ConnectionException should extend VmcException`() {
        val exception = VmcException.ConnectionException("Connection failed")
        
        assertTrue(exception is VmcException)
        assertEquals("Connection failed", exception.message)
    }

    @Test
    fun `ProtocolException should extend VmcException`() {
        val exception = VmcException.ProtocolException("Protocol error")
        
        assertTrue(exception is VmcException)
        assertEquals("Protocol error", exception.message)
    }

    @Test
    fun `TimeoutException should extend VmcException`() {
        val exception = VmcException.TimeoutException("Operation timed out")
        
        assertTrue(exception is VmcException)
        assertEquals("Operation timed out", exception.message)
    }

    @Test
    fun `HardwareException should extend VmcException and include error code`() {
        val errorCode: Byte = 0x02
        val exception = VmcException.HardwareException("Hardware failure", errorCode)
        
        assertTrue(exception is VmcException)
        assertEquals("Hardware failure", exception.message)
        assertEquals(errorCode, exception.errorCode)
    }
}

/**
 * Tests for WaterDispenseResult
 */
class WaterDispenseResultTest {

    @Test
    fun `successful dispense result should have correct properties`() {
        val result = WaterDispenseResult(
            success = true,
            slot = 5,
            dispensingTimeMs = 3000
        )
        
        assertTrue(result.success)
        assertEquals(5, result.slot)
        assertNull(result.errorCode)
        assertNull(result.errorMessage)
        assertEquals(3000, result.dispensingTimeMs)
    }

    @Test
    fun `failed dispense result should include error details`() {
        val result = WaterDispenseResult(
            success = false,
            slot = 3,
            errorCode = VmcErrorCodes.MOTOR_FAILURE,
            errorMessage = "Motor failure in slot 3",
            dispensingTimeMs = 1500
        )
        
        assertFalse(result.success)
        assertEquals(3, result.slot)
        assertEquals(VmcErrorCodes.MOTOR_FAILURE, result.errorCode)
        assertEquals("Motor failure in slot 3", result.errorMessage)
        assertEquals(1500, result.dispensingTimeMs)
    }
}
