package com.waterfountainmachine.app.core.hardware.sdk

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [SlotValidator]. The 48-slot grid skips columns 9 and 10
 * of every row -- a real hardware constraint. Encoding the layout in
 * tests guards against someone "tidying up" the validator into a simple
 * mod-10 check.
 */
class SlotValidatorTest {

    @Test
    fun `VALID_SLOTS has exactly 48 entries`() {
        assertThat(SlotValidator.VALID_SLOTS).hasSize(48)
        assertThat(SlotValidator.VALID_SLOTS.toSet()).hasSize(48) // no duplicates
    }

    @Test
    fun `SLOT_ROWS covers six rows of eight`() {
        assertThat(SlotValidator.SLOT_ROWS).hasSize(6)
        SlotValidator.SLOT_ROWS.forEach { range ->
            assertThat(range.last - range.first + 1).isEqualTo(8)
        }
    }

    @Test
    fun `isValidSlot accepts first and last slot of every row`() {
        val rowBoundaries = listOf(
            1 to 8, 11 to 18, 21 to 28, 31 to 38, 41 to 48, 51 to 58,
        )
        rowBoundaries.forEach { (first, last) ->
            assertThat(SlotValidator.isValidSlot(first)).isTrue()
            assertThat(SlotValidator.isValidSlot(last)).isTrue()
        }
    }

    @Test
    fun `isValidSlot rejects skipped columns 9 and 10 of every row`() {
        val skipped = listOf(9, 10, 19, 20, 29, 30, 39, 40, 49, 50)
        skipped.forEach { slot ->
            assertThat(SlotValidator.isValidSlot(slot)).isFalse()
        }
    }

    @Test
    fun `isValidSlot rejects out-of-range slots`() {
        listOf(0, -1, 59, 60, 100, Int.MAX_VALUE, Int.MIN_VALUE).forEach { slot ->
            assertThat(SlotValidator.isValidSlot(slot)).isFalse()
        }
    }

    @Test
    fun `getRowNumber returns the correct row for each valid slot`() {
        assertThat(SlotValidator.getRowNumber(1)).isEqualTo(1)
        assertThat(SlotValidator.getRowNumber(8)).isEqualTo(1)
        assertThat(SlotValidator.getRowNumber(11)).isEqualTo(2)
        assertThat(SlotValidator.getRowNumber(28)).isEqualTo(3)
        assertThat(SlotValidator.getRowNumber(38)).isEqualTo(4)
        assertThat(SlotValidator.getRowNumber(48)).isEqualTo(5)
        assertThat(SlotValidator.getRowNumber(51)).isEqualTo(6)
        assertThat(SlotValidator.getRowNumber(58)).isEqualTo(6)
    }

    @Test
    fun `getRowNumber returns null for invalid slots including skipped columns`() {
        listOf(0, 9, 10, 19, 30, 59, -5, 100).forEach { slot ->
            assertThat(SlotValidator.getRowNumber(slot)).isNull()
        }
    }

    @Test
    fun `getColumnNumber returns 1-8 for valid slots`() {
        assertThat(SlotValidator.getColumnNumber(1)).isEqualTo(1)
        assertThat(SlotValidator.getColumnNumber(8)).isEqualTo(8)
        assertThat(SlotValidator.getColumnNumber(11)).isEqualTo(1)
        assertThat(SlotValidator.getColumnNumber(28)).isEqualTo(8)
        assertThat(SlotValidator.getColumnNumber(35)).isEqualTo(5)
        assertThat(SlotValidator.getColumnNumber(58)).isEqualTo(8)
    }

    @Test
    fun `getColumnNumber returns null for invalid slots`() {
        listOf(9, 10, 0, 60, -1).forEach { slot ->
            assertThat(SlotValidator.getColumnNumber(slot)).isNull()
        }
    }

    @Test
    fun `getSlotPosition formats Row X, Column Y for valid slots`() {
        assertThat(SlotValidator.getSlotPosition(1)).isEqualTo("Row 1, Column 1")
        assertThat(SlotValidator.getSlotPosition(28)).isEqualTo("Row 3, Column 8")
        assertThat(SlotValidator.getSlotPosition(35)).isEqualTo("Row 4, Column 5")
    }

    @Test
    fun `getSlotPosition returns null for invalid slots`() {
        assertThat(SlotValidator.getSlotPosition(9)).isNull()
        assertThat(SlotValidator.getSlotPosition(0)).isNull()
    }

    @Test
    fun `getSlotsInRow returns the correct eight slots for rows 1 through 6`() {
        assertThat(SlotValidator.getSlotsInRow(1)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8).inOrder()
        assertThat(SlotValidator.getSlotsInRow(2)).containsExactly(11, 12, 13, 14, 15, 16, 17, 18).inOrder()
        assertThat(SlotValidator.getSlotsInRow(6)).containsExactly(51, 52, 53, 54, 55, 56, 57, 58).inOrder()
    }

    @Test
    fun `getSlotsInRow returns empty list for out-of-range rows`() {
        assertThat(SlotValidator.getSlotsInRow(0)).isEmpty()
        assertThat(SlotValidator.getSlotsInRow(7)).isEmpty()
        assertThat(SlotValidator.getSlotsInRow(-1)).isEmpty()
    }

    @Test
    fun `validateSlotOrThrow accepts valid slots`() {
        listOf(1, 8, 18, 28, 38, 48, 58).forEach { slot ->
            SlotValidator.validateSlotOrThrow(slot) // must not throw
        }
    }

    @Test
    fun `validateSlotOrThrow rejects invalid slots with a helpful message`() {
        val ex = runCatching { SlotValidator.validateSlotOrThrow(9) }.exceptionOrNull()
        assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(ex!!.message).contains("9")
        assertThat(ex.message).contains("Valid slots")
    }

    @Test
    fun `getNearestValidSlot returns the input for already-valid slots`() {
        listOf(1, 5, 18, 28, 35, 48, 58).forEach { slot ->
            assertThat(SlotValidator.getNearestValidSlot(slot)).isEqualTo(slot)
        }
    }

    @Test
    fun `getNearestValidSlot snaps an invalid skipped slot to the next row's first slot`() {
        // The implementation moves invalid slots FORWARD: skipped slots 9
        // and 10 (row 1's holes) map to 11 (row 2 col 1); 19 and 20 to 21
        // (row 3 col 1); and so on. Pinning this here so a future
        // refactor cannot quietly redirect them backward without a
        // deliberate test update.
        assertThat(SlotValidator.getNearestValidSlot(9)).isEqualTo(11)
        assertThat(SlotValidator.getNearestValidSlot(10)).isEqualTo(11)
        assertThat(SlotValidator.getNearestValidSlot(19)).isEqualTo(21)
        assertThat(SlotValidator.getNearestValidSlot(20)).isEqualTo(21)
        assertThat(SlotValidator.getNearestValidSlot(29)).isEqualTo(31)
        assertThat(SlotValidator.getNearestValidSlot(30)).isEqualTo(31)
        assertThat(SlotValidator.getNearestValidSlot(39)).isEqualTo(41)
        assertThat(SlotValidator.getNearestValidSlot(40)).isEqualTo(41)
        assertThat(SlotValidator.getNearestValidSlot(49)).isEqualTo(51)
        assertThat(SlotValidator.getNearestValidSlot(50)).isEqualTo(51)
    }

    @Test
    fun `getNearestValidSlot returns null for slots outside the grid`() {
        listOf(0, -1, 59, 60, 100).forEach { slot ->
            assertThat(SlotValidator.getNearestValidSlot(slot)).isNull()
        }
    }
}
