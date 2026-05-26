package com.waterfountainmachine.app.features.admin.ui

import android.content.Intent
import android.os.Looper
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.waterfountainmachine.app.core.config.WaterFountainConfig
import com.waterfountainmachine.app.core.utils.SecurePreferences
import com.waterfountainmachine.app.features.admin.utils.AdminPinManager
import com.waterfountainmachine.app.testing.FakeSharedPreferences
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
import java.util.concurrent.TimeUnit

/**
 * Robolectric tests for [AdminAuthActivity].
 *
 * Focuses on the security-critical surface: failed-attempt counting,
 * the 1-hour lockout after 3 strikes, and the reset-on-success path.
 * These are the only pieces standing between a malicious operator and
 * the admin panel, so they need stronger coverage than the unit tests
 * on [AdminPinManager] can give us alone (those test the hash; this
 * tests the wiring).
 *
 * Out of scope: the navigation Intent contents (covered by Android
 * framework), the rotation dialog (manual QA -- AlertDialog under
 * Robolectric is fiddly and the value is low).
 *
 * Setup notes:
 *  - `@AndroidEntryPoint` activities require a Hilt-aware Application
 *    under test. [HiltTestApplication] replaces [com.waterfountainmachine.app.WaterFountainApplication]
 *    so we don't try to initialise Keystore / Firebase on the JVM.
 *  - `mockkObject(AdminPinManager)` controls the PIN verdict and the
 *    default-PIN check so each test can pick its scenario.
 *  - `mockkObject(SecurePreferences)` swaps the EncryptedSharedPreferences
 *    (which needs AndroidKeyStore) for an in-memory [FakeSharedPreferences].
 *    We assert directly on `prefs` for the persisted lockout state so we
 *    don't have to round-trip through the UI.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(
    application = HiltTestApplication::class,
    sdk = [33],
)
class AdminAuthActivityTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private lateinit var prefs: FakeSharedPreferences

    @Before
    fun setUp() {
        hiltRule.inject()

        prefs = FakeSharedPreferences()
        mockkObject(SecurePreferences)
        every { SecurePreferences.getSystemSettings(any()) } returns prefs

        mockkObject(AdminPinManager)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `wrong pin increments failed attempts but does not lock out`() {
        every { AdminPinManager.verifyPin(any(), any()) } returns false

        ActivityScenario.launch(AdminAuthActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> enterPin(activity, "00000000") }

            assertThat(prefs.getInt("failed_attempts", -1)).isEqualTo(1)
            assertThat(prefs.getLong("lockout_until", -1L)).isEqualTo(0L)
        }
    }

    @Test
    fun `three wrong pins triggers one hour lockout`() {
        every { AdminPinManager.verifyPin(any(), any()) } returns false
        val before = System.currentTimeMillis()

        ActivityScenario.launch(AdminAuthActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Three independent 8-digit entries. We drain the main looper
                // between entries so the postDelayed clear / re-enable path
                // runs and the activity is ready for the next attempt.
                repeat(WaterFountainConfig.ADMIN_MAX_ATTEMPTS) {
                    enterPin(activity, "00000000")
                    shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
                }
            }

            val attempts = prefs.getInt("failed_attempts", -1)
            val lockoutUntil = prefs.getLong("lockout_until", -1L)

            assertThat(attempts).isEqualTo(WaterFountainConfig.ADMIN_MAX_ATTEMPTS)
            // lockoutUntil = currentTime + 1 hour. Allow a generous window
            // for the time it took to run the three attempts.
            val expectedLowerBound = before + WaterFountainConfig.ADMIN_LOCKOUT_DURATION_MS
            val expectedUpperBound =
                System.currentTimeMillis() + WaterFountainConfig.ADMIN_LOCKOUT_DURATION_MS + 1_000L
            assertThat(lockoutUntil).isAtLeast(expectedLowerBound)
            assertThat(lockoutUntil).isAtMost(expectedUpperBound)
        }
    }

    @Test
    fun `keypad is disabled when activity opens during active lockout`() {
        every { AdminPinManager.verifyPin(any(), any()) } returns false
        // Seed a fresh lockout that hasn't expired yet.
        prefs.edit()
            .putInt("failed_attempts", WaterFountainConfig.ADMIN_MAX_ATTEMPTS)
            .putLong(
                "lockout_until",
                System.currentTimeMillis() + WaterFountainConfig.ADMIN_LOCKOUT_DURATION_MS,
            )
            .apply()

        ActivityScenario.launch(AdminAuthActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val btn0 = activity.findViewById<Button>(
                    com.waterfountainmachine.app.R.id.btn0
                )
                val clearButton = activity.findViewById<Button>(
                    com.waterfountainmachine.app.R.id.clearButton
                )
                assertThat(btn0.isEnabled).isFalse()
                assertThat(clearButton.isEnabled).isFalse()
            }
        }
    }

    @Test
    fun `expired lockout is cleared on activity open`() {
        every { AdminPinManager.verifyPin(any(), any()) } returns false
        // Lockout sat in prefs but already expired.
        prefs.edit()
            .putInt("failed_attempts", WaterFountainConfig.ADMIN_MAX_ATTEMPTS)
            .putLong("lockout_until", System.currentTimeMillis() - 1_000L)
            .apply()

        ActivityScenario.launch(AdminAuthActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val btn0 = activity.findViewById<Button>(
                    com.waterfountainmachine.app.R.id.btn0
                )
                assertThat(btn0.isEnabled).isTrue()
                assertThat(prefs.getInt("failed_attempts", -1)).isEqualTo(0)
                assertThat(prefs.getLong("lockout_until", -1L)).isEqualTo(0L)
            }
        }
    }

    @Test
    fun `correct pin resets failed attempts and starts admin panel`() {
        every { AdminPinManager.verifyPin(any(), any()) } returns true
        every { AdminPinManager.isDefaultPin(any()) } returns false
        // Pre-existing failures must be wiped on success.
        prefs.edit().putInt("failed_attempts", 2).apply()

        ActivityScenario.launch(AdminAuthActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> enterPin(activity, "12345678") }
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(prefs.getInt("failed_attempts", -1)).isEqualTo(0)
            assertThat(prefs.getLong("lockout_until", -1L)).isEqualTo(0L)

            // The activity navigates to AdminPanelActivity. Robolectric
            // captures startActivity() on the application shadow.
            val nextIntent: Intent? = shadowOf(
                ApplicationProvider.getApplicationContext<android.app.Application>()
            ).nextStartedActivity
            assertThat(nextIntent).isNotNull()
            assertThat(nextIntent!!.component?.className)
                .isEqualTo(AdminPanelActivity::class.java.name)
        }
    }

    /**
     * Press the digit buttons matching [pin]. Triggers `validatePin()` when
     * the 8th digit lands.
     */
    private fun enterPin(activity: AdminAuthActivity, pin: String) {
        require(pin.length == 8) { "AdminAuthActivity validates only on 8-digit entries" }
        for (digit in pin) {
            val id = when (digit) {
                '0' -> com.waterfountainmachine.app.R.id.btn0
                '1' -> com.waterfountainmachine.app.R.id.btn1
                '2' -> com.waterfountainmachine.app.R.id.btn2
                '3' -> com.waterfountainmachine.app.R.id.btn3
                '4' -> com.waterfountainmachine.app.R.id.btn4
                '5' -> com.waterfountainmachine.app.R.id.btn5
                '6' -> com.waterfountainmachine.app.R.id.btn6
                '7' -> com.waterfountainmachine.app.R.id.btn7
                '8' -> com.waterfountainmachine.app.R.id.btn8
                '9' -> com.waterfountainmachine.app.R.id.btn9
                else -> error("non-digit '$digit' in test PIN")
            }
            activity.findViewById<Button>(id).performClick()
        }
    }
}
