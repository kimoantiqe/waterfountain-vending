package com.waterfountainmachine.app.hardware.sdk

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Main SDK interface for VMC communication - Water Fountain Edition
 * This interface only includes functions needed for water dispensing operations
 */
interface VendingMachineSDK {
    
    /**
     * Initialize connection to VMC
     * @param config Serial configuration
     * @return true if connection successful
     */
    suspend fun connect(config: SerialConfig = SerialConfig()): Boolean
    
    /**
     * Disconnect from VMC
     */
    suspend fun disconnect()
    
    /**
     * Check if connected to VMC
     */
    fun isConnected(): Boolean
    
    /**
     * Get VMC device ID (15-byte string from API)
     * @return Device ID string or error
     */
    suspend fun getDeviceId(): Result<String>
    
    /**
     * Send delivery command to dispense water
     * @param slot Slot number - Valid slots: 1-8, 11-18, 21-28, 31-38, 41-48, 51-58 (48 total slots in 6 rows)
     * @param quantity Quantity to dispense (typically 1)
     * @return Delivery response with slot and quantity confirmation
     */
    suspend fun sendDeliveryCommand(slot: Int, quantity: Int = 1): Result<VmcResponse.DeliveryResponse>
    
    /**
     * Query delivery status after sending delivery command
     * @param slot Slot number that was used for delivery
     * @param quantity Expected quantity
     * @return Status response indicating success/failure with error codes
     */
    suspend fun queryDeliveryStatus(slot: Int, quantity: Int = 1): Result<VmcResponse.StatusResponse>
    
    /**
     * Clear any faults in the VMC using remove fault command
     * @return true if fault clearing successful
     */
    suspend fun clearFaults(): Result<Boolean>
    
    /**
     * Perform complete water dispensing operation
     * This is a high-level operation that:
     * 1. Sends delivery command
     * 2. Monitors status until completion
     * 3. Handles errors automatically
     * 
     * @param slot Slot number to dispense from
     * @return Final status of the operation
     */
    suspend fun dispenseWater(slot: Int): Result<WaterDispenseResult>
}

/**
 * Result of water dispensing operation
 */
data class WaterDispenseResult(
    val success: Boolean,
    val slot: Int,
    val errorCode: Byte? = null,
    val errorMessage: String? = null,
    val dispensingTimeMs: Long = 0
)



/**
 * Implementation of the VMC SDK
 */
