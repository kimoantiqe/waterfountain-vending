package com.waterfountainmachine.app.features.vending.ui

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.google.common.truth.Truth.assertThat
import com.waterfountainmachine.app.core.analytics.IMachineHealthMonitor
import com.waterfountainmachine.app.core.di.HealthMonitorModule
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
 * Robolectric tests for [SMSActivity]'s machine-disabled kill switch.
 *
 * Scope: the audit-driven contract that `bailIfMachineDisabled` short-
 * circuits the customer-facing flow BEFORE any setup work runs. A
 * regression here would let a remotely-disabled machine still accept
 * phone numbers and queue SMS, defeating the entire admin disable lever.
 *
 * The interactive phone-entry behaviour itself lives in [SMSViewModel]
 * (already covered by `SMSViewModelTest`). This activity is otherwise
 * a thin renderer around the ViewModel + sound + analytics, none of
 * which is worth round-tripping through Robolectric.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [33])
class SMSActivityTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Before
    fun setUp() {
        mockkObject(HealthMonitorModule)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `disabled machine finishes activity before showing phone entry`() {
        val monitor = mockk<IMachineHealthMonitor>(relaxed = true)
        every { monitor.isMachineDisabled() } returns true
        every { HealthMonitorModule.getMachineHealthMonitor(any()) } returns monitor

        ActivityScenario.launch(SMSActivity::class.java).use { scenario ->
            // The activity calls finish() inside bailIfMachineDisabled, so by
            // the time launch() returns the activity has already moved to
            // DESTROYED. We can't call onActivity (it asserts the activity
            // is alive); state is the durable signal that the bail fired.
            assertThat(scenario.state).isEqualTo(Lifecycle.State.DESTROYED)
        }
    }
}
