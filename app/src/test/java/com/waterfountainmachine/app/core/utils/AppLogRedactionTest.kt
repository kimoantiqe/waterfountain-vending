package com.waterfountainmachine.app.core.utils

import com.google.common.truth.Truth.assertThat
import com.waterfountainmachine.app.features.admin.models.LogEntry
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for the [AppLog] -> [SensitiveDataRedactor] -> [LogCollector]
 * pipeline. We verify that PII never lands in the in-memory log buffer (which
 * the admin panel reads) even if the call site forgot to redact.
 *
 * We don't exercise `android.util.Log` here — its behavior under unit tests is
 * a no-op (returns 0) which is fine.
 */
class AppLogRedactionTest {

    @Before
    fun setup() {
        LogCollector.clear()
    }

    @After
    fun tearDown() {
        LogCollector.clear()
    }

    @Test
    fun `info logs strip E164 phone numbers from the buffer`() {
        AppLog.i("Auth", "Sending OTP to +12345678900")

        val msg = LogCollector.getLogs().first().message
        assertThat(msg).doesNotContain("+12345678900")
        assertThat(msg).contains("[REDACTED]")
    }

    @Test
    fun `error logs strip OTP codes from the buffer`() {
        AppLog.e("Auth", "Verify failed for otp: 123456")

        val msg = LogCollector.getLogs().first().message
        assertThat(msg).doesNotContain("123456")
        assertThat(msg).contains("[REDACTED]")
    }

    @Test
    fun `warning logs strip emails from the buffer`() {
        AppLog.w("Auth", "Suspicious login: user@example.com")

        val msg = LogCollector.getLogs().first().message
        assertThat(msg).doesNotContain("user@example.com")
        assertThat(msg).contains("[REDACTED]")
    }

    @Test
    fun `debug logs strip PEM blocks from the buffer`() {
        val pem = "-----BEGIN CERTIFICATE-----\nMIIB...secret...\n-----END CERTIFICATE-----"
        AppLog.d("Cert", "Loaded $pem from prefs")

        val msg = LogCollector.getLogs().first().message
        assertThat(msg).doesNotContain("MIIB")
        assertThat(msg).doesNotContain("secret")
        assertThat(msg).contains("[REDACTED]")
    }

    @Test
    fun `verbose logs strip auth tokens from the buffer`() {
        AppLog.v("Net", "Bearer: ya29.AbCdEfGhIjKlMnOpQrStUvWxYz0123456789")

        val msg = LogCollector.getLogs().first().message
        assertThat(msg).doesNotContain("ya29.AbCdEfGhIjKlMnOpQrStUvWxYz0123456789")
        assertThat(msg).contains("[REDACTED]")
    }

    @Test
    fun `tag is redacted if it contains an email`() {
        AppLog.i("user@example.com", "hello")

        val entry = LogCollector.getLogs().first()
        assertThat(entry.tag).doesNotContain("user@example.com")
        assertThat(entry.tag).contains("[REDACTED]")
    }

    @Test
    fun `level is preserved through redaction`() {
        AppLog.e("Tag", "boom: +12345678900")
        AppLog.w("Tag", "warn: +12345678900")
        AppLog.i("Tag", "info: +12345678900")
        AppLog.d("Tag", "debug: +12345678900")

        val levels = LogCollector.getLogs().map { it.level }.toSet()
        assertThat(levels).containsExactly(
            LogEntry.Level.ERROR,
            LogEntry.Level.WARNING,
            LogEntry.Level.INFO,
            LogEntry.Level.DEBUG
        )
    }

    @Test
    fun `non-sensitive messages are not mangled`() {
        AppLog.i("Net", "request completed in 42ms")

        val msg = LogCollector.getLogs().first().message
        assertThat(msg).isEqualTo("request completed in 42ms")
    }
}
