# Water Fountain SDK Integration Example

## Quick Start Guide

This example demonstrates how to integrate the Water Fountain Hardware SDK into your Android application.

## Setup

### 1. Add Dependencies

Add to your `app/build.gradle`:

```kotlin
dependencies {
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4'
    // Your other dependencies
}
```

### 2. Initialize the SDK

```kotlin
class WaterFountainController {
    private val sdk: VendingMachineSDK
    
    init {
        // For testing/simulation - use MockSerialCommunicator
        val mockCommunicator = MockSerialCommunicator()
        
        // For production - implement real serial communicator
        // val usbCommunicator = MyUsbSerialCommunicator()
        
        sdk = VendingMachineSDKImpl(
            serialCommunicator = mockCommunicator,
            commandTimeoutMs = 5000,
            statusPollingIntervalMs = 500,
            maxStatusPollingAttempts = 20
        )
    }
}
```

## Basic Operations

### 1. Connect to Water Fountain

```kotlin
suspend fun connectToFountain(): Boolean {
    val config = SerialConfig(
        baudRate = 9600,
        dataBits = 8,
        stopBits = 1,
        parity = SerialConfig.Parity.NONE,
        flowControl = SerialConfig.FlowControl.NONE
    )
    
    return try {
        sdk.connect(config)
    } catch (e: Exception) {
        Log.e("WaterFountain", "Connection failed: ${e.message}")
        false
    }
}
```

### 2. Get Device Information

```kotlin
suspend fun getDeviceInfo(): String? {
    val result = sdk.getDeviceId()
    return if (result.isSuccess) {
        result.getOrNull()
    } else {
        Log.e("WaterFountain", "Failed to get device ID: ${result.exceptionOrNull()}")
        null
    }
}
```

### 3. Dispense Water (Recommended Method)

```kotlin
suspend fun dispenseWater(slot: Int): WaterDispenseResult {
    val result = sdk.dispenseWater(slot)
    
    return if (result.isSuccess) {
        val dispenseResult = result.getOrNull()!!
        if (dispenseResult.success) {
            Log.i("WaterFountain", "Water dispensed from slot $slot in ${dispenseResult.dispensingTimeMs}ms")
        } else {
            Log.e("WaterFountain", "Dispensing failed: ${dispenseResult.errorMessage}")
        }
        dispenseResult
    } else {
        Log.e("WaterFountain", "Dispensing error: ${result.exceptionOrNull()}")
        WaterDispenseResult(
            success = false,
            slot = slot,
            errorMessage = "SDK error: ${result.exceptionOrNull()?.message}"
        )
    }
}
```

### 4. Manual Control (Advanced)

```kotlin
suspend fun manualDispenseControl(slot: Int): Boolean {
    try {
        // Step 1: Send delivery command
        val deliveryResult = sdk.sendDeliveryCommand(slot, quantity = 1)
        if (deliveryResult.isFailure) {
            Log.e("WaterFountain", "Delivery command failed")
            return false
        }
        
        // Step 2: Poll status until completion
        repeat(20) { attempt ->
            delay(500) // Wait 500ms between polls
            
            val statusResult = sdk.queryDeliveryStatus(slot, quantity = 1)
            if (statusResult.isSuccess) {
                val status = statusResult.getOrNull()!!
                when {
                    status.success -> {
                        Log.i("WaterFountain", "Dispensing completed successfully")
                        return true
                    }
                    status.errorCode != null -> {
                        Log.e("WaterFountain", "Dispensing failed with error: ${status.errorCode}")
                        return false
                    }
                    // Still in progress, continue polling
                }
            }
        }
        
        Log.e("WaterFountain", "Dispensing timed out")
        return false
        
    } catch (e: Exception) {
        Log.e("WaterFountain", "Manual control error: ${e.message}")
        return false
    }
}
```

### 5. Error Handling and Recovery

```kotlin
suspend fun clearFaultsAndRetry(slot: Int): Boolean {
    try {
        // Clear any existing faults
        val clearResult = sdk.clearFaults()
        if (clearResult.isFailure) {
            Log.e("WaterFountain", "Failed to clear faults")
            return false
        }
        
        val cleared = clearResult.getOrNull() ?: false
        if (cleared) {
            Log.i("WaterFountain", "Faults cleared, retrying dispense")
            
            // Retry dispensing
            val dispenseResult = dispenseWater(slot)
            return dispenseResult.success
        } else {
            Log.w("WaterFountain", "No faults to clear")
            return false
        }
        
    } catch (e: Exception) {
        Log.e("WaterFountain", "Error recovery failed: ${e.message}")
        return false
    }
}
```

## Complete Integration Example

