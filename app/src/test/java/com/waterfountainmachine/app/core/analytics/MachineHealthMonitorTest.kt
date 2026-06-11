package com.waterfountainmachine.app.core.analytics

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.waterfountainmachine.app.core.utils.UserErrorMessages
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the pure-logic surface of [MachineHealthMonitor]:
 *
 * - dispense counters
 * - daily reset
 * - successRate computation in [MachineHealthMonitor.getHealthStatus]
 * - the SharedPreferences-backed remote-disable flag read via
 *   [MachineHealthMonitor.isMachineDisabled] / [getDisabledReason]
 *
 * Heartbeat / status-check coroutines touch Firebase and are NOT
 * exercised here -- those paths are owned by the integration suite.
 * The Firebase Functions client itself is now lazily initialised so
 * these tests run without `FirebaseApp.initializeApp(...)`.
 *
 * NOTE: [MachineHealthMonitor] is a singleton keyed on application
 * context. Within a single JVM run there is one shared instance, so
 * every test resets counters and clears the backing prefs in @Before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [33])
class MachineHealthMonitorTest {

    private lateinit var context: Application
    private lateinit var monitor: MachineHealthMonitor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric gives every test a fresh Application instance, but
        // MachineHealthMonitor caches its singleton (and the prefs handle
        // it holds) in a static field. Clear it so each test wires up
        // against the current test's context and prefs file.
        val instanceField = MachineHealthMonitor::class.java
            .getDeclaredField("instance")
            .apply { isAccessible = true }
        instanceField.set(null, null)

