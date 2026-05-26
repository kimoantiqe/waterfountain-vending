package com.waterfountainmachine.app.core.slot

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Unit tests for [SlotInventoryManager].
 *
 * Uses Robolectric so we get a real [android.content.SharedPreferences]
 * backed by an in-memory file, exercising the same `editor.commit()` /
 * `apply()` paths as production. No mocking of SharedPreferences keeps
 * the test honest about atomicity semantics in [replaceAllSlots].
 *
 * We deliberately install a stock [Application] (not the production
 * `WaterFountainApplication`) because the latter wires Hilt + Firebase +
 * EncryptedSharedPreferences in `onCreate`, none of which exist under
 * the Robolectric JVM and would explode with an `AndroidKeyStore not
 * found` error before any test code runs.
 *
 * Each test gets a fresh manager via [SlotInventoryManager.clearAll]
 * since the class is a process-scoped singleton.
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [33], application = Application::class)
class SlotInventoryManagerTest {

    private lateinit var manager: SlotInventoryManager

    @Before
    fun setup() {
        manager = SlotInventoryManager.getInstance(ApplicationProvider.getApplicationContext())
        manager.clearAll()
    }

    @After
    fun tearDown() {
        manager.clearAll()
    }

    // ---------- getSlotInventory ----------

    @Test
    fun `getSlotInventory returns null for never-written slot`() {
        assertThat(manager.getSlotInventory(1)).isNull()
    }

    @Test
    fun `getSlotInventory returns null for invalid slot number`() {
        assertThat(manager.getSlotInventory(999)).isNull()
        assertThat(manager.getSlotInventory(-1)).isNull()
        assertThat(manager.getSlotInventory(0)).isNull()
    }

    @Test
    fun `updateSlotInventory then getSlotInventory round-trips all fields`() {
        manager.updateSlotInventory(
            slot = 1,
            remainingBottles = 5,
            capacity = 7,
            campaignId = "camp-1",
            canDesignId = "design-1",
            canDesignName = "Test Design",
            status = SlotInventoryManager.SlotStatus.ACTIVE
        )

        val inv = manager.getSlotInventory(1)
        assertThat(inv).isNotNull()
        assertThat(inv!!.slot).isEqualTo(1)
        assertThat(inv.remainingBottles).isEqualTo(5)
        assertThat(inv.capacity).isEqualTo(7)
        assertThat(inv.campaignId).isEqualTo("camp-1")
        assertThat(inv.canDesignId).isEqualTo("design-1")
        assertThat(inv.canDesignName).isEqualTo("Test Design")
        assertThat(inv.status).isEqualTo(SlotInventoryManager.SlotStatus.ACTIVE)
        assertThat(inv.lastUpdated).isGreaterThan(0L)
    }

    @Test
    fun `updateSlotInventory ignores invalid slot`() {
        manager.updateSlotInventory(slot = 999, remainingBottles = 5)
        assertThat(manager.getSlotInventory(999)).isNull()
    }

    // ---------- decrementInventory ----------

    @Test
    fun `decrementInventory drops bottle count by one`() {
        manager.updateSlotInventory(slot = 1, remainingBottles = 3, capacity = 7)

        assertThat(manager.decrementInventory(1)).isTrue()
        assertThat(manager.getSlotInventory(1)!!.remainingBottles).isEqualTo(2)
    }

    @Test
    fun `decrementInventory flips status to EMPTY when last bottle is sold`() {
        manager.updateSlotInventory(slot = 1, remainingBottles = 1)

        manager.decrementInventory(1)

        val inv = manager.getSlotInventory(1)!!
        assertThat(inv.remainingBottles).isEqualTo(0)
        assertThat(inv.status).isEqualTo(SlotInventoryManager.SlotStatus.EMPTY)
    }

    @Test
    fun `decrementInventory returns false for non-existent slot`() {
        assertThat(manager.decrementInventory(1)).isFalse()
    }

    @Test
    fun `decrementInventory returns false for already-empty slot`() {
        manager.updateSlotInventory(slot = 1, remainingBottles = 0)
        assertThat(manager.decrementInventory(1)).isFalse()
    }

    // ---------- isSlotAvailable / getAvailableSlots ----------

    @Test
    fun `isSlotAvailable returns false for empty, disabled, or maintenance slots`() {
        manager.updateSlotInventory(slot = 1, remainingBottles = 0)
        manager.updateSlotInventory(
            slot = 2,
            remainingBottles = 5,
            status = SlotInventoryManager.SlotStatus.DISABLED
        )
        manager.updateSlotInventory(
            slot = 3,
            remainingBottles = 5,
            status = SlotInventoryManager.SlotStatus.MAINTENANCE
        )
        manager.updateSlotInventory(
            slot = 4,
            remainingBottles = 5,
            status = SlotInventoryManager.SlotStatus.ACTIVE
        )

        assertThat(manager.isSlotAvailable(1)).isFalse() // empty
        assertThat(manager.isSlotAvailable(2)).isFalse() // disabled
        assertThat(manager.isSlotAvailable(3)).isFalse() // maintenance
        assertThat(manager.isSlotAvailable(4)).isTrue()
        assertThat(manager.isSlotAvailable(5)).isFalse() // never configured
    }

