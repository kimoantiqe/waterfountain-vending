package com.waterfountainmachine.app.core.backend

import com.google.common.truth.Truth.assertThat
import com.waterfountainmachine.app.core.slot.SlotInventoryManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Unit tests for [BackendSlotService] companion helpers.
 *
 * Same pattern as [BackendMachineServiceTest]: extract the pure logic
 * (retry + response parsing) and test it without bringing up Firebase
 * or Robolectric. The suspend functions themselves are integration-level
 * because they couple to final FirebaseFunctions classes and live wire
 * I/O — those belong in instrumented tests.
 */
class BackendSlotServiceTest {

    // ---------- retryOperation ----------

    @Test
    fun `retryOperation returns immediately on success`() = runTest {
        var attempts = 0
        val result = BackendSlotService.retryOperation(
            operation = {
                attempts++
                "ok"
            },
            operationName = "test",
            delayMillis = { /* no wait in tests */ }
        )

        assertThat(result).isEqualTo("ok")
        assertThat(attempts).isEqualTo(1)
    }

    @Test
    fun `retryOperation succeeds on the second attempt after one transient failure`() = runTest {
        var attempts = 0
        val result = BackendSlotService.retryOperation(
            operation = {
                attempts++
                if (attempts < 2) throw RuntimeException("transient")
                "ok"
            },
            operationName = "test",
            delayMillis = { /* no wait */ }
        )

        assertThat(result).isEqualTo("ok")
        assertThat(attempts).isEqualTo(2)
    }

    @Test
    fun `retryOperation gives up after MAX_RETRIES and rethrows the last exception`() = runTest {
        var attempts = 0
        val thrown = runCatching {
            BackendSlotService.retryOperation<String>(
                operation = {
                    attempts++
                    throw RuntimeException("attempt $attempts")
                },
                operationName = "test",
                delayMillis = { /* no wait */ }
            )
        }.exceptionOrNull()

        assertThat(attempts).isEqualTo(BackendSlotService.MAX_RETRIES)
        assertThat(thrown).hasMessageThat().contains("attempt 3")
    }

    @Test
    fun `retryOperation uses linear backoff between attempts`() = runTest {
        val delays = mutableListOf<Long>()
        runCatching {
            BackendSlotService.retryOperation<String>(
                operation = { throw RuntimeException("nope") },
                operationName = "test",
                delayMillis = { delays.add(it) }
            )
        }
        @OptIn(ExperimentalCoroutinesApi::class)
        advanceUntilIdle()

        // 3 attempts -> 2 delays at 1x and 2x RETRY_DELAY_MS.
        assertThat(delays).containsExactly(
            BackendSlotService.RETRY_DELAY_MS,
            BackendSlotService.RETRY_DELAY_MS * 2
        ).inOrder()
    }

    // ---------- parseSlotInventoryList ----------

    @Test
    fun `parseSlotInventoryList parses a well-formed payload`() {
        val now = 1700000000000L
        val data = listOf(
            mapOf(
                "slot" to 1,
                "remainingBottles" to 5,
                "capacity" to 7,
                "campaignId" to "camp-1",
                "canDesignId" to "design-1",
                "canDesignName" to "Cola",
                "status" to "active"
            ),
            mapOf(
                "slot" to 2,
                "remainingBottles" to 0,
                "capacity" to 7,
                "status" to "empty"
            )
        )

        val slots = BackendSlotService.parseSlotInventoryList(data, nowMillis = now)

        assertThat(slots).hasSize(2)
        assertThat(slots[0].slot).isEqualTo(1)
        assertThat(slots[0].remainingBottles).isEqualTo(5)
        assertThat(slots[0].canDesignName).isEqualTo("Cola")
        assertThat(slots[0].status).isEqualTo(SlotInventoryManager.SlotStatus.ACTIVE)
        assertThat(slots[0].lastUpdated).isEqualTo(now)
        assertThat(slots[1].status).isEqualTo(SlotInventoryManager.SlotStatus.EMPTY)
    }

    @Test
    fun `parseSlotInventoryList returns empty list for non-list payload`() {
        assertThat(BackendSlotService.parseSlotInventoryList(null)).isEmpty()
        assertThat(BackendSlotService.parseSlotInventoryList("not-a-list")).isEmpty()
        assertThat(BackendSlotService.parseSlotInventoryList(mapOf("a" to 1))).isEmpty()
    }

    @Test
    fun `parseSlotInventoryList silently drops entries missing slot number`() {
        val data = listOf(
            mapOf("slot" to 1, "remainingBottles" to 5),
            mapOf("remainingBottles" to 99), // no slot -> dropped
            mapOf("slot" to "abc"), // non-numeric slot -> dropped
            mapOf("slot" to 3)
        )

        val slots = BackendSlotService.parseSlotInventoryList(data)
        assertThat(slots.map { it.slot }).containsExactly(1, 3).inOrder()
    }

    @Test
    fun `parseSlotInventoryList defaults capacity to 7 when missing`() {
        val data = listOf(mapOf("slot" to 1))
        val slot = BackendSlotService.parseSlotInventoryList(data).single()
        assertThat(slot.capacity).isEqualTo(7)
        assertThat(slot.remainingBottles).isEqualTo(0)
    }

    @Test
    fun `parseSlotInventoryList falls back to ACTIVE for unknown status`() {
        val data = listOf(mapOf("slot" to 1, "status" to "totally-bogus"))
        val slot = BackendSlotService.parseSlotInventoryList(data).single()
        assertThat(slot.status).isEqualTo(SlotInventoryManager.SlotStatus.ACTIVE)
    }

    // ---------- parseVendEventResult ----------

    @Test
    fun `parseVendEventResult extracts all attribution fields`() {
        val data = mapOf(
            "eventId" to "evt-1",
            "campaignId" to "c1",
            "canDesignId" to "d1",
            "advertiserId" to "a1",
            "machineName" to "M1",
            "campaignName" to "Cola Summer",
            "canDesignName" to "Cola 330ml",
            "advertiserName" to "CocaCola Inc"
        )

        val r = BackendSlotService.parseVendEventResult(data)

        assertThat(r.eventId).isEqualTo("evt-1")
        assertThat(r.campaignName).isEqualTo("Cola Summer")
        assertThat(r.advertiserName).isEqualTo("CocaCola Inc")
    }

    @Test
    fun `parseVendEventResult returns empty eventId when response is not a Map`() {
        val r = BackendSlotService.parseVendEventResult(null)
        assertThat(r.eventId).isEqualTo("")
        assertThat(r.campaignId).isNull()
    }

    @Test
    fun `parseVendEventResult treats missing eventId as empty string`() {
        val r = BackendSlotService.parseVendEventResult(
            mapOf("campaignId" to "c1") // no eventId
        )
        assertThat(r.eventId).isEqualTo("")
        assertThat(r.campaignId).isEqualTo("c1")
    }
}