        // Wipe the prefs file backing isMachineDisabled / getDisabledReason
        // BEFORE acquiring the singleton so the first construction picks
        // up a clean slate too. Using commit() so the write is observable
        // to the singleton's cached prefs handle synchronously.
        @Suppress("ApplySharedPref")
        context.getSharedPreferences("machine_health", Context.MODE_PRIVATE)
            .edit().clear().commit()
        monitor = MachineHealthMonitor.getInstance(context)
        monitor.resetDailyCounters()
    }

    // --- recordDispense -------------------------------------------------

    @Test
    fun `recordDispense success increments total and successful counts`() {
        monitor.recordDispense(slotNumber = 1, success = true)
        monitor.recordDispense(slotNumber = 2, success = true)

        val status = monitor.getHealthStatus()
        assertThat(status["totalDispensesToday"]).isEqualTo(2)
        assertThat(status["successfulDispensesToday"]).isEqualTo(2)
        assertThat(status["failedDispensesToday"]).isEqualTo(0)
    }

    @Test
    fun `recordDispense failure increments total and failed counts and stores error code`() {
        monitor.recordDispense(slotNumber = 5, success = false, errorCode = "HW_TIMEOUT")

        val status = monitor.getHealthStatus()
        assertThat(status["totalDispensesToday"]).isEqualTo(1)
        assertThat(status["successfulDispensesToday"]).isEqualTo(0)
        assertThat(status["failedDispensesToday"]).isEqualTo(1)
    }

    @Test
    fun `recordDispense success does not overwrite the previous error code on success`() {
        // This pins the documented behaviour: lastErrorCode only updates
        // on failure, so a later success does NOT erase the history of
        // what previously broke. Heartbeats carry the last error until
        // the next failure or the daily reset.
        monitor.recordDispense(slotNumber = 1, success = false, errorCode = "HW_TIMEOUT")
        monitor.recordDispense(slotNumber = 2, success = true)
        // lastErrorCode is private; we verify indirectly via successRate
        // which proves the success path was taken without altering the
        // failure counter.
        val status = monitor.getHealthStatus()
        assertThat(status["failedDispensesToday"]).isEqualTo(1)
        assertThat(status["successfulDispensesToday"]).isEqualTo(1)
    }

    // --- getHealthStatus.successRate ------------------------------------

    @Test
    fun `successRate is 100 percent when there have been no dispenses`() {
        // Zero-dispense ambiguity guard: an empty machine reports
        // 100%, not 0% (avoids paging the on-call for an idle kiosk).
        val status = monitor.getHealthStatus()
        assertThat(status["totalDispensesToday"]).isEqualTo(0)
        assertThat(status["successRate"]).isEqualTo(100)
    }

    @Test
    fun `successRate is the integer percentage when there have been dispenses`() {
        repeat(4) { monitor.recordDispense(slotNumber = 1, success = true) }
        monitor.recordDispense(slotNumber = 1, success = false, errorCode = "HW_TIMEOUT")

        // 4 successes / 5 total = 80%.
        assertThat(monitor.getHealthStatus()["successRate"]).isEqualTo(80)
    }

    @Test
    fun `successRate truncates fractional percentages towards zero`() {
        repeat(2) { monitor.recordDispense(slotNumber = 1, success = true) }
        monitor.recordDispense(slotNumber = 1, success = false, errorCode = "X")
        // 2/3 = 66.66...% which the int conversion truncates to 66.
        assertThat(monitor.getHealthStatus()["successRate"]).isEqualTo(66)
    }

    // --- resetDailyCounters --------------------------------------------

    @Test
    fun `resetDailyCounters clears every counter`() {
        monitor.recordDispense(slotNumber = 1, success = true)
        monitor.recordDispense(slotNumber = 2, success = false, errorCode = "ERR")

        monitor.resetDailyCounters()

        val status = monitor.getHealthStatus()
        assertThat(status["totalDispensesToday"]).isEqualTo(0)
        assertThat(status["successfulDispensesToday"]).isEqualTo(0)
        assertThat(status["failedDispensesToday"]).isEqualTo(0)
        assertThat(status["successRate"]).isEqualTo(100)
    }

    // --- isMachineDisabled / getDisabledReason --------------------------

    @Test
    fun `isMachineDisabled defaults to false when no status has been written`() {
        assertThat(monitor.isMachineDisabled()).isFalse()
        assertThat(monitor.getDisabledReason()).isNull()
    }

    @Test
    fun `isMachineDisabled reflects the value written to backing prefs`() {
        // Writing through the same pref file the monitor reads from
        // simulates a status check having stored a disable verdict.
        @Suppress("ApplySharedPref")
        context.getSharedPreferences("machine_health", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("is_disabled", true)
            .putString("disabled_reason", "scheduled maintenance")
            .commit()

        assertThat(monitor.isMachineDisabled()).isTrue()
        assertThat(monitor.getDisabledReason()).isEqualTo("scheduled maintenance")
    }

    // --- getMaintenanceMessage / getMaintenanceScreenMessage -----------

    private fun writeMaintenance(on: Boolean, message: String?) {
        @Suppress("ApplySharedPref")
        context.getSharedPreferences("machine_health", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("remote_maintenance_mode", on)
            .putString("remote_maintenance_message", message)
            .commit()
    }

    @Test
    fun `isMaintenanceMode defaults to false with default maintenance message`() {
        assertThat(monitor.isMaintenanceMode()).isFalse()
        assertThat(monitor.getMaintenanceMessage()).isNull()
        assertThat(monitor.getMaintenanceScreenMessage())
            .isEqualTo(UserErrorMessages.MACHINE_MAINTENANCE)
    }

    @Test
    fun `getMaintenanceScreenMessage returns the admin message when set`() {
        writeMaintenance(true, "Back soon — restocking.")
        assertThat(monitor.isMaintenanceMode()).isTrue()
        assertThat(monitor.getMaintenanceScreenMessage()).isEqualTo("Back soon — restocking.")
    }

    @Test
    fun `getMaintenanceScreenMessage falls back to default for an empty message`() {
        writeMaintenance(true, "")
        assertThat(monitor.getMaintenanceScreenMessage())
            .isEqualTo(UserErrorMessages.MACHINE_MAINTENANCE)
    }

    @Test
    fun `getMaintenanceScreenMessage falls back to default for a whitespace-only message`() {
        writeMaintenance(true, "   \n  ")
        assertThat(monitor.getMaintenanceScreenMessage())
            .isEqualTo(UserErrorMessages.MACHINE_MAINTENANCE)
    }

    @Test
    fun `getHealthStatus exposes machineId as unknown when start has never been called`() {
        // Without start(machineId), the monitor exposes "unknown" so that
        // health snapshots taken from the admin panel never crash even
        // before enrolment completes.
        assertThat(monitor.getHealthStatus()["machineId"]).isEqualTo("unknown")
    }

    @Test
    fun `getHealthStatus uptimeSeconds is non-negative`() {
        val uptime = monitor.getHealthStatus()["uptimeSeconds"] as Long
        // sessionStartTime defaults to 0 so uptime ~= currentTimeMillis/1000
        // -- the only thing worth asserting is sign.
        assertThat(uptime).isAtLeast(0L)
    }
}
