package com.waterfountainmachine.app.hardware.sdk

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Main SDK interface for VMC communication
 * This is the primary interface that applications should use
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
     * Get VMC device ID
     * @return Device ID string (15 characters) or null if failed
     */
    suspend fun getDeviceId(): Result<String>
    
    /**
     * Send delivery command to dispense water
     * @param slot Slot number (1-255)
     * @param quantity Quantity to dispense (typically 1)
     * @return Delivery response with slot and quantity confirmation
     */
    suspend fun sendDeliveryCommand(slot: Int, quantity: Int = 1): Result<VmcResponse.DeliveryResponse>
    
    /**
     * Query delivery status
     * @param slot Slot number that was used for delivery
     * @param quantity Expected quantity
     * @return Status response indicating success/failure
     */
    suspend fun queryDeliveryStatus(slot: Int, quantity: Int = 1): Result<VmcResponse.StatusResponse>
    
    /**
     * Clear any faults in the VMC
     * @return true if fault clearing successful
     */
    suspend fun clearFaults(): Result<Boolean>
    
    /**
     * Query VMC balance (for coin-operated machines)
     * @return Balance in cents (amount * 100)
     */
    suspend fun queryBalance(): Result<Int>
    
    /**
     * Send payment instruction to VMC
     * @param amount Payment amount in cents (will be sent as amount * 100)
     * @param paymentMethod Payment method (see PaymentMethods)
     * @param slot Slot number for delivery
     * @return Payment response indicating success/failure
     */
    suspend fun sendPaymentInstruction(amount: Int, paymentMethod: Byte, slot: Int): Result<Boolean>
    
    /**
     * Request coin change from VMC
     * @return true if coin change successful
     */
    suspend fun requestCoinChange(): Result<Boolean>
    
    /**
     * Cancel cashless payment transaction
     * @return true if cancellation successful
     */
    suspend fun cancelCashlessPayment(): Result<Boolean>
    
    /**
     * Send debit instruction to VMC payment panel
     * @param amount Amount to debit in cents
     * @return true if debit successful
     */
    suspend fun sendDebitInstruction(amount: Int): Result<Boolean>
    
    /**
     * Start age recognition process
     * @param requiredAge Minimum age requirement (1-99)
     * @return true if age recognition started successfully
     */
    suspend fun startAgeRecognition(requiredAge: Int): Result<Boolean>
    
    /**
     * Query coin change capability status
     * @return true if coins can be refunded, false if insufficient coins
     */
    suspend fun queryCoinChangeStatus(): Result<Boolean>
    
    /**
     * Query age verification status
     * @return true if age verification was successful
     */
    suspend fun queryAgeVerificationStatus(): Result<Boolean>
    
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
 * SDK Exception types
 */
