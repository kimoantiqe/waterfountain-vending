package com.waterfountainmachine.app.hardware.sdk

/**
 * Slot Validator Utility
 * 
 * The vending machine controller supports 48 valid slots organized in 6 rows of 8 slots each:
 * - Row 1: Slots 1-8
 * - Row 2: Slots 11-18
 * - Row 3: Slots 21-28
 * - Row 4: Slots 31-38
 * - Row 5: Slots 41-48
 * - Row 6: Slots 51-58
 * 
 * Slots 9, 10, 19, 20, 29, 30, etc. are not valid.
 */
object SlotValidator {
    
    /**
     * All valid slot numbers (48 total slots)
     */
    val VALID_SLOTS = listOf(
        // Row 1
        1, 2, 3, 4, 5, 6, 7, 8,
        // Row 2
        11, 12, 13, 14, 15, 16, 17, 18,
        // Row 3
        21, 22, 23, 24, 25, 26, 27, 28,
        // Row 4
        31, 32, 33, 34, 35, 36, 37, 38,
        // Row 5
        41, 42, 43, 44, 45, 46, 47, 48,
        // Row 6
        51, 52, 53, 54, 55, 56, 57, 58
    )
    
    /**
     * Slot ranges organized by row (for easier UI display)
     */
    val SLOT_ROWS = listOf(
        1..8,    // Row 1
        11..18,  // Row 2
        21..28,  // Row 3
        31..38,  // Row 4
        41..48,  // Row 5
        51..58   // Row 6
    )
    
    /**
     * Check if a slot number is valid
     * @param slot Slot number to validate
     * @return true if slot is valid, false otherwise
     */
    fun isValidSlot(slot: Int): Boolean {
        return slot in VALID_SLOTS
    }
    
    /**
     * Get the row number (1-6) for a given slot
     * @param slot Slot number
     * @return Row number (1-6) or null if slot is invalid
     */
    fun getRowNumber(slot: Int): Int? {
        return when {
            slot in 1..8 -> 1
            slot in 11..18 -> 2
            slot in 21..28 -> 3
            slot in 31..38 -> 4
            slot in 41..48 -> 5
            slot in 51..58 -> 6
            else -> null
        }
    }
    
    /**
     * Get the column number (1-8) within a row for a given slot
     * @param slot Slot number
     * @return Column number (1-8) or null if slot is invalid
     */
    fun getColumnNumber(slot: Int): Int? {
        if (!isValidSlot(slot)) return null
        return ((slot - 1) % 10) + 1
    }
    
    /**
     * Get a human-readable position for a slot (e.g., "Row 1, Column 3")
     * @param slot Slot number
     * @return Position string or null if slot is invalid
     */
    fun getSlotPosition(slot: Int): String? {
        val row = getRowNumber(slot) ?: return null
        val col = getColumnNumber(slot) ?: return null
        return "Row $row, Column $col"
    }
    
    /**
     * Get all slots for a specific row
     * @param row Row number (1-6)
     * @return List of slots in that row, or empty list if row is invalid
     */
    fun getSlotsInRow(row: Int): List<Int> {
        return when (row) {
            1 -> listOf(1, 2, 3, 4, 5, 6, 7, 8)
            2 -> listOf(11, 12, 13, 14, 15, 16, 17, 18)
            3 -> listOf(21, 22, 23, 24, 25, 26, 27, 28)
            4 -> listOf(31, 32, 33, 34, 35, 36, 37, 38)
            5 -> listOf(41, 42, 43, 44, 45, 46, 47, 48)
            6 -> listOf(51, 52, 53, 54, 55, 56, 57, 58)
            else -> emptyList()
        }
    }
    
    /**
     * Validate slot and throw exception if invalid
     * @param slot Slot number to validate
     * @throws IllegalArgumentException if slot is invalid
     */
    fun validateSlotOrThrow(slot: Int) {
        require(isValidSlot(slot)) {
            "Invalid slot number: $slot. Valid slots are: 1-8, 11-18, 21-28, 31-38, 41-48, 51-58"
        }
    }
    
    /**
     * Get the nearest valid slot for an invalid slot number
     * @param slot Potentially invalid slot number
     * @return Nearest valid slot, or null if no reasonable match exists
     */
    fun getNearestValidSlot(slot: Int): Int? {
        if (isValidSlot(slot)) return slot
        
        // Try to find slot in same "row" (tens digit)
        val row = slot / 10
        
        return when {
            slot in 1..8 -> slot.coerceIn(1, 8)
            slot in 9..18 -> (slot - 10).coerceIn(11, 18)
            slot in 19..28 -> (slot - 10).coerceIn(21, 28)
            slot in 29..38 -> (slot - 10).coerceIn(31, 38)
            slot in 39..48 -> (slot - 10).coerceIn(41, 48)
            slot in 49..58 -> (slot - 10).coerceIn(51, 58)
            else -> null
        }
    }
}
