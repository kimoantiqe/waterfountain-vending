package com.waterfountainmachine.app.features.admin.utils

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.waterfountainmachine.app.core.utils.SecurePreferences
import com.waterfountainmachine.app.testing.FakeSharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AdminPinManager].
 *
 * Pure JVM test (no Robolectric) — we stub [SecurePreferences.getSystemSettings]
 * with a [FakeSharedPreferences] so the real hash / verify / lockout logic runs
 * without needing AndroidKeyStore or EncryptedSharedPreferences.
 */
class AdminPinManagerTest {

    private lateinit var context: Context
    private lateinit var prefs: FakeSharedPreferences

    private val correctPin = "12345678"
    private val wrongPin = "87654321"
    private val defaultPin = "01121999"

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        prefs = FakeSharedPreferences()
        mockkObject(SecurePreferences)
        every { SecurePreferences.getSystemSettings(any()) } returns prefs

        // changePin() logs a successful rotation to Crashlytics; stub it out so
        // pure-JVM tests don't try to spin up Firebase.
        mockkStatic(FirebaseCrashlytics::class)
        val crashlytics = mockk<FirebaseCrashlytics>(relaxed = true)
        every { FirebaseCrashlytics.getInstance() } returns crashlytics
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `initialize on fresh install installs the default PIN`() {
        AdminPinManager.initialize(context)

        assertThat(AdminPinManager.verifyPin(context, defaultPin)).isTrue()
        assertThat(AdminPinManager.isDefaultPin(context)).isTrue()
    }

    @Test
    fun `initialize is a no-op when a PIN is already configured`() {
        AdminPinManager.setPin(context, correctPin)

        AdminPinManager.initialize(context)

        assertThat(AdminPinManager.verifyPin(context, correctPin)).isTrue()
        assertThat(AdminPinManager.verifyPin(context, defaultPin)).isFalse()
    }

    @Test
    fun `setPin accepts a valid 8-digit PIN`() {
        assertThat(AdminPinManager.setPin(context, correctPin)).isTrue()
        assertThat(AdminPinManager.verifyPin(context, correctPin)).isTrue()
    }

    @Test
    fun `setPin rejects a PIN shorter than 8 digits`() {
        assertThat(AdminPinManager.setPin(context, "1234567")).isFalse()
    }

    @Test
    fun `setPin rejects a PIN longer than 8 digits`() {
        assertThat(AdminPinManager.setPin(context, "123456789")).isFalse()
    }

    @Test
    fun `setPin rejects a PIN that contains non-digits`() {
        assertThat(AdminPinManager.setPin(context, "1234abcd")).isFalse()
    }

    @Test
    fun `setPin replaces the previously stored PIN`() {
        AdminPinManager.setPin(context, correctPin)
        AdminPinManager.setPin(context, "99999999")

        assertThat(AdminPinManager.verifyPin(context, correctPin)).isFalse()
        assertThat(AdminPinManager.verifyPin(context, "99999999")).isTrue()
    }

    @Test
    fun `verifyPin returns false when no PIN is configured`() {
        assertThat(AdminPinManager.verifyPin(context, correctPin)).isFalse()
    }

    @Test
    fun `verifyPin returns true for the stored PIN`() {
        AdminPinManager.setPin(context, correctPin)
        assertThat(AdminPinManager.verifyPin(context, correctPin)).isTrue()
    }

    @Test
    fun `verifyPin returns false for a wrong PIN`() {
        AdminPinManager.setPin(context, correctPin)
        assertThat(AdminPinManager.verifyPin(context, wrongPin)).isFalse()
    }

    @Test
    fun `verifyPin survives the salt-is-random round trip`() {
        AdminPinManager.setPin(context, correctPin)
        val hash1 = prefs.getString("admin_pin_hash", null)

        AdminPinManager.setPin(context, correctPin)
        val hash2 = prefs.getString("admin_pin_hash", null)

        assertThat(hash1).isNotNull()
        assertThat(hash2).isNotNull()
        assertThat(hash1).isNotEqualTo(hash2)
        assertThat(AdminPinManager.verifyPin(context, correctPin)).isTrue()
    }

    @Test
    fun `validatePin matches verifyPin`() {
        AdminPinManager.setPin(context, correctPin)
        assertThat(AdminPinManager.validatePin(context, correctPin)).isTrue()
        assertThat(AdminPinManager.validatePin(context, wrongPin)).isFalse()
    }

    @Test
    fun `changePin succeeds when current PIN is correct and new PIN is valid`() {
        AdminPinManager.setPin(context, correctPin)

        val ok = AdminPinManager.changePin(context, correctPin, "55555555")

        assertThat(ok).isTrue()
        assertThat(AdminPinManager.verifyPin(context, correctPin)).isFalse()
        assertThat(AdminPinManager.verifyPin(context, "55555555")).isTrue()
    }

    @Test
    fun `changePin rejects when current PIN is wrong`() {
        AdminPinManager.setPin(context, correctPin)

        val ok = AdminPinManager.changePin(context, wrongPin, "55555555")

        assertThat(ok).isFalse()
        assertThat(AdminPinManager.verifyPin(context, correctPin)).isTrue()
    }

