package com.waterfountainmachine.app.hardware.sdk

import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

/**
 * Unit tests for MockSerialCommunicator
 */
class MockSerialCommunicatorTest {

    private lateinit var mockSerial: MockSerialCommunicator

    @Before
    fun setUp() {
        mockSerial = MockSerialCommunicator()
    }

    @Test
    fun `connect should set connected state`() = runTest {
        assertFalse(mockSerial.isConnected())
        
        val result = mockSerial.connect(SerialConfig())
        
        assertTrue(result)
        assertTrue(mockSerial.isConnected())
    }

    @Test
    fun `disconnect should clear state`() = runTest {
        mockSerial.connect(SerialConfig())
        mockSerial.queueResponse(byteArrayOf(1, 2, 3))
        mockSerial.sendData(byteArrayOf(4, 5, 6))
        
        mockSerial.disconnect()
        
        assertFalse(mockSerial.isConnected())
        assertTrue(mockSerial.getSentCommands().isEmpty())
        assertNull(mockSerial.readData())
    }

    @Test
    fun `sendData when connected should store command and return true`() = runTest {
        mockSerial.connect(SerialConfig())
        val testData = byteArrayOf(1, 2, 3, 4)
        
        val result = mockSerial.sendData(testData)
        
        assertTrue(result)
        assertEquals(1, mockSerial.getSentCommands().size)
        assertArrayEquals(testData, mockSerial.getSentCommands()[0])
    }

    @Test
    fun `sendData when not connected should return false`() = runTest {
        val testData = byteArrayOf(1, 2, 3, 4)
        
        val result = mockSerial.sendData(testData)
        
        assertFalse(result)
        assertTrue(mockSerial.getSentCommands().isEmpty())
    }

    @Test
    fun `readData should return queued responses in order`() = runTest {
        mockSerial.connect(SerialConfig())
        val response1 = byteArrayOf(1, 2, 3)
        val response2 = byteArrayOf(4, 5, 6)
        
        mockSerial.queueResponse(response1)
        mockSerial.queueResponse(response2)
        
        val result1 = mockSerial.readData()
        val result2 = mockSerial.readData()
        val result3 = mockSerial.readData()
        
        assertArrayEquals(response1, result1)
        assertArrayEquals(response2, result2)
        assertNull(result3) // No more responses
    }

    @Test
    fun `readData when not connected should return null`() = runTest {
        mockSerial.queueResponse(byteArrayOf(1, 2, 3))
        
        val result = mockSerial.readData()
        
        assertNull(result)
    }

    @Test
    fun `clearBuffers should remove queued responses`() = runTest {
        mockSerial.connect(SerialConfig())
        mockSerial.queueResponse(byteArrayOf(1, 2, 3))
        mockSerial.queueResponse(byteArrayOf(4, 5, 6))
        
        mockSerial.clearBuffers()
        
        assertNull(mockSerial.readData())
    }

    @Test
    fun `clearSentCommands should remove command history`() = runTest {
        mockSerial.connect(SerialConfig())
        mockSerial.sendData(byteArrayOf(1, 2, 3))
        mockSerial.sendData(byteArrayOf(4, 5, 6))
        
        assertEquals(2, mockSerial.getSentCommands().size)
        
        mockSerial.clearSentCommands()
        
        assertTrue(mockSerial.getSentCommands().isEmpty())
    }

    @Test
    fun `multiple sendData calls should preserve order`() = runTest {
        mockSerial.connect(SerialConfig())
        val command1 = byteArrayOf(1, 2)
        val command2 = byteArrayOf(3, 4)
        val command3 = byteArrayOf(5, 6)
        
        mockSerial.sendData(command1)
        mockSerial.sendData(command2)
        mockSerial.sendData(command3)
        
        val commands = mockSerial.getSentCommands()
        assertEquals(3, commands.size)
        assertArrayEquals(command1, commands[0])
        assertArrayEquals(command2, commands[1])
        assertArrayEquals(command3, commands[2])
    }

    @Test
    fun `sendData should create copy of data to prevent external modification`() = runTest {
        mockSerial.connect(SerialConfig())
        val originalData = byteArrayOf(1, 2, 3)
        
        mockSerial.sendData(originalData)
        originalData[0] = 99 // Modify original
        
        val storedCommand = mockSerial.getSentCommands()[0]
        assertEquals(1, storedCommand[0]) // Should still be original value
    }
}

/**
 * Unit tests for SerialConfig
 */
class SerialConfigTest {

    @Test
    fun `default SerialConfig should have correct values`() {
        val config = SerialConfig()
        
        assertEquals(9600, config.baudRate)
        assertEquals(8, config.dataBits)
        assertEquals(1, config.stopBits)
        assertEquals(SerialConfig.Parity.NONE, config.parity)
        assertEquals(SerialConfig.FlowControl.NONE, config.flowControl)
        assertNull(config.devicePath)
    }

    @Test
    fun `custom SerialConfig should accept all parameters`() {
        val config = SerialConfig(
            baudRate = 115200,
            dataBits = 7,
            stopBits = 2,
            parity = SerialConfig.Parity.EVEN,
            flowControl = SerialConfig.FlowControl.HARDWARE,
            devicePath = "/dev/ttyUSB0"
        )
        
        assertEquals(115200, config.baudRate)
        assertEquals(7, config.dataBits)
        assertEquals(2, config.stopBits)
        assertEquals(SerialConfig.Parity.EVEN, config.parity)
        assertEquals(SerialConfig.FlowControl.HARDWARE, config.flowControl)
        assertEquals("/dev/ttyUSB0", config.devicePath)
    }

    @Test
    fun `parity enum should have correct values`() {
        val values = SerialConfig.Parity.values()
        assertEquals(3, values.size)
        assertTrue(values.contains(SerialConfig.Parity.NONE))
        assertTrue(values.contains(SerialConfig.Parity.ODD))
        assertTrue(values.contains(SerialConfig.Parity.EVEN))
    }

    @Test
    fun `flowControl enum should have correct values`() {
        val values = SerialConfig.FlowControl.values()
        assertEquals(3, values.size)
        assertTrue(values.contains(SerialConfig.FlowControl.NONE))
        assertTrue(values.contains(SerialConfig.FlowControl.HARDWARE))
        assertTrue(values.contains(SerialConfig.FlowControl.SOFTWARE))
    }
}
