package com.waterfountainmachine.app.hardware.sdk

import org.junit.Test
import org.junit.Assert.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Comprehensive unit tests for ProtocolFrame
 */
class ProtocolFrameTest {

    @Test
    fun `create frame with builder should calculate correct checksum`() {
        val frame = ProtocolFrameBuilder()
            .appHeader()
            .command(0x31)
            .dataBytes(0xAD.toByte())
            .build()

        // Verify frame structure
        assertEquals(ProtocolFrame.FIXED_ADDRESS, frame.address)
        assertEquals(ProtocolFrame.FIXED_FRAME_NUMBER, frame.frameNumber)
        assertEquals(ProtocolFrame.APP_HEADER, frame.header)
        assertEquals(0x31.toByte(), frame.command)
        assertEquals(1, frame.dataLength.toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(0xAD.toByte()), frame.data)

        // Verify checksum calculation
        val expectedChecksum = (0x55 + 0x31 + 0x01 + 0xAD) and 0xFF
        assertEquals(expectedChecksum.toByte(), frame.checksum)
        assertTrue(frame.isChecksumValid())
    }

    @Test
    fun `create VMC response frame should have correct header`() {
        val frame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(0x31)
            .data(byteArrayOf(1, 2, 3, 4, 5))
            .build()

        assertEquals(ProtocolFrame.VMC_HEADER, frame.header)
        assertEquals(5, frame.dataLength.toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), frame.data)
    }

    @Test
    fun `frame with invalid checksum should not be valid`() {
        val frame = ProtocolFrame(
            header = ProtocolFrame.APP_HEADER,
            command = 0x31,
            dataLength = 1,
            data = byteArrayOf(0xAD.toByte()),
            checksum = 0x00 // Wrong checksum
        )

        assertFalse(frame.isChecksumValid())
    }

    @Test
    fun `frame with mismatched data length should not be valid`() {
        val frame = ProtocolFrame(
            header = ProtocolFrame.APP_HEADER,
            command = 0x31,
            dataLength = 5, // Says 5 bytes
            data = byteArrayOf(0xAD.toByte()), // But only 1 byte
            checksum = 0x00
        )

        assertFalse(frame.isChecksumValid())
    }

    @Test
    fun `toByteArray should create correct byte sequence`() {
        val frame = ProtocolFrameBuilder()
            .appHeader()
            .command(0x41)
            .dataBytes(0x01, 0x01) // Slot 1, Quantity 1
            .build()

        val bytes = frame.toByteArray()
        val expected = byteArrayOf(
            0xFF.toByte(),      // Address
            0x00,               // Frame number
            0x55,               // Header (APP)
            0x41,               // Command
            0x02,               // Data length
            0x01, 0x01,         // Data
            frame.checksum      // Checksum
        )

        assertArrayEquals(expected, bytes)
    }

    @Test
    fun `checksum calculation should exclude address, frame number and checksum`() {
        val frame = ProtocolFrame(
            address = 0xFF.toByte(),
            frameNumber = 0x00,
            header = 0x55,
            command = 0x31,
            dataLength = 0x01,
            data = byteArrayOf(0xAD.toByte()),
            checksum = 0x00 // Will be ignored in calculation
        )

        val calculatedChecksum = frame.calculateChecksum()
        val expectedChecksum = (0x55 + 0x31 + 0x01 + 0xAD) and 0xFF

        assertEquals(expectedChecksum.toByte(), calculatedChecksum)
    }

    @Test
    fun `frame with empty data should be valid`() {
        val frame = ProtocolFrameBuilder()
            .appHeader()
            .command(0xA2.toByte())
            .data(byteArrayOf())
            .build()

        assertEquals(0, frame.dataLength.toInt())
        assertArrayEquals(byteArrayOf(), frame.data)
        assertTrue(frame.isChecksumValid())
    }

    @Test
    fun `frame with maximum data size should be valid`() {
        val maxData = ByteArray(255) { it.toByte() }
        val frame = ProtocolFrameBuilder()
            .appHeader()
            .command(0x31)
            .data(maxData)
            .build()

        assertEquals(255, frame.dataLength.toInt() and 0xFF)
        assertArrayEquals(maxData, frame.data)
        assertTrue(frame.isChecksumValid())
    }

    @Test
    fun `frame equality should work correctly`() {
        val frame1 = ProtocolFrameBuilder()
            .appHeader()
            .command(0x31)
            .dataBytes(0xAD.toByte())
            .build()

        val frame2 = ProtocolFrameBuilder()
            .appHeader()
            .command(0x31)
            .dataBytes(0xAD.toByte())
            .build()

        val frame3 = ProtocolFrameBuilder()
            .appHeader()
            .command(0x41)
            .dataBytes(0xAD.toByte())
            .build()

        assertEquals(frame1, frame2)
        assertNotEquals(frame1, frame3)
        assertEquals(frame1.hashCode(), frame2.hashCode())
    }
}

