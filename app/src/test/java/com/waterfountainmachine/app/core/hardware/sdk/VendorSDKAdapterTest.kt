package com.waterfountainmachine.app.core.hardware.sdk

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [VendorSDKAdapter] guard paths.
 *
 * The vendor SDK ([com.yy.tools.util.CYVendingMachine]) is a final
 * class shipped as an .aar that talks to `/dev/ttyS0` in its
 * constructor — there's no seam to mock it cleanly without a wider
 * refactor (constructor-injected factory). We therefore restrict this
 * suite to the branches that never reach the SDK:
 *
 *   1. [VendorSDKAdapter.initialize] correctly fails when `/dev/ttyS0`
 *      is absent (always true on JVM/CI).
 *   2. [VendorSDKAdapter.isReady] starts false and flips to true only
 *      after a successful initialize.
 *   3. [VendorSDKAdapter.dispenseWater] short-circuits on invalid slot
 *      numbers and on the not-ready state, returning a structured
 *      failure [WaterDispenseResult] (NOT a Result.failure — the
 *      contract is always Result.success, with the success flag inside
 *      the payload).
 *
 * End-to-end dispense behavior (post-SDK-construction) is covered by
 * the on-device instrumentation suite, where real hardware is present.
 */
class VendorSDKAdapterTest {

    private lateinit var adapter: VendorSDKAdapter

    @Before
    fun setup() {
        adapter = VendorSDKAdapter()
    }

    // ---------- initialize ----------

    @Test
    fun `initialize fails when serial port is absent on JVM`() {
        val result = adapter.initialize()

        assertThat(result.isFailure).isTrue()
        val ex = result.exceptionOrNull()
        assertThat(ex).isInstanceOf(VendingMachineException.InitializationError::class.java)
        assertThat(ex).hasMessageThat().contains("/dev/ttyS0")
    }

    @Test
    fun `isReady is false before initialize is called`() {
        assertThat(adapter.isReady()).isFalse()
    }

    @Test
    fun `isReady stays false after a failed initialize`() {
        adapter.initialize() // fails on JVM
        assertThat(adapter.isReady()).isFalse()
    }

    // ---------- dispenseWater: input validation ----------

    @Test
    fun `dispenseWater returns invalid-slot result for slot 0`() = runTest {
        val result = adapter.dispenseWater(0)

        assertThat(result.isSuccess).isTrue() // contract: always Result.success
        val payload = result.getOrThrow()
        assertThat(payload.success).isFalse()
        assertThat(payload.slot).isEqualTo(0)
        assertThat(payload.errorCode).isEqualTo(0x01)
        assertThat(payload.errorMessage).contains("Invalid slot")
        assertThat(payload.dispensingTimeMs).isEqualTo(0)
    }

    @Test
    fun `dispenseWater returns invalid-slot result for slot 9`() = runTest {
        // 9 is in the gap between row 1 (1-8) and row 2 (11-18)
        val result = adapter.dispenseWater(9)
        assertThat(result.getOrThrow().success).isFalse()
        assertThat(result.getOrThrow().errorCode).isEqualTo(0x01)
    }

    @Test
    fun `dispenseWater returns invalid-slot result for slot 99`() = runTest {
        val result = adapter.dispenseWater(99)
        assertThat(result.getOrThrow().success).isFalse()
        assertThat(result.getOrThrow().errorCode).isEqualTo(0x01)
    }

    @Test
    fun `dispenseWater returns invalid-slot result for negative slot`() = runTest {
        val result = adapter.dispenseWater(-1)
        assertThat(result.getOrThrow().success).isFalse()
        assertThat(result.getOrThrow().errorCode).isEqualTo(0x01)
    }

    // ---------- dispenseWater: readiness guard ----------

    @Test
    fun `dispenseWater rejects valid slot when adapter not initialized`() = runTest {
        // Slot 1 is valid; initialize() has not been called.
        val result = adapter.dispenseWater(1)

        assertThat(result.isSuccess).isTrue()
        val payload = result.getOrThrow()
        assertThat(payload.success).isFalse()
        assertThat(payload.slot).isEqualTo(1)
        assertThat(payload.errorCode).isEqualTo(0x02)
        assertThat(payload.errorMessage).contains("not ready")
    }

    @Test
    fun `dispenseWater readiness guard runs after slot validation`() = runTest {
        // Even when un-initialized, invalid slot should produce errorCode 0x01
        // (not 0x02). Documents the order of guard checks for the contract.
        val result = adapter.dispenseWater(0)
        assertThat(result.getOrThrow().errorCode).isEqualTo(0x01)
    }

    // ---------- SerialConfig ----------

    @Test
    fun `SerialConfig defaults match vendor SDK hardcoded settings`() {
        val cfg = SerialConfig()
        assertThat(cfg.baudRate).isEqualTo(9600)
        assertThat(cfg.dataBits).isEqualTo(8)
        assertThat(cfg.stopBits).isEqualTo(1)
        assertThat(cfg.parity).isEqualTo(0)
    }
}