    @Test
    fun `getAvailableSlots lists only ACTIVE slots with stock`() {
        manager.updateSlotInventory(slot = 1, remainingBottles = 5)
        manager.updateSlotInventory(slot = 2, remainingBottles = 0)
        manager.updateSlotInventory(
            slot = 3,
            remainingBottles = 5,
            status = SlotInventoryManager.SlotStatus.DISABLED
        )

        assertThat(manager.getAvailableSlots()).containsExactly(1)
    }

    // ---------- replaceAllSlots (atomic snapshot) ----------

    @Test
    fun `replaceAllSlots overwrites the entire cache with the snapshot`() {
        manager.updateSlotInventory(slot = 1, remainingBottles = 99, capacity = 99)
        manager.updateSlotInventory(slot = 2, remainingBottles = 99)

        manager.replaceAllSlots(
            listOf(
                SlotInventoryManager.SlotInventory(
                    slot = 1,
                    remainingBottles = 3,
                    capacity = 7,
                    campaignId = "c1",
                    canDesignId = "d1",
                    canDesignName = "n1",
                    status = SlotInventoryManager.SlotStatus.ACTIVE,
                    lastUpdated = 0L // ignored — manager stamps `now`
                )
            )
        )

        val s1 = manager.getSlotInventory(1)!!
        assertThat(s1.remainingBottles).isEqualTo(3)
        assertThat(s1.capacity).isEqualTo(7)
        assertThat(s1.campaignId).isEqualTo("c1")
        // Slot 2 was in the old cache but not in the snapshot — it must be evicted.
        assertThat(manager.getSlotInventory(2)).isNull()
    }

    @Test
    fun `replaceAllSlots bumps lastSyncTimestamp in the same commit`() {
        val before = manager.getLastSyncTimestamp()
        manager.replaceAllSlots(emptyList())
        assertThat(manager.getLastSyncTimestamp()).isGreaterThan(before)
    }

    @Test
    fun `replaceAllSlots with empty list clears every slot but keeps lastSync`() {
        manager.updateSlotInventory(slot = 1, remainingBottles = 5)
        manager.replaceAllSlots(emptyList())

        assertThat(manager.getSlotInventory(1)).isNull()
        assertThat(manager.getLastSyncTimestamp()).isGreaterThan(0L)
    }

    // ---------- analytics helpers ----------

    @Test
    fun `getLowInventorySlots flags slots under 20 percent capacity`() {
        // capacity 10, threshold = 2
        manager.updateSlotInventory(slot = 1, remainingBottles = 1, capacity = 10) // low
        manager.updateSlotInventory(slot = 2, remainingBottles = 2, capacity = 10) // low (== threshold)
        manager.updateSlotInventory(slot = 3, remainingBottles = 3, capacity = 10) // not low
        manager.updateSlotInventory(slot = 4, remainingBottles = 0, capacity = 10) // empty != low

        assertThat(manager.getLowInventorySlots()).containsExactly(1, 2)
    }

    @Test
    fun `getEmptySlots lists only zero-bottle slots`() {
        manager.updateSlotInventory(slot = 1, remainingBottles = 0)
        manager.updateSlotInventory(slot = 2, remainingBottles = 3)

        assertThat(manager.getEmptySlots()).containsExactly(1)
    }

    @Test
    fun `getTotalInventory sums remainingBottles across all configured slots`() {
        manager.updateSlotInventory(slot = 1, remainingBottles = 3)
        manager.updateSlotInventory(slot = 2, remainingBottles = 4)
        manager.updateSlotInventory(slot = 3, remainingBottles = 0)

        assertThat(manager.getTotalInventory()).isEqualTo(7)
    }

    @Test
    fun `getInventoryFillRate returns zero when no slots configured`() {
        assertThat(manager.getInventoryFillRate()).isEqualTo(0f)
    }

    @Test
    fun `getInventoryFillRate computes percent of capacity`() {
        manager.updateSlotInventory(slot = 1, remainingBottles = 5, capacity = 10)
        manager.updateSlotInventory(slot = 2, remainingBottles = 5, capacity = 10)
        // 10 / 20 = 50%
        assertThat(manager.getInventoryFillRate()).isEqualTo(50f)
    }

    @Test
    fun `getInventorySummary returns expected metric keys`() {
        manager.updateSlotInventory(slot = 1, remainingBottles = 2, capacity = 10)

        val summary = manager.getInventorySummary()
        assertThat(summary.keys).containsExactly(
            "totalInventory",
            "totalCapacity",
            "fillRate",
            "emptySlots",
            "lowInventorySlots",
            "availableSlots"
        )
    }

    // ---------- SlotStatus.fromString ----------

    @Test
    fun `SlotStatus fromString is case-insensitive`() {
        assertThat(SlotInventoryManager.SlotStatus.fromString("active"))
            .isEqualTo(SlotInventoryManager.SlotStatus.ACTIVE)
        assertThat(SlotInventoryManager.SlotStatus.fromString("EMPTY"))
            .isEqualTo(SlotInventoryManager.SlotStatus.EMPTY)
        assertThat(SlotInventoryManager.SlotStatus.fromString("Disabled"))
            .isEqualTo(SlotInventoryManager.SlotStatus.DISABLED)
    }

    @Test
    fun `SlotStatus fromString falls back to ACTIVE for unknown values`() {
        assertThat(SlotInventoryManager.SlotStatus.fromString("garbage"))
            .isEqualTo(SlotInventoryManager.SlotStatus.ACTIVE)
        assertThat(SlotInventoryManager.SlotStatus.fromString(""))
            .isEqualTo(SlotInventoryManager.SlotStatus.ACTIVE)
    }
}
