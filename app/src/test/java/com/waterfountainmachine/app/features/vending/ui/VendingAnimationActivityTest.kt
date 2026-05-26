package com.waterfountainmachine.app.features.vending.ui

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
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
 * Robolectric tests for [VendingAnimationActivity]'s machine-disabled
 * kill switch. Same rationale as [SMSActivityTest] / [SMSVerifyActivityTest]:
 * remote disable must short-circuit dispensing before any hardware call.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [33])
class VendingAnimationActivityTest {

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
    fun `disabled machine finishes activity before starting vend`() {
        val monitor = mockk<IMachineHealthMonitor>(relaxed = true)
        every { monitor.isMachineDisabled() } returns true
        every { HealthMonitorModule.getMachineHealthMonitor(any()) } returns monitor

        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            VendingAnimationActivity::class.java,
        ).apply {
            putExtra("slot", 1)
            putExtra("phoneNumber", "5555550100")
            putExtra("dispensingTime", 8000L)
        }

        ActivityScenario.launch<VendingAnimationActivity>(intent).use { scenario ->
            assertThat(scenario.state).isEqualTo(Lifecycle.State.DESTROYED)
        }
    }
}
