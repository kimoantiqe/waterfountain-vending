package com.waterfountainmachine.app.features.error

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.waterfountainmachine.app.core.analytics.IMachineHealthMonitor
import com.waterfountainmachine.app.core.di.HealthMonitorModule
import com.waterfountainmachine.app.core.utils.SecurePreferences
import com.waterfountainmachine.app.features.vending.ui.MainActivity
import com.waterfountainmachine.app.testing.FakeSharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for [ErrorActivity]'s machine-status watch
 * (`EXTRA_WATCH_MACHINE_STATUS`) — the recovery path that lets the kiosk
 * leave the disabled/maintenance screen as soon as an admin clears the
 * state, instead of waiting out the 24h timer.
 *
 * Scope: the SYNCHRONOUS create-time verdict only. When the screen is
 * armed to watch status, `onCreate` immediately returns to the main
 * screen if the machine is already available, and stays put while it is
 * disabled / in maintenance (remote or local). The async
 * SharedPreferences-listener transition is intentionally NOT asserted
 * here: it fires on the main looper and would require brittle looper
 * advancement (consistent with the timer/looper stance in
 * [ErrorActivityTest]).
 *
 * Harness mirrors [com.waterfountainmachine.app.features.vending.ui.MainActivityTest]:
 *  - `mockkObject(SecurePreferences)` swaps EncryptedSharedPreferences for
 *    an in-memory [FakeSharedPreferences] (local maintenance read).
 *  - `mockkObject(HealthMonitorModule)` controls the disabled/maintenance
 *    verdict the production Hilt graph would otherwise resolve.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class ErrorActivityRecoveryTest {

    private lateinit var systemPrefs: FakeSharedPreferences
    private lateinit var monitor: IMachineHealthMonitor

    @Before
    fun setUp() {
        systemPrefs = FakeSharedPreferences()
        mockkObject(SecurePreferences)
        every { SecurePreferences.getSystemSettings(any()) } returns systemPrefs

        monitor = mockk(relaxed = true)
        every { monitor.isMachineDisabled() } returns false
        every { monitor.isMaintenanceMode() } returns false
        mockkObject(HealthMonitorModule)
        every { HealthMonitorModule.getMachineHealthMonitor(any()) } returns monitor
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun watchIntent(): Intent =
        Intent(ApplicationProvider.getApplicationContext(), ErrorActivity::class.java)
            .putExtra(ErrorActivity.EXTRA_WATCH_MACHINE_STATUS, true)
            .putExtra(ErrorActivity.EXTRA_DISPLAY_DURATION, 24 * 60 * 60 * 1000L)

    @Test
    fun `watch mode returns to main immediately when machine is available`() {
        // Not disabled, not maintenance, no local maintenance -> bounce back.
        val activity = Robolectric.buildActivity(ErrorActivity::class.java, watchIntent())
            .create()
            .get()

        assertThat(activity.isFinishing).isTrue()
        val next = shadowOf(activity).nextStartedActivity
        assertThat(next.component?.className).isEqualTo(MainActivity::class.java.name)
    }

    @Test
    fun `watch mode stays on screen while remotely disabled`() {
        every { monitor.isMachineDisabled() } returns true

        val activity = Robolectric.buildActivity(ErrorActivity::class.java, watchIntent())
            .create()
            .get()

        assertThat(activity.isFinishing).isFalse()
    }

    @Test
    fun `watch mode stays on screen while in remote maintenance`() {
        every { monitor.isMaintenanceMode() } returns true

        val activity = Robolectric.buildActivity(ErrorActivity::class.java, watchIntent())
            .create()
            .get()

        assertThat(activity.isFinishing).isFalse()
    }

    @Test
    fun `watch mode stays on screen while in local maintenance`() {
        systemPrefs.edit().putBoolean("maintenance_mode", true).commit()

        val activity = Robolectric.buildActivity(ErrorActivity::class.java, watchIntent())
            .create()
            .get()

        assertThat(activity.isFinishing).isFalse()
    }

    @Test
    fun `non-watch error screen ignores machine status`() {
        // A normal error screen (no watch extra) must never bounce, even if
        // the machine happens to be available.
        val intent = Intent(ApplicationProvider.getApplicationContext(), ErrorActivity::class.java)
            .putExtra(ErrorActivity.EXTRA_MESSAGE, "Daily limit reached")

        val activity = Robolectric.buildActivity(ErrorActivity::class.java, intent)
            .create()
            .get()

        assertThat(activity.isFinishing).isFalse()
    }
}