class VendingMachineSDKImpl(
    private val serialCommunicator: SerialCommunicator,
    private val commandTimeoutMs: Long = 5000,
    private val statusPollingIntervalMs: Long = 500,
    private val maxStatusPollingAttempts: Int = 20
) : VendingMachineSDK {

    override suspend fun connect(config: SerialConfig): Boolean {
        return try {
            serialCommunicator.connect(config)
        } catch (e: Exception) {
            throw VmcException.ConnectionException("Failed to connect: ${e.message}")
        }
    }

    override suspend fun disconnect() {
        serialCommunicator.disconnect()
    }

    override fun isConnected(): Boolean {
        return serialCommunicator.isConnected()
    }

    override suspend fun getDeviceId(): Result<String> {
        return executeCommand(
            command = VmcCommandBuilder.getDeviceId(),
            expectedResponseCommand = VmcCommands.GET_DEVICE_ID
        ) { response ->
            when (response) {
                is VmcResponse.DeviceIdResponse -> response.deviceId
                is VmcResponse.ErrorResponse -> throw VmcException.ProtocolException(response.message)
                else -> throw VmcException.ProtocolException("Unexpected response type")
            }
        }
    }

    override suspend fun sendDeliveryCommand(slot: Int, quantity: Int): Result<VmcResponse.DeliveryResponse> {
        SlotValidator.validateSlotOrThrow(slot)
        require(quantity in 1..255) { "Quantity must be between 1 and 255" }
        
        return executeCommand(
            command = VmcCommandBuilder.deliveryCommand(slot.toByte(), quantity.toByte()),
            expectedResponseCommand = VmcCommands.DELIVERY_COMMAND
        ) { response ->
            when (response) {
                is VmcResponse.DeliveryResponse -> response
                is VmcResponse.ErrorResponse -> throw VmcException.ProtocolException(response.message)
                else -> throw VmcException.ProtocolException("Unexpected response type")
            }
        }
    }

    override suspend fun queryDeliveryStatus(slot: Int, quantity: Int): Result<VmcResponse.StatusResponse> {
        SlotValidator.validateSlotOrThrow(slot)
        require(quantity in 1..255) { "Quantity must be between 1 and 255" }
        
        return executeCommand(
            command = VmcCommandBuilder.queryStatus(slot.toByte(), quantity.toByte()),
            expectedResponseCommand = VmcCommands.QUERY_STATUS
        ) { response ->
            when (response) {
                is VmcResponse.StatusResponse -> response
                is VmcResponse.ErrorResponse -> throw VmcException.ProtocolException(response.message)
                else -> throw VmcException.ProtocolException("Unexpected response type")
            }
        }
    }

    override suspend fun clearFaults(): Result<Boolean> {
        return executeCommand(
            command = VmcCommandBuilder.removeFault(),
            expectedResponseCommand = VmcCommands.REMOVE_FAULT
        ) { response ->
            when (response) {
                is VmcResponse.SuccessResponse -> response.success
                is VmcResponse.ErrorResponse -> throw VmcException.ProtocolException(response.message)
                else -> throw VmcException.ProtocolException("Unexpected response type")
            }
        }
    }




    override suspend fun dispenseWater(slot: Int): Result<WaterDispenseResult> {
        val startTime = System.currentTimeMillis()
        
        return try {
            // Step 1: Send delivery command
            val deliveryResult = sendDeliveryCommand(slot)
            if (deliveryResult.isFailure) {
                return Result.success(
                    WaterDispenseResult(
                        success = false,
                        slot = slot,
                        errorMessage = "Failed to send delivery command: ${deliveryResult.exceptionOrNull()?.message}"
                    )
                )
            }

            // Step 2: Poll status until completion
            var attempts = 0
            while (attempts < maxStatusPollingAttempts) {
                kotlinx.coroutines.delay(statusPollingIntervalMs)
                
                val statusResult = queryDeliveryStatus(slot, 1)
                if (statusResult.isFailure) {
                    attempts++
                    continue
                }
                
                val status = statusResult.getOrThrow()
                val dispensingTime = System.currentTimeMillis() - startTime
                
                return when {
                    status.success -> {
                        Result.success(
                            WaterDispenseResult(
                                success = true,
                                slot = slot,
                                dispensingTimeMs = dispensingTime
                            )
                        )
                    }
                    status.errorCode != null -> {
                        val errorMessage = when (status.errorCode) {
                            VmcErrorCodes.MOTOR_FAILURE -> "Motor failure in slot $slot"
                            VmcErrorCodes.OPTICAL_EYE_FAILURE -> "Optical sensor failure in slot $slot"
                            else -> "Unknown error: ${status.errorCode}"
                        }
                        Result.success(
                            WaterDispenseResult(
                                success = false,
                                slot = slot,
                                errorCode = status.errorCode,
                                errorMessage = errorMessage,
                                dispensingTimeMs = dispensingTime
                            )
                        )
                    }
                    else -> {
                        // Still in progress, continue polling
                        attempts++
                        continue
                    }
                }
            }
            
            // Timeout - operation took too long
            Result.success(
                WaterDispenseResult(
                    success = false,
                    slot = slot,
                    errorMessage = "Dispensing operation timed out after ${maxStatusPollingAttempts * statusPollingIntervalMs}ms",
                    dispensingTimeMs = System.currentTimeMillis() - startTime
                )
            )
            
        } catch (e: Exception) {
            Result.success(
                WaterDispenseResult(
                    success = false,
                    slot = slot,
                    errorMessage = "Dispensing failed: ${e.message}",
                    dispensingTimeMs = System.currentTimeMillis() - startTime
                )
            )
        }
    }

    /**
     * Execute a command and return the parsed response
     */
    private suspend fun <T> executeCommand(
        command: ProtocolFrame,
        expectedResponseCommand: Byte,
        parser: (VmcResponse) -> T
    ): Result<T> {
        return try {
            if (!isConnected()) {
                throw VmcException.ConnectionException("Not connected to VMC")
            }

            // Send command
            val sendSuccess = withTimeout(commandTimeoutMs) {
                serialCommunicator.sendData(command.toByteArray())
            }
            
            if (!sendSuccess) {
                throw VmcException.ProtocolException("Failed to send command")
            }

            // Read response
            val responseBytes = withTimeout(commandTimeoutMs) {
                serialCommunicator.readData(commandTimeoutMs)
            } ?: throw VmcException.TimeoutException("No response received within timeout")

            // Parse response frame
            val responseFrame = ProtocolFrameParser.parse(responseBytes)
                ?: throw VmcException.ProtocolException("Invalid response frame")

            // Validate response
            if (responseFrame.command != expectedResponseCommand) {
                throw VmcException.ProtocolException(
                    "Unexpected response command: expected ${expectedResponseCommand.toString(16)}, " +
                    "got ${responseFrame.command.toString(16)}"
                )
            }

            if (responseFrame.header != ProtocolFrame.VMC_HEADER) {
                throw VmcException.ProtocolException("Invalid response header")
            }

            // Parse response data
            val response = VmcResponseParser.parseResponse(responseFrame)
            val result = parser(response)
            
            Result.success(result)
            
        } catch (e: TimeoutCancellationException) {
            Result.failure(VmcException.TimeoutException("Command timed out after ${commandTimeoutMs}ms"))
        } catch (e: VmcException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(VmcException.ProtocolException("Unexpected error: ${e.message}"))
        }
    }
}
