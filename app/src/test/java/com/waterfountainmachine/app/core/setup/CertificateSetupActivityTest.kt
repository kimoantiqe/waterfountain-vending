package com.waterfountainmachine.app.core.setup

import android.app.Application
import android.widget.Button
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.textfield.TextInputLayout
import com.google.common.truth.Truth.assertThat
import com.google.firebase.functions.FirebaseFunctions
import com.waterfountainmachine.app.R
import com.waterfountainmachine.app.core.security.SecurityModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * Robolectric tests for [CertificateSetupActivity].
 *
 * Focus: the enrollment-input validation gate that runs BEFORE any
 * crypto / backend work. A typo here means a bricked machine, so the
 * three validation paths (blank machine ID, blank token, both populated)
 * are the highest-risk thing to cover.
 *
 * Out of scope: the actual enrollment flow ([SecurityModule.generatePublicKeyPem],
 * `callEnrollMachineKey`, [SecurityModule.installCertificate],
 * [com.waterfountainmachine.app.workers.CertificateRenewalWorker.schedule]).
 * That path requires a full Firebase + Keystore stack and is exercised
 * by integration tests on real hardware.
 *
 * Setup notes:
 *  - `mockkObject(SecurityModule)` stops `checkExistingEnrollment` (called
 *    from onCreate) from short-circuiting into the already-enrolled UI.
 *  - `mockkStatic(FirebaseFunctions::class)` keeps `FirebaseFunctions.getInstance()`
 *    in onCreate from trying to initialise a real Firebase app.
 *  - `application = Application::class` keeps `WaterFountainApplication.onCreate`
 *    (which hits Keystore / Firebase) from running on the JVM.
 */
@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [33],
    application = Application::class,
)
class CertificateSetupActivityTest {

    @Before
    fun setUp() {
        mockkObject(SecurityModule)
        every { SecurityModule.isEnrolled() } returns false

        mockkStatic(FirebaseFunctions::class)
        every { FirebaseFunctions.getInstance() } returns mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `blank machine id shows the machine-id error`() {
        val activity = launch()
        val views = Views(activity)

        views.machineIdInput.setText("")
        views.tokenInput.setText("some-token")

        views.startButton.performClick()

        assertThat(views.machineIdLayout.error?.toString())
            .isEqualTo("Machine ID is required")
        // Inputs must remain enabled when validation rejects the form.
        assertThat(views.startButton.isEnabled).isTrue()
        assertThat(views.machineIdInput.isEnabled).isTrue()
    }

    @Test
    fun `blank token shows the token error`() {
        val activity = launch()
        val views = Views(activity)

        views.machineIdInput.setText("machine-123")
        views.tokenInput.setText("")

        views.startButton.performClick()

        assertThat(views.tokenLayout.error?.toString())
            .isEqualTo("One-time token is required")
        assertThat(views.startButton.isEnabled).isTrue()
    }

    @Test
    fun `whitespace-only machine id is treated as blank`() {
        val activity = launch()
        val views = Views(activity)

        views.machineIdInput.setText("   ")
        views.tokenInput.setText("\t")

        views.startButton.performClick()

        // The machine-id check runs first; the token check is in the else
        // branch so it isn't reached when machine-id is already blank.
        assertThat(views.machineIdLayout.error?.toString())
            .isEqualTo("Machine ID is required")
    }

    @Test
    fun `populated inputs clear errors and lock the form`() {
        val activity = launch()
        val views = Views(activity)

        // Seed pre-existing errors so we can assert they get cleared.
        views.machineIdLayout.error = "stale error"
        views.tokenLayout.error = "stale error"

        views.machineIdInput.setText("machine-123")
        views.tokenInput.setText("token-abc")

        views.startButton.performClick()

        assertThat(views.machineIdLayout.error).isNull()
        assertThat(views.tokenLayout.error).isNull()
        // After validation passes the form is locked while the suspend
        // enrollment routine runs in lifecycleScope.
        assertThat(views.machineIdInput.isEnabled).isFalse()
        assertThat(views.tokenInput.isEnabled).isFalse()
        assertThat(views.startButton.isEnabled).isFalse()
    }

    private fun launch(): CertificateSetupActivity =
        Robolectric.buildActivity(CertificateSetupActivity::class.java)
            .create()
            .start()
            .resume()
            .get()

    /**
     * Bundle of the views the validation tests need to reach into.
     * Centralised so each test reads top-down without repeating findViewById.
     */
    private class Views(activity: CertificateSetupActivity) {
        val machineIdLayout: TextInputLayout = activity.findViewById(R.id.machineIdLayout)
        val tokenLayout: TextInputLayout = activity.findViewById(R.id.tokenLayout)
        val machineIdInput: EditText = activity.findViewById(R.id.machineIdInput)
        val tokenInput: EditText = activity.findViewById(R.id.tokenInput)
        val startButton: Button = activity.findViewById(R.id.startEnrollmentButton)
    }
}
