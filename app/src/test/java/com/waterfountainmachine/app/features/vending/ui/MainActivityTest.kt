package com.waterfountainmachine.app.features.vending.ui

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.google.common.truth.Truth.assertThat
import com.waterfountainmachine.app.core.analytics.AnalyticsManager
import com.waterfountainmachine.app.core.analytics.IMachineHealthMonitor
import com.waterfountainmachine.app.core.di.HealthMonitorModule
import com.waterfountainmachine.app.core.utils.SecurePreferences
import com.waterfountainmachine.app.testing.FakeSharedPreferences
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for [MainActivity]'s machine-disabled kill switch.
 *
 * Scope: the audit-driven contract that when [checkMachineDisabled]
 * decides the kiosk should not serve customers, the rest of `onCreate`
 * is skipped and the Activity finishes BEFORE any hardware
 * initialization, gesture wiring, or further setup work runs. A
 * regression here would let a maintenance-mode or remotely-disabled
 * machine keep spinning up hardware coroutines and gesture detectors
 * for a screen the user will never see.
 *
 * The interactive happy path (tap-to-start → CAN selection navigation)
 * is out of scope: it depends on a `WaterFountainApplication` cast
 * inside `initializeHardware()` that cannot run under a Hilt test
 * harness without a non-trivial refactor (extracting the hardware
 * coordinator behind an interface). The kill-switch tests below cover
 * the security-critical surface; the happy path is exercised by manual
 * QA + device-side smoke runs.
 *
 * Setup notes mirror [AdminAuthActivityTest] and [SMSActivityTest]:
 *  - `HiltTestApplication` replaces [com.waterfountainmachine.app.WaterFountainApplication]
 *    so we avoid Keystore / Firebase init on the JVM.
 *  - `mockkObject(SecurePreferences)` swaps EncryptedSharedPreferences
 *    for an in-memory [FakeSharedPreferences] (kiosk-mode read +
 *    maintenance-mode read both go through it).
 *  - `mockkObject(AnalyticsManager)` replaces the Firebase-backed
 *    singleton with a relaxed mock so `setupAnalytics` is a no-op
 *    instead of a Firebase init.
 *  - `mockkObject(HealthMonitorModule)` controls the disabled verdict
 *    that the production Hilt graph would otherwise resolve from the
 *    real provider.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [33])
class MainActivityTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private lateinit var prefs: FakeSharedPreferences

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        mockkObject(SecurePreferences)
        every { SecurePreferences.getSystemSettings(any()) } returns prefs

        mockkObject(AnalyticsManager)
        every { AnalyticsManager.getInstance(any()) } returns mockk(relaxed = true)

        mockkObject(HealthMonitorModule)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `local maintenance mode finishes activity before hardware init`() {
        prefs.edit().putBoolean("maintenance_mode", true).commit()
        // Even with the machine remotely ENABLED, the local maintenance
        // flag alone must finish the Activity.
        val monitor = mockk<IMachineHealthMonitor>(relaxed = true)
        every { monitor.isMachineDisabled() } returns false
        every { HealthMonitorModule.getMachineHealthMonitor(any()) } returns monitor

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // `finish()` from showMaintenanceScreen + `isFinishing` early
            // return in onCreate together drop the Activity to DESTROYED
            // before launch() returns. If this regresses (e.g., someone
            // removes the `isFinishing` guard), the next line still
            // executes `initializeHardware()` which crashes the cast and
            // surfaces as a different failure -- both are loud.
            assertThat(scenario.state).isEqualTo(Lifecycle.State.DESTROYED)
        }
    }

    @Test
    fun `remotely disabled machine finishes activity before hardware init`() {
        prefs.edit().putBoolean("maintenance_mode", false).commit()
        val monitor = mockk<IMachineHealthMonitor>(relaxed = true)
        every { monitor.isMachineDisabled() } returns true
        every { HealthMonitorModule.getMachineHealthMonitor(any()) } returns monitor

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertThat(scenario.state).isEqualTo(Lifecycle.State.DESTROYED)
        }
    }
}
