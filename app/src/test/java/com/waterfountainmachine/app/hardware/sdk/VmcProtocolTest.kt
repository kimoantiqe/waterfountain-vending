package com.waterfountainmachine.app.hardware.sdk

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for VMC Protocol commands and responses - Water Fountain Edition
 * Only tests essential water fountain functionality
 */
class VmcProtocolTest {

    @Test
    fun `VmcCommandBuilder getDeviceId should create correct frame`() {
        val frame = VmcCommandBuilder.getDeviceId()

        assertEquals(ProtocolFrame.APP_HEADER, frame.header)
        assertEquals(VmcCommands.GET_DEVICE_ID, frame.command)
        assertEquals(1, frame.dataLength.toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(0xAD.toByte()), frame.data)
        assertTrue(frame.isChecksumValid())
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
        assertTrue(frame.isChecksumValid())
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
        assertTrue(frame.isChecksumValid())
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
        assertTrue(frame.isChecksumValid())
    }

    @Test
    fun `VmcResponseParser parseResponse for DeviceId should extract device ID`() {
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
        assertEquals("WF001234567890", deviceResponse.deviceId)  // Trimmed null terminator
    }

    @Test
    fun `VmcResponseParser parseResponse for Delivery should extract slot and quantity`() {
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
    fun `VmcResponseParser parseResponse for Status success should indicate success`() {
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
    }

    @Test
    fun `VmcResponseParser parseResponse for Status with error code should indicate failure`() {
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
    }

    @Test
    fun `VmcResponseParser parseResponse for RemoveFault success should indicate success`() {
        val frame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.REMOVE_FAULT)
            .dataBytes(VmcErrorCodes.SUCCESS)
            .build()

        val response = VmcResponseParser.parseResponse(frame)

        assertTrue(response is VmcResponse.SuccessResponse)
        val successResponse = response as VmcResponse.SuccessResponse
        assertTrue(successResponse.success)
    }

    @Test
    fun `VmcResponseParser parseResponse for RemoveFault failure should indicate failure`() {
        val frame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.REMOVE_FAULT)
            .dataBytes(0x00) // Not success
            .build()

        val response = VmcResponseParser.parseResponse(frame)

        assertTrue(response is VmcResponse.SuccessResponse)
        val successResponse = response as VmcResponse.SuccessResponse
        assertFalse(successResponse.success)
    }

    @Test
    fun `VmcResponseParser parseResponse with unknown command should return error`() {
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
    fun `error codes should have correct values`() {
        assertEquals(0x01.toByte(), VmcErrorCodes.SUCCESS)
        assertEquals(0x02.toByte(), VmcErrorCodes.MOTOR_FAILURE)
        assertEquals(0x03.toByte(), VmcErrorCodes.OPTICAL_EYE_FAILURE)
    }

    @Test
    fun `water fountain command constants should have correct hex values`() {
        assertEquals(0x31.toByte(), VmcCommands.GET_DEVICE_ID)
        assertEquals(0x41.toByte(), VmcCommands.DELIVERY_COMMAND)
        assertEquals(0xA2.toByte(), VmcCommands.REMOVE_FAULT)
        assertEquals(0xE1.toByte(), VmcCommands.QUERY_STATUS)
    }

}
