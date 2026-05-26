package com.yishengkj.logging

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [SensitiveDataRedactor].
 *
 * The redactor is the last-line defence before logs leave the device for
 * Firebase. These tests pin its behaviour for every PII shape we know about
 * AND assert it does NOT redact things that merely *look* like PII
 * (e.g. timestamps, slot IDs) -- false positives hide real signal in
 * remote logs.
 */
class SensitiveDataRedactorTest {

    private val placeholder = "[REDACTED]"

    // ========== Phone numbers ==========

    @Test
    fun `redacts plain 10-digit phone`() {
        val out = SensitiveDataRedactor.redact("User entered 2345678900 today")
        assertThat(out).doesNotContain("2345678900")
        assertThat(out).contains(placeholder)
    }

    @Test
    fun `redacts dashed phone`() {
        val out = SensitiveDataRedactor.redact("Calling 234-567-8900 now")
        assertThat(out).doesNotContain("234-567-8900")
        assertThat(out).contains(placeholder)
    }

    @Test
    fun `redacts dotted phone`() {
        val out = SensitiveDataRedactor.redact("Calling 234.567.8900 now")
        assertThat(out).doesNotContain("234.567.8900")
    }

    @Test
    fun `redacts parenthesised US phone`() {
        val out = SensitiveDataRedactor.redact("Number is (234) 567-8900 confirmed")
        assertThat(out).doesNotContain("(234) 567-8900")
        assertThat(out).contains(placeholder)
    }

    @Test
    fun `redacts E164 phone`() {
        // The original PHONE_PATTERN missed this -- leading + blocks \b boundary.
        // Regression guard for the specific PII shape produced by
        // PhoneNumberUtils.normalizePhoneNumber.
        val out = SensitiveDataRedactor.redact("Sending OTP to +12345678900 over Twilio")
        assertThat(out).doesNotContain("+12345678900")
        assertThat(out).contains(placeholder)
    }

    @Test
    fun `does not redact 13-digit unix timestamps`() {
        // Timestamps are everywhere in our logs and are not PII.
        val msg = "Event at 1716580000000 completed"
        val out = SensitiveDataRedactor.redact(msg)
        assertThat(out).isEqualTo(msg)
    }

    @Test
    fun `does not redact short numeric IDs like slot numbers`() {
        val msg = "Dispensed from slot 7 in 1234 ms"
        val out = SensitiveDataRedactor.redact(msg)
        assertThat(out).isEqualTo(msg)
    }

    // ========== OTP / PIN ==========

    @Test
    fun `redacts OTP value but keeps keyword`() {
        val out = SensitiveDataRedactor.redact("Mock: received otp: 123456 ok")
        assertThat(out).doesNotContain("123456")
        assertThat(out).ignoringCase().contains("otp")
    }

    @Test
    fun `does not redact a bare 6-digit number without OTP keyword`() {
        // Conservative by design: matching every 6-digit run would redact
        // useful debug data (millisecond timings, slot IDs, error codes).
        // PII discipline at the source covers OTPs that are logged without a
        // keyword.
        val msg = "Completed in 654321 ms"
        val out = SensitiveDataRedactor.redact(msg)
        assertThat(out).isEqualTo(msg)
    }

    @Test
    fun `redacts verification code value`() {
        val out = SensitiveDataRedactor.redact("verification code 987654 entered")
        assertThat(out).doesNotContain("987654")
        assertThat(out).ignoringCase().contains("verification code")
    }

    @Test
    fun `redacts admin PIN value`() {
        val out = SensitiveDataRedactor.redact("Admin entered PIN: 9999 successfully")
        assertThat(out).doesNotContain("9999")
        assertThat(out).contains("PIN")
    }

    @Test
    fun `does not redact pin_length style debug keys`() {
        // "pin_length: 4" is metadata, not a secret. The OTP_PIN pattern requires
        // the keyword to be a word -- "pin_length" should not match.
        val msg = "Debug: pin_length: 4 ok"
        val out = SensitiveDataRedactor.redact(msg)
        assertThat(out).isEqualTo(msg)
    }