```kotlin
class WaterFountainManager(context: Context) {
    private val sdk: VendingMachineSDK
    private var isConnected = false
    
    init {
        // Initialize with mock for testing
        val mockCommunicator = MockSerialCommunicator()
        
        // Setup mock responses for testing
        setupMockResponses(mockCommunicator)
        
        sdk = VendingMachineSDKImpl(mockCommunicator)
    }
    
    suspend fun initialize(): Boolean {
        if (isConnected) return true
        
        return try {
            val connected = sdk.connect()
            if (connected) {
                isConnected = true
                val deviceId = getDeviceInfo()
                Log.i("WaterFountain", "Connected to device: $deviceId")
                true
            } else {
                Log.e("WaterFountain", "Failed to connect")
                false
            }
        } catch (e: Exception) {
            Log.e("WaterFountain", "Initialization error: ${e.message}")
            false
        }
    }
    
    suspend fun dispenseWaterWithRetry(slot: Int, maxRetries: Int = 3): WaterDispenseResult {
        var lastResult: WaterDispenseResult? = null
        
        repeat(maxRetries) { attempt ->
            Log.i("WaterFountain", "Dispensing attempt ${attempt + 1} for slot $slot")
            
            val result = dispenseWater(slot)
            if (result.success) {
                return result
            }
            
            lastResult = result
            
            // Try to clear faults if there's an error code
            if (result.errorCode != null) {
                Log.i("WaterFountain", "Attempting to clear faults...")
                clearFaultsAndRetry(slot)
            }
            
            // Wait before retry
            if (attempt < maxRetries - 1) {
                delay(1000)
            }
        }
        
        return lastResult ?: WaterDispenseResult(
            success = false,
            slot = slot,
            errorMessage = "All retry attempts failed"
        )
    }
    
    suspend fun shutdown() {
        try {
            sdk.disconnect()
            isConnected = false
            Log.i("WaterFountain", "SDK disconnected")
        } catch (e: Exception) {
            Log.e("WaterFountain", "Shutdown error: ${e.message}")
        }
    }
    
    private fun setupMockResponses(mockCommunicator: MockSerialCommunicator) {
        // This would be used for testing
        // In production, remove this and use real hardware communication
        
        // Mock device ID response
        val deviceIdResponse = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.GET_DEVICE_ID)
            .data("WF001234567890\u0000".toByteArray().take(15).toByteArray())
            .build()
        mockCommunicator.queueResponse(deviceIdResponse.toByteArray())
        
        // Mock successful delivery response
        val deliveryResponse = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.DELIVERY_COMMAND)
            .dataBytes(1.toByte(), 1.toByte())
            .build()
        mockCommunicator.queueResponse(deliveryResponse.toByteArray())
        
        // Mock successful status response
        val statusResponse = ProtocolFrameBuilder()
            .vmcHeader()
            .command(VmcCommands.QUERY_STATUS)
            .dataBytes(VmcErrorCodes.SUCCESS)
            .build()
        mockCommunicator.queueResponse(statusResponse.toByteArray())
    }
}
```

## Testing

### Unit Test Example

```kotlin
@Test
fun testWaterDispensing() = runTest {
    val mockCommunicator = MockSerialCommunicator()
    val sdk = VendingMachineSDKImpl(mockCommunicator)
    
    // Setup mock responses
    setupSuccessfulDispenseResponse(mockCommunicator)
    
    // Test
    sdk.connect()
    val result = sdk.dispenseWater(slot = 1)
    
    // Verify
    assertTrue(result.isSuccess)
    val dispenseResult = result.getOrNull()!!
    assertTrue(dispenseResult.success)
    assertEquals(1, dispenseResult.slot)
}
```

## Error Handling

### Common Error Scenarios

1. **Connection Failures**: Network/USB issues
2. **Hardware Errors**: Motor failure, sensor issues
3. **Protocol Errors**: Invalid responses, timeouts
4. **Validation Errors**: Invalid slot numbers

### Error Recovery Strategy

```kotlin
suspend fun handleError(error: Exception, slot: Int): Boolean {
    return when (error) {
        is VmcException.ConnectionException -> {
            Log.w("WaterFountain", "Connection lost, attempting reconnect...")
            sdk.disconnect()
            delay(1000)
            sdk.connect()
        }
        
        is VmcException.HardwareException -> {
            Log.w("WaterFountain", "Hardware error: ${error.errorCode}, clearing faults...")
            clearFaultsAndRetry(slot)
        }
        
        is VmcException.TimeoutException -> {
            Log.w("WaterFountain", "Operation timed out, retrying...")
            true // Will be retried by caller
        }
        
        else -> {
            Log.e("WaterFountain", "Unhandled error: ${error.message}")
            false
        }
    }
}
```

## Production Considerations

1. **Hardware Integration**: Replace MockSerialCommunicator with real USB/Serial implementation
2. **Permissions**: Add USB device permissions in AndroidManifest.xml
3. **Background Processing**: Use WorkManager for long-running operations
4. **Logging**: Implement proper logging with different levels for production
5. **Configuration**: Make timeouts and retry counts configurable
6. **Monitoring**: Add metrics and health checks for the SDK

This example provides a complete integration guide for the Water Fountain Hardware SDK, covering all essential operations and best practices.