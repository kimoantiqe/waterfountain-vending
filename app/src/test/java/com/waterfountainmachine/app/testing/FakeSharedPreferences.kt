package com.waterfountainmachine.app.testing

import android.content.SharedPreferences

/**
 * Minimal in-memory [SharedPreferences] fake for unit tests.
 *
 * Use with [com.waterfountainmachine.app.core.utils.SecurePreferences]:
 * ```
 * mockkObject(SecurePreferences)
 * every { SecurePreferences.getSystemSettings(any()) } returns FakeSharedPreferences()
 * ```
 *
 * Covers the surface this codebase uses: String / Int / Long / Boolean
 * get+put, contains, remove, clear. Listeners are no-ops.
 *
 * Shared by [com.waterfountainmachine.app.features.admin.utils.AdminPinManagerTest]
 * and [com.waterfountainmachine.app.features.admin.ui.AdminAuthActivityTest].
 */
class FakeSharedPreferences : SharedPreferences {
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
