package com.waterfountainmachine.app.features.admin.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.waterfountainmachine.app.core.utils.SecurePreferences
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
}

/**
 * Minimal in-memory [SharedPreferences] fake. Just enough surface for
 * [AdminPinManager]: String / Int / Long get+put, contains, remove, clear.
 */
private class FakeSharedPreferences : SharedPreferences {
    private val map = mutableMapOf<String, Any?>()

    override fun getAll(): Map<String, *> = map.toMap()
    override fun getString(key: String, defValue: String?): String? =
        map[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        @Suppress("UNCHECKED_CAST") (map[key] as? Set<String>) ?: defValues
    override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        map[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor()
    override fun registerOnSharedPreferenceChangeListener(
        l: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        l: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) =
            apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removals += key }
        override fun clear() = apply { clearAll = true }

        override fun commit(): Boolean {
            flush()
            return true
        }
        override fun apply() {
            flush()
        }
        private fun flush() {
            if (clearAll) map.clear()
            removals.forEach { map.remove(it) }
            map.putAll(pending)
        }
    }
}