sealed class VmcException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ConnectionException(message: String) : VmcException(message)
    class ProtocolException(message: String) : VmcException(message)
    class TimeoutException(message: String) : VmcException(message)
    class HardwareException(message: String, val errorCode: Byte) : VmcException(message)
}

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
        require(slot in 1..255) { "Slot must be between 1 and 255" }
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
        require(slot in 1..255) { "Slot must be between 1 and 255" }
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
                is VmcResponse.SimpleResponse -> response.success
                is VmcResponse.ErrorResponse -> throw VmcException.ProtocolException(response.message)
                else -> throw VmcException.ProtocolException("Unexpected response type")
            }
        }
    }

    override suspend fun queryBalance(): Result<Int> {
        return executeCommand(
            command = VmcCommandBuilder.queryBalance(),
            expectedResponseCommand = VmcCommands.QUERY_BALANCE
        ) { response ->
            when (response) {
                is VmcResponse.BalanceResponse -> response.balance
                is VmcResponse.ErrorResponse -> throw VmcException.ProtocolException(response.message)
                else -> throw VmcException.ProtocolException("Unexpected response type")
            }
        }
    }

    override suspend fun sendPaymentInstruction(amount: Int, paymentMethod: Byte, slot: Int): Result<Boolean> {
        require(slot in 1..255) { "Slot must be between 1 and 255" }
        require(amount >= 0) { "Amount must be non-negative" }
        
        return executeCommand(
            command = VmcCommandBuilder.paymentInstruction(amount, paymentMethod, slot.toByte()),
            expectedResponseCommand = VmcCommands.PAYMENT_INSTRUCTION
        ) { response ->
            when (response) {
                is VmcResponse.PaymentResponse -> response.success
                is VmcResponse.ErrorResponse -> throw VmcException.ProtocolException(response.message)
                else -> throw VmcException.ProtocolException("Unexpected response type")
            }
        }
    }

    override suspend fun requestCoinChange(): Result<Boolean> {
        return executeCommand(
            command = VmcCommandBuilder.coinChange(),
            expectedResponseCommand = VmcCommands.COIN_CHANGE
        ) { response ->
            when (response) {
                is VmcResponse.SimpleResponse -> response.success
                is VmcResponse.ErrorResponse -> throw VmcException.ProtocolException(response.message)
                else -> throw VmcException.ProtocolException("Unexpected response type")
            }
        }
    }

    override suspend fun cancelCashlessPayment(): Result<Boolean> {
        return executeCommand(
            command = VmcCommandBuilder.cashlessCancel(),
            expectedResponseCommand = VmcCommands.CASHLESS_CANCEL
        ) { response ->
            when (response) {
                is VmcResponse.SimpleResponse -> response.success
                is VmcResponse.ErrorResponse -> throw VmcException.ProtocolException(response.message)
                else -> throw VmcException.ProtocolException("Unexpected response type")
            }
        }
    }

    override suspend fun sendDebitInstruction(amount: Int): Result<Boolean> {
        require(amount >= 0) { "Amount must be non-negative" }
        
        return executeCommand(
            command = VmcCommandBuilder.debitInstruction(amount),
            expectedResponseCommand = VmcCommands.DEBIT_INSTRUCTION
        ) { response ->
            when (response) {
                is VmcResponse.SimpleResponse -> response.success
                is VmcResponse.ErrorResponse -> throw VmcException.ProtocolException(response.message)
                else -> throw VmcException.ProtocolException("Unexpected response type")
            }
        }
    }

    override suspend fun startAgeRecognition(requiredAge: Int): Result<Boolean> {
        require(requiredAge in 1..99) { "Required age must be between 1 and 99" }
        
        return executeCommand(
            command = VmcCommandBuilder.ageRecognition(requiredAge.toByte()),
            expectedResponseCommand = VmcCommands.AGE_RECOGNITION
        ) { response ->
            when (response) {
                is VmcResponse.SimpleResponse -> response.success
                is VmcResponse.ErrorResponse -> throw VmcException.ProtocolException(response.message)
                else -> throw VmcException.ProtocolException("Unexpected response type")
            }
        }
    }

    override suspend fun queryCoinChangeStatus(): Result<Boolean> {
        return executeCommand(
            command = VmcCommandBuilder.queryCoinChangeStatus(),
            expectedResponseCommand = VmcCommands.QUERY_COIN_CHANGE_STATUS
        ) { response ->
            when (response) {
                is VmcResponse.CoinChangeStatusResponse -> response.canRefund
                is VmcResponse.ErrorResponse -> throw VmcException.ProtocolException(response.message)
                else -> throw VmcException.ProtocolException("Unexpected response type")
            }
        }
    }

    override suspend fun queryAgeVerificationStatus(): Result<Boolean> {
        return executeCommand(
            command = VmcCommandBuilder.queryAgeVerificationStatus(),
            expectedResponseCommand = VmcCommands.QUERY_AGE_VERIFICATION
        ) { response ->
            when (response) {
                is VmcResponse.AgeVerificationResponse -> response.verified
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