/**
 * Tests for ProtocolFrameParser
 */
class ProtocolFrameParserTest {

    @Test
    fun `parse valid frame should return correct frame`() {
        val originalFrame = ProtocolFrameBuilder()
            .appHeader()
            .command(0x31)
            .dataBytes(0xAD.toByte())
            .build()

        val bytes = originalFrame.toByteArray()
        val parsedFrame = ProtocolFrameParser.parse(bytes)

        assertNotNull(parsedFrame)
        assertEquals(originalFrame, parsedFrame)
    }

    @Test
    fun `parse frame with insufficient bytes should return null`() {
        val insufficientBytes = byteArrayOf(0xFF.toByte(), 0x00, 0x55) // Only 3 bytes
        val parsedFrame = ProtocolFrameParser.parse(insufficientBytes)

        assertNull(parsedFrame)
    }

    @Test
    fun `parse frame with invalid checksum should return null`() {
        val validFrame = ProtocolFrameBuilder()
            .appHeader()
            .command(0x31)
            .dataBytes(0xAD.toByte())
            .build()

        val bytes = validFrame.toByteArray()
        bytes[bytes.size - 1] = 0x00 // Corrupt checksum

        val parsedFrame = ProtocolFrameParser.parse(bytes)
        assertNull(parsedFrame)
    }

    @Test
    fun `parse frame with mismatched data length should return null`() {
        val bytes = byteArrayOf(
            0xFF.toByte(),  // Address
            0x00,           // Frame number
            0x55,           // Header
            0x31,           // Command
            0x05,           // Data length (says 5)
            0xAD.toByte(),  // Data (only 1 byte)
            0x00            // Checksum
        )

        val parsedFrame = ProtocolFrameParser.parse(bytes)
        assertNull(parsedFrame)
    }

    @Test
    fun `parse VMC response frame should work correctly`() {
        // Simulate VMC device ID response
        val deviceId = "WF001234567890\u0000" // 15 bytes with null terminator
        val deviceIdBytes = deviceId.toByteArray(Charsets.UTF_8).take(15).toByteArray()

        val vmcResponse = ProtocolFrameBuilder()
            .vmcHeader()
            .command(0x31)
            .data(deviceIdBytes)
            .build()

        val bytes = vmcResponse.toByteArray()
        val parsedFrame = ProtocolFrameParser.parse(bytes)

        assertNotNull(parsedFrame)
        assertEquals(ProtocolFrame.VMC_HEADER, parsedFrame!!.header)
        assertEquals(0x31.toByte(), parsedFrame.command)
        assertEquals(15, parsedFrame.dataLength.toInt() and 0xFF)
        assertArrayEquals(deviceIdBytes, parsedFrame.data)
    }

    @Test
    fun `parse frame with correct byte order should handle multi-byte data`() {
        // Test little-endian data parsing
        val multiByteData = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val frame = ProtocolFrameBuilder()
            .vmcHeader()
            .command(0xE1.toByte())
            .data(multiByteData)
            .build()

        val bytes = frame.toByteArray()
        val parsedFrame = ProtocolFrameParser.parse(bytes)

        assertNotNull(parsedFrame)
        assertArrayEquals(multiByteData, parsedFrame!!.data)
    }
}
