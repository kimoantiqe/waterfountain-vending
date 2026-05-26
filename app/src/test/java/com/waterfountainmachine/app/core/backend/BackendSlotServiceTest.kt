package com.waterfountainmachine.app.core.backend

import com.google.common.truth.Truth.assertThat
import com.google.firebase.functions.FirebaseFunctionsException
import com.waterfountainmachine.app.core.slot.SlotInventoryManager
import io.mockk.mockk
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

    // ---------- retryOperation: failure-mode edge cases ----------

    @Test
    fun `retryOperation preserves the original exception type through the retry loop`() = runTest {
        // The vend path downcasts the rethrown exception to
        // FirebaseFunctionsException for daily-limit translation. If retry
        // ever wraps/replaces the exception, that downcast breaks silently.
        class CustomFailure(message: String) : Exception(message)

        val thrown = runCatching {
            BackendSlotService.retryOperation<String>(
                operation = { throw CustomFailure("boom") },
                operationName = "test",
                delayMillis = { /* no wait */ }
            )
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(CustomFailure::class.java)
        assertThat(thrown).hasMessageThat().isEqualTo("boom")
    }

    @Test
    fun `retryOperation rethrows the LAST exception, not the first`() = runTest {
        var attempts = 0
        val thrown = runCatching {
            BackendSlotService.retryOperation<String>(
                operation = {
                    attempts++
                    throw RuntimeException("attempt $attempts of 3")
                },
                operationName = "test",
                delayMillis = { /* no wait */ }
            )
        }.exceptionOrNull()

        // The last attempt is the one that should surface — important for
        // diagnostics so the log shows the most recent failure, not a
        // stale one from minutes ago.
        assertThat(thrown).hasMessageThat().contains("attempt 3 of 3")
    }

    // ---------- parseSlotInventoryList: partial-failure semantics ----------

    @Test
    fun `parseSlotInventoryList returns 47 valid slots when 1 of 48 entries is malformed`() {
        // Production realism: the device pulls all 48 slots from the
        // getSlotInventory callable. If the backend ever ships ONE bad row
        // (e.g., a schema migration in flight), we still want the other 47
        // to render — never an empty kiosk.
        val data = buildList {
            for (i in 1..48) {
                if (i == 24) {
                    // Slot 24 is malformed: slot field missing entirely.
                    add(mapOf("remainingBottles" to 5, "capacity" to 7))
                } else {
                    add(mapOf("slot" to i, "remainingBottles" to 5, "capacity" to 7))
                }
            }
        }

        val slots = BackendSlotService.parseSlotInventoryList(data)

        assertThat(slots).hasSize(47)
        assertThat(slots.map { it.slot }).doesNotContain(24)
        // The slots either side of the bad row are unaffected.
        assertThat(slots.map { it.slot }).containsAtLeast(23, 25)
    }

    // ---------- mapVendFirebaseException ----------

    @Test
    fun `mapVendFirebaseException translates RESOURCE_EXHAUSTED to DailyLimitReachedException`() {
        // FirebaseFunctionsException has a package-private constructor, so
        // we mock it for identity comparisons only — the helper takes the
        // (code, message, original) triple split out for exactly this
        // testability reason.
        val original = mockk<FirebaseFunctionsException>(relaxed = true)

        val result = BackendSlotService.mapVendFirebaseException(
            FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED,
            "limit",
            original
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .isInstanceOf(BackendSlotService.DailyLimitReachedException::class.java)
        assertThat(result.exceptionOrNull()).hasMessageThat().isEqualTo("limit")
    }

    @Test
    fun `mapVendFirebaseException uses default message when RESOURCE_EXHAUSTED message is null`() {
        val original = mockk<FirebaseFunctionsException>(relaxed = true)

        val result = BackendSlotService.mapVendFirebaseException(
            FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED,
            null,
            original
        )

        assertThat(result.exceptionOrNull())
            .isInstanceOf(BackendSlotService.DailyLimitReachedException::class.java)
        assertThat(result.exceptionOrNull()).hasMessageThat().isEqualTo("Daily limit reached")
    }

    @Test
    fun `mapVendFirebaseException surfaces UNAUTHENTICATED as the original Firebase exception`() {
        // Non-rate-limit codes must bubble up as the original
        // FirebaseFunctionsException so callers can switch on `.code`.
        val original = mockk<FirebaseFunctionsException>(relaxed = true)

        val result = BackendSlotService.mapVendFirebaseException(
            FirebaseFunctionsException.Code.UNAUTHENTICATED,
            "bad cert",
            original
        )

        assertThat(result.exceptionOrNull()).isSameInstanceAs(original)
    }

    @Test
    fun `mapVendFirebaseException surfaces UNAVAILABLE as the original Firebase exception`() {
        // UNAVAILABLE is what we get for backend partial-outages. Preserve
        // identity so callers can decide whether to retry at a higher layer.
        val original = mockk<FirebaseFunctionsException>(relaxed = true)

        val result = BackendSlotService.mapVendFirebaseException(
            FirebaseFunctionsException.Code.UNAVAILABLE,
            "backend unavailable",
            original
        )

        assertThat(result.exceptionOrNull()).isSameInstanceAs(original)
    }

    @Test
    fun `mapVendFirebaseException surfaces DEADLINE_EXCEEDED as the original Firebase exception`() {
        // Timeout case — caller may want to surface a "try again" UI.
        val original = mockk<FirebaseFunctionsException>(relaxed = true)

        val result = BackendSlotService.mapVendFirebaseException(
            FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
            "timeout",
            original
        )

        assertThat(result.exceptionOrNull()).isSameInstanceAs(original)
    }
}