    // ========== Email ==========

    @Test
    fun `redacts email address`() {
        val out = SensitiveDataRedactor.redact("Contact admin@example.com for help")
        assertThat(out).doesNotContain("admin@example.com")
        assertThat(out).contains(placeholder)
    }

    // ========== JWT / tokens ==========

    @Test
    fun `redacts JWT token even without prefix keyword`() {
        // A JWT pasted bare into a log line still gets redacted.
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        val out = SensitiveDataRedactor.redact("Authorization header arrived: $jwt for request")
        assertThat(out).doesNotContain(jwt)
        assertThat(out).contains(placeholder)
    }

    @Test
    fun `redacts bearer token with keyword`() {
        val out = SensitiveDataRedactor.redact("authorization: abcdef0123456789xyz")
        assertThat(out).doesNotContain("abcdef0123456789xyz")
        assertThat(out).ignoringCase().contains("authorization")
    }

    @Test
    fun `redacts password with keyword`() {
        val out = SensitiveDataRedactor.redact("password=hunter2hunter2hunter2 failed")
        assertThat(out).doesNotContain("hunter2hunter2hunter2")
    }

    // ========== PEM blocks ==========

    @Test
    fun `redacts PEM certificate block including body`() {
        val pem = """
            -----BEGIN CERTIFICATE-----
            MIIDazCCAlOgAwIBAgIUVj7Q5xJgVNlk9F2zBz3xqgI7t5gwDQYJKoZIhvcNAQEL
            BQAwRTELMAkGA1UEBhMCQVUxEzARBgNVBAgMClNvbWUtU3RhdGUxITAfBgNVBAoM
            -----END CERTIFICATE-----
        """.trimIndent()
        val out = SensitiveDataRedactor.redact("Loaded cert: $pem done")
        assertThat(out).doesNotContain("MIIDazCC")
        assertThat(out).doesNotContain("BEGIN CERTIFICATE")
        assertThat(out).contains(placeholder)
    }

    @Test
    fun `redacts PEM private key block`() {
        val pem = """
            -----BEGIN RSA PRIVATE KEY-----
            VERYSECRETKEYMATERIALnevershouldbeloggedanywhere
            -----END RSA PRIVATE KEY-----
        """.trimIndent()
        val out = SensitiveDataRedactor.redact(pem)
        assertThat(out).doesNotContain("VERYSECRETKEYMATERIAL")
    }

    // ========== IP addresses ==========

    @Test
    fun `redacts IPv4 address`() {
        val out = SensitiveDataRedactor.redact("Connected to 192.168.1.42 OK")
        assertThat(out).doesNotContain("192.168.1.42")
    }

    // ========== Tag redaction ==========

    @Test
    fun `redactTag strips email from tag`() {
        val out = SensitiveDataRedactor.redactTag("user-admin@example.com")
        assertThat(out).doesNotContain("admin@example.com")
    }

    @Test
    fun `redactTag leaves regular tags alone`() {
        val tag = "VendingViewModel"
        assertThat(SensitiveDataRedactor.redactTag(tag)).isEqualTo(tag)
    }

    // ========== Combined / regression ==========

    @Test
    fun `redacts every PII type in a single message`() {
        val msg = "User admin@example.com phone +12345678900 otp: 123456 from 10.0.0.1"
        val out = SensitiveDataRedactor.redact(msg)
        assertThat(out).doesNotContain("admin@example.com")
        assertThat(out).doesNotContain("+12345678900")
        assertThat(out).doesNotContain("123456")
        assertThat(out).doesNotContain("10.0.0.1")
    }

    @Test
    fun `empty input returns empty output`() {
        assertThat(SensitiveDataRedactor.redact("")).isEqualTo("")
    }

    @Test
    fun `safe message passes through unchanged`() {
        val msg = "Dispensing started for slot 3, progress 50 percent"
        assertThat(SensitiveDataRedactor.redact(msg)).isEqualTo(msg)
    }
}