    @Test
    fun `changePin rejects when new PIN equals current PIN`() {
        AdminPinManager.setPin(context, correctPin)

        val ok = AdminPinManager.changePin(context, correctPin, correctPin)

        assertThat(ok).isFalse()
    }

    @Test
    fun `changePin rejects when new PIN is invalid`() {
        AdminPinManager.setPin(context, correctPin)

        val ok = AdminPinManager.changePin(context, correctPin, "abc")

        assertThat(ok).isFalse()
        assertThat(AdminPinManager.verifyPin(context, correctPin)).isTrue()
    }

    @Test
    fun `isDefaultPin returns true on fresh install and false after rotation`() {
        AdminPinManager.initialize(context)
        assertThat(AdminPinManager.isDefaultPin(context)).isTrue()

        AdminPinManager.setPin(context, correctPin)
        assertThat(AdminPinManager.isDefaultPin(context)).isFalse()
    }

    @Test
    fun `isDefaultPinValue identifies the default PIN string`() {
        assertThat(AdminPinManager.isDefaultPinValue(defaultPin)).isTrue()
        assertThat(AdminPinManager.isDefaultPinValue(correctPin)).isFalse()
        assertThat(AdminPinManager.isDefaultPinValue("")).isFalse()
    }

    @Test
    fun `rate limit state defaults to zero attempts and zero lockout`() {
        val (attempts, lockout) = AdminPinManager.getRateLimitState(context)
        assertThat(attempts).isEqualTo(0)
        assertThat(lockout).isEqualTo(0L)
    }

    @Test
    fun `saveRateLimitState persists attempts and lockout`() {
        AdminPinManager.saveRateLimitState(
            context,
            failedAttempts = 3,
            lockoutUntilTimestamp = 999_999L
        )

        val (attempts, lockout) = AdminPinManager.getRateLimitState(context)
        assertThat(attempts).isEqualTo(3)
        assertThat(lockout).isEqualTo(999_999L)
    }

    // --- PBKDF2 migration ---------------------------------------------------

    @Test
    fun `setPin writes pbkdf2 algo tag`() {
        AdminPinManager.setPin(context, correctPin)
        assertThat(prefs.getString("admin_pin_algo", null))
            .isEqualTo(AdminPinManager.ALGO_PBKDF2_V1)
    }

    @Test
    fun `pbkdf2 hash differs from legacy sha256 hash for same pin and salt`() {
        val salt = ByteArray(32) { it.toByte() }
        val legacy = AdminPinManager.hashPinLegacy(correctPin, salt)
        val modern = AdminPinManager.hashPinPbkdf2(correctPin, salt)
        assertThat(modern).isNotEqualTo(legacy)
    }

    @Test
    fun `verifyPin accepts a legacy sha256 hash and migrates it in place`() {
        // Simulate a device upgraded from the old salted-SHA-256 build:
        // hash + salt present, but no algo tag.
        val salt = ByteArray(32) { it.toByte() }
        val legacyHash = AdminPinManager.hashPinLegacy(correctPin, salt)
        prefs.edit()
            .putString("admin_pin_hash", legacyHash)
            .putString(
                "admin_pin_salt",
                java.util.Base64.getEncoder().encodeToString(salt)
            )
            .apply()
        // Sanity: algo tag is absent → legacy path.
        assertThat(prefs.getString("admin_pin_algo", null)).isNull()

        // First verify with the correct PIN succeeds AND upgrades the hash.
        assertThat(AdminPinManager.verifyPin(context, correctPin)).isTrue()

        assertThat(prefs.getString("admin_pin_algo", null))
            .isEqualTo(AdminPinManager.ALGO_PBKDF2_V1)
        // Hash is now the PBKDF2 derivative, no longer the legacy SHA-256.
        assertThat(prefs.getString("admin_pin_hash", null)).isNotEqualTo(legacyHash)

        // Subsequent verifies continue to work against the upgraded hash.
        assertThat(AdminPinManager.verifyPin(context, correctPin)).isTrue()
        assertThat(AdminPinManager.verifyPin(context, wrongPin)).isFalse()
    }

    @Test
    fun `verifyPin does not migrate when wrong pin is supplied against a legacy hash`() {
        val salt = ByteArray(32) { it.toByte() }
        val legacyHash = AdminPinManager.hashPinLegacy(correctPin, salt)
        prefs.edit()
            .putString("admin_pin_hash", legacyHash)
            .putString(
                "admin_pin_salt",
                java.util.Base64.getEncoder().encodeToString(salt)
            )
            .apply()

        assertThat(AdminPinManager.verifyPin(context, wrongPin)).isFalse()

        // Wrong PIN must NOT trigger any write -- algo tag still absent,
        // hash still the original legacy SHA-256.
        assertThat(prefs.getString("admin_pin_algo", null)).isNull()
        assertThat(prefs.getString("admin_pin_hash", null)).isEqualTo(legacyHash)
    }
}
