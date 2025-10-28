package com.waterfountainmachine.app.hardware.sdk

/**
 * Interface for vending machine adapter implementations.
 * Allows for polymorphic behavior between real hardware and mock implementations.
 * 
 * Note: The vendor SDK operates on a per-operation connection model.
 * Each dispense operation opens and closes the serial port automatically.
 * There is no persistent connection to maintain.
 */
interface IVendingMachineAdapter {
    
    /**
     * Initialize the adapter and verify hardware is available.
     * For vendor SDK: validates serial port access (/dev/ttyS0)
     * For mock: initializes mock state
     * 
     * @return Result.success if hardware is accessible, Result.failure otherwise
     */
    fun initialize(): Result<Unit>
    
    /**
     * Check if the adapter is ready to perform operations.
     * For vendor SDK: checks if serial port is accessible
     * For mock: always returns true
     * 
     * @return true if adapter is ready for operations
     */
    fun isReady(): Boolean
    
    /**
     * Dispense water from the specified slot.
     * 
     * This operation:
     * - Opens serial connection (vendor SDK does this automatically)
     * - Sends dispense command to VMC
     * - Waits for completion callback
     * - Closes serial connection (vendor SDK does this automatically)
     * 
     * @param slot The slot number to dispense from
     * @return Result containing WaterDispenseResult with success/failure info
     */
    suspend fun dispenseWater(slot: Int): Result<WaterDispenseResult>
    
    /**
     * Shutdown the adapter and cleanup resources.
     * Called when app is closing or switching adapters.
     */
    fun shutdown()
    
    /**
     * Get hardware information for diagnostics.
     * 
     * @return Map of hardware details (adapter type, serial port, baud rate, etc)
     */
    fun getHardwareInfo(): Map<String, String>
}
