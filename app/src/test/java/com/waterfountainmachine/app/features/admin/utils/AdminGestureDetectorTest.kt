package com.waterfountainmachine.app.features.admin.utils

import android.app.Application
import android.content.ComponentName
import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.waterfountainmachine.app.core.utils.AdminDebugConfig
import com.waterfountainmachine.app.features.admin.ui.AdminAuthActivity
import dagger.hilt.android.testing.HiltTestApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Unit tests for [AdminGestureDetector] -- the 3-rapid-Enter-press gate
 * that opens [AdminAuthActivity]. This is the only entry point to the
 * admin tier from the kiosk surface, so getting the boundary conditions
 * wrong (debounce window, key-up vs key-down, counter reset) means
 * either accidental admin launches or technicians unable to log in.
 *
 * Time is driven by an injected clock so the 1-second debounce window
 * can be exercised deterministically (no `Thread.sleep`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [33])
class AdminGestureDetectorTest {

    private lateinit var context: Application
    private lateinit var view: View
    private var now: Long = 1_000_000L
    private lateinit var detector: AdminGestureDetector

    private fun keyDown(code: Int = KeyEvent.KEYCODE_ENTER): KeyEvent =
        KeyEvent(KeyEvent.ACTION_DOWN, code)

    private fun keyUp(code: Int = KeyEvent.KEYCODE_ENTER): KeyEvent =
        KeyEvent(KeyEvent.ACTION_UP, code)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        view = mockk(relaxed = true)
        now = 1_000_000L
        // AdminDebugConfig touches EncryptedSharedPreferences which needs
        // the Android Keystore -- not available under Robolectric. The
        // logging is a side effect; stub it out so the test can focus on
        // gesture-counting behaviour.
        mockkObject(AdminDebugConfig)
        every { AdminDebugConfig.isAdminLoggingEnabled(any()) } returns false
        detector = AdminGestureDetector(context, view, clock = { now })
        // Drain anything that another test may have started.
        while (shadowOf(context).nextStartedActivity != null) { /* consumed */ }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun nextLaunchedComponent(): ComponentName? =
        shadowOf(context).nextStartedActivity?.component

    @Test
    fun `non-enter key returns false and is not consumed`() {
        val consumed = detector.onKeyEvent(
            KeyEvent.KEYCODE_A,
            keyDown(KeyEvent.KEYCODE_A),
        )

        assertThat(consumed).isFalse()
        assertThat(nextLaunchedComponent()).isNull()
    }

    @Test
    fun `enter key-up event is not consumed and does not increment`() {
        // Up events are ignored entirely. Three of them in a row must not
        // open admin.
        repeat(3) {
            val consumed = detector.onKeyEvent(KeyEvent.KEYCODE_ENTER, keyUp())
            assertThat(consumed).isFalse()
        }

        assertThat(nextLaunchedComponent()).isNull()
    }

    @Test
    fun `single enter press is consumed but does not launch admin`() {
        val consumed = detector.onKeyEvent(KeyEvent.KEYCODE_ENTER, keyDown())

        assertThat(consumed).isTrue() // consumed so Enter is not double-handled
        assertThat(nextLaunchedComponent()).isNull()
    }

    @Test
    fun `two rapid enter presses do not launch admin`() {
        detector.onKeyEvent(KeyEvent.KEYCODE_ENTER, keyDown())
        now += 100
        detector.onKeyEvent(KeyEvent.KEYCODE_ENTER, keyDown())

        assertThat(nextLaunchedComponent()).isNull()
    }

    @Test
    fun `three rapid enter presses launch AdminAuthActivity`() {
        detector.onKeyEvent(KeyEvent.KEYCODE_ENTER, keyDown())
        now += 100
        detector.onKeyEvent(KeyEvent.KEYCODE_ENTER, keyDown())
        now += 100
        detector.onKeyEvent(KeyEvent.KEYCODE_ENTER, keyDown())

        val launched = nextLaunchedComponent()
        assertThat(launched?.className).isEqualTo(AdminAuthActivity::class.java.name)
    }

    @Test
    fun `presses spaced beyond the debounce window do not accumulate`() {
        // Three presses each exactly 1000ms apart. The debounce check is
        // strict less-than, so 1000ms is OUTSIDE the window and the
        // counter must reset to 1 on each subsequent press.
        detector.onKeyEvent(KeyEvent.KEYCODE_ENTER, keyDown())
        now += 1000
        detector.onKeyEvent(KeyEvent.KEYCODE_ENTER, keyDown())
        now += 1000
        detector.onKeyEvent(KeyEvent.KEYCODE_ENTER, keyDown())

        assertThat(nextLaunchedComponent()).isNull()
    }

    @Test
    fun `999ms gap stays inside the debounce window`() {
        // Boundary just below the 1000ms window: must count as rapid.
        detector.onKeyEvent(KeyEvent.KEYCODE_ENTER, keyDown())
        now += 999
        detector.onKeyEvent(KeyEvent.KEYCODE_ENTER, keyDown())
        now += 999
        detector.onKeyEvent(KeyEvent.KEYCODE_ENTER, keyDown())

        assertThat(nextLaunchedComponent()?.className)
            .isEqualTo(AdminAuthActivity::class.java.name)
    }

    @Test
    fun `counter resets after a successful launch`() {
        // First sequence opens admin.
        repeat(3) {
            detector.onKeyEvent(KeyEvent.KEYCODE_ENTER, keyDown())
            now += 100
        }
        assertThat(nextLaunchedComponent()?.className)
            .isEqualTo(AdminAuthActivity::class.java.name)

        // A single press immediately after must NOT trigger another launch.
        detector.onKeyEvent(KeyEvent.KEYCODE_ENTER, keyDown())
        assertThat(nextLaunchedComponent()).isNull()

        // It now takes a fresh three rapid presses to launch again.
        now += 100
        detector.onKeyEvent(KeyEvent.KEYCODE_ENTER, keyDown())
        now += 100
        detector.onKeyEvent(KeyEvent.KEYCODE_ENTER, keyDown())
        assertThat(nextLaunchedComponent()?.className)
            .isEqualTo(AdminAuthActivity::class.java.name)
    }

    @Test
    fun `mid-sequence gap beyond the window restarts the counter`() {
        // 1st press, then 2nd within window, then 3rd outside the window:
        // the 3rd is treated as a fresh sequence start, so no launch.
        detector.onKeyEvent(KeyEvent.KEYCODE_ENTER, keyDown())
        now += 100
        detector.onKeyEvent(KeyEvent.KEYCODE_ENTER, keyDown())
        now += 1500 // gap > timeout: counter resets
        detector.onKeyEvent(KeyEvent.KEYCODE_ENTER, keyDown())

        assertThat(nextLaunchedComponent()).isNull()
    }
}
