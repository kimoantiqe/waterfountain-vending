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
 * Robolectric tests for [SMSVerifyActivity]'s machine-disabled kill switch.
 *
 * Same rationale as [SMSActivityTest]: this is the audit-driven contract
 * that the remote disable lever short-circuits the OTP verification flow.
 * The interactive OTP behaviour itself lives in [SMSVerifyViewModel].
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [33])
class SMSVerifyActivityTest {

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
    fun `disabled machine finishes activity before showing otp entry`() {
        val monitor = mockk<IMachineHealthMonitor>(relaxed = true)
        every { monitor.isMachineDisabled() } returns true
        every { HealthMonitorModule.getMachineHealthMonitor(any()) } returns monitor

        // SMSVerifyActivity requires a phone number on the launching intent.
        // We supply a syntactically valid one even though no verification
        // happens -- the bail-out short-circuits before initialize().
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            SMSVerifyActivity::class.java,
        ).putExtra(SMSVerifyActivity.EXTRA_PHONE_NUMBER, "5555550100")

        ActivityScenario.launch<SMSVerifyActivity>(intent).use { scenario ->
            assertThat(scenario.state).isEqualTo(Lifecycle.State.DESTROYED)
        }
    }
}
