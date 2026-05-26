package com.waterfountainmachine.app.features.admin.ui

import android.os.Looper
import android.view.View
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import com.google.common.truth.Truth.assertThat
import com.waterfountainmachine.app.R
import com.waterfountainmachine.app.core.security.SecurityModule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric tests for [AdminPanelActivity].
 *
 * AdminPanelActivity is a thin host: it owns navigation between four
 * fragments + the exit button + a 5-minute inactivity timer. The
 * fragments themselves are tested separately (their own state and IO).
 *
 * Scope here: the activity's own contract.
 *  - Exit button finishes.
 *  - Back press finishes (no fragment back-stack to pop).
 *  - The default fragment is `CertificateStatusFragment` and its tab
 *    is the selected/highlighted nav button on first open.
 *  - The four nav buttons swap content and update the alpha/selected
 *    state correctly.
 *
 * Out of scope: anything that requires the loaded fragment to actually
 * render its own data (Firebase / Keystore IO). We stub
 * [SecurityModule.isEnrolled] to keep [CertificateStatusFragment]'s
 * onViewCreated on the not-enrolled branch, which doesn't make any
 * network calls.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [33])
class AdminPanelActivityTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Before
    fun setUp() {
        mockkObject(SecurityModule)
        // Keep CertificateStatusFragment on the simple "not enrolled" path
        // -- avoids OkHttp / SslContext / certificate inspection.
        every { SecurityModule.isEnrolled() } returns false
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `activity launches with certificate tab selected by default`() {
        ActivityScenario.launch(AdminPanelActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val cert = activity.findViewById<Button>(R.id.certificateButton)
                val hardware = activity.findViewById<Button>(R.id.hardwareButton)
                val logs = activity.findViewById<Button>(R.id.logsButton)
                val system = activity.findViewById<Button>(R.id.systemButton)

                assertThat(cert.isSelected).isTrue()
                assertThat(cert.alpha).isEqualTo(1.0f)
                assertThat(hardware.isSelected).isFalse()
                assertThat(hardware.alpha).isEqualTo(0.6f)
                assertThat(logs.isSelected).isFalse()
                assertThat(system.isSelected).isFalse()
            }
        }
    }

    @Test
    fun `exit button finishes the activity`() {
        ActivityScenario.launch(AdminPanelActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.exitButton).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertThat(activity.isFinishing).isTrue()
            }
        }
    }

    @Test
    fun `tapping a nav button moves the highlight to that button`() {
        ActivityScenario.launch(AdminPanelActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val cert = activity.findViewById<Button>(R.id.certificateButton)
                val logs = activity.findViewById<Button>(R.id.logsButton)

                // Pre-condition: certificate is the default selected tab.
                assertThat(cert.isSelected).isTrue()

                logs.performClick()
                shadowOf(Looper.getMainLooper()).idle()

                assertThat(logs.isSelected).isTrue()
                assertThat(logs.alpha).isEqualTo(1.0f)
                assertThat(cert.isSelected).isFalse()
                assertThat(cert.alpha).isEqualTo(0.6f)
            }
        }
    }
}
