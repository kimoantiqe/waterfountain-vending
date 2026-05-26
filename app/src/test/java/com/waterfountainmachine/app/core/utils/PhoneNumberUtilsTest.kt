package com.waterfountainmachine.app.core.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [PhoneNumberUtils]. These are normalization, validation,
 * and masking helpers used on the hot path between the SMS entry screen
 * and the backend (which then hashes for storage). Mis-normalizing a
 * number means the user's verification SMS never arrives; under-masking
 * means PII leaks into device logs. Both are user-visible regressions
 * that pure-logic tests catch cheaply.
 */
class PhoneNumberUtilsTest {

    // --- normalizePhoneNumber -------------------------------------------

    @Test
    fun `normalizePhoneNumber adds +1 to a bare US ten-digit number`() {
        assertThat(PhoneNumberUtils.normalizePhoneNumber("2345678900"))
            .isEqualTo("+12345678900")
    }

    @Test
    fun `normalizePhoneNumber strips dashes spaces parens and dots`() {
        assertThat(PhoneNumberUtils.normalizePhoneNumber("+1 234-567.8900"))
            .isEqualTo("+12345678900")
        assertThat(PhoneNumberUtils.normalizePhoneNumber("(234) 567-8900"))
            .isEqualTo("+12345678900")
        assertThat(PhoneNumberUtils.normalizePhoneNumber("234.567.8900"))
            .isEqualTo("+12345678900")
    }

    @Test
    fun `normalizePhoneNumber leaves already-normalized E164 alone`() {
        assertThat(PhoneNumberUtils.normalizePhoneNumber("+12345678900"))
            .isEqualTo("+12345678900")
    }

    @Test
    fun `normalizePhoneNumber rejects US numbers whose area code begins with 0 or 1`() {
        // The US ten-digit fast path requires the first digit to be 2-9
        // (NANP rule: area codes cannot start with 0 or 1). When that
        // guard fails the function falls back to "prepend '+'" --
        // documented here so a refactor cannot silently broaden the
        // ten-digit path.
        assertThat(PhoneNumberUtils.normalizePhoneNumber("1234567890"))
            .isEqualTo("+1234567890")
        assertThat(PhoneNumberUtils.normalizePhoneNumber("0234567890"))
            .isEqualTo("+0234567890")
    }

    @Test
    fun `normalizePhoneNumber prepends + to non-ten-digit international numbers`() {
        assertThat(PhoneNumberUtils.normalizePhoneNumber("442071838750"))
            .isEqualTo("+442071838750")
    }

    @Test
    fun `normalizePhoneNumber is idempotent`() {
        val once = PhoneNumberUtils.normalizePhoneNumber("(234) 567-8900")
        val twice = PhoneNumberUtils.normalizePhoneNumber(once)
        assertThat(twice).isEqualTo(once)
    }

    // --- isValidE164 ----------------------------------------------------

    @Test
    fun `isValidE164 accepts canonical US format`() {
        assertThat(PhoneNumberUtils.isValidE164("+12345678900")).isTrue()
    }

    @Test
    fun `isValidE164 accepts short international numbers above two characters`() {
        assertThat(PhoneNumberUtils.isValidE164("+15")).isTrue() // + plus 2 digits
    }

    @Test
    fun `isValidE164 rejects missing plus, leading zero, length one, length over fifteen`() {
        assertThat(PhoneNumberUtils.isValidE164("12345678900")).isFalse() // no +
        assertThat(PhoneNumberUtils.isValidE164("+0234567890")).isFalse() // leading 0
        assertThat(PhoneNumberUtils.isValidE164("+1")).isFalse()          // 1 digit
        assertThat(PhoneNumberUtils.isValidE164("+1234567890123456")).isFalse() // 16 digits
        assertThat(PhoneNumberUtils.isValidE164("+1abc")).isFalse()       // non-digits
        assertThat(PhoneNumberUtils.isValidE164("")).isFalse()
    }

    // --- formatUsPhoneForDisplay ----------------------------------------

    @Test
    fun `formatUsPhoneForDisplay formats ten digits as parens-space-dash`() {
        assertThat(PhoneNumberUtils.formatUsPhoneForDisplay("2345678900"))
            .isEqualTo("(234) 567-8900")
    }

    @Test
    fun `formatUsPhoneForDisplay returns input unchanged when not exactly ten digits`() {
        // The helper does no normalization -- it is a UI formatter for
        // raw entry. Non-ten-digit input must round-trip so the caller
        // can decide how to render an invalid entry.
        assertThat(PhoneNumberUtils.formatUsPhoneForDisplay("12345"))
            .isEqualTo("12345")
        assertThat(PhoneNumberUtils.formatUsPhoneForDisplay("+12345678900"))
            .isEqualTo("+12345678900")
        assertThat(PhoneNumberUtils.formatUsPhoneForDisplay(""))
            .isEqualTo("")
    }

    // --- maskPhoneForLogging --------------------------------------------

    @Test
    fun `maskPhoneForLogging keeps US country code and last four digits`() {
        assertThat(PhoneNumberUtils.maskPhoneForLogging("+12345678900"))
            .isEqualTo("+1***-***-8900")
        assertThat(PhoneNumberUtils.maskPhoneForLogging("2345678900"))
            .isEqualTo("+1***-***-8900")
        assertThat(PhoneNumberUtils.maskPhoneForLogging("(234) 567-8900"))
            .isEqualTo("+1***-***-8900")
    }

    @Test
    fun `maskPhoneForLogging drops the country code prefix for non-US numbers`() {
        // International numbers don't get a "+44" prefix in the mask --
        // the masker only special-cases "+1". This test pins that
        // behaviour so a future refactor that broadens the prefix
        // changes the masking-output contract intentionally.
        assertThat(PhoneNumberUtils.maskPhoneForLogging("+442071838750"))
            .isEqualTo("***-***-8750")
    }

    @Test
    fun `maskPhoneForLogging emits stars for inputs that normalize to under four characters`() {
        // Empty string normalizes to "+" (length 1), well under the 4-char
        // floor, so we return the sentinel.
        assertThat(PhoneNumberUtils.maskPhoneForLogging("")).isEqualTo("***")
        assertThat(PhoneNumberUtils.maskPhoneForLogging("12")).isEqualTo("***")
    }

    @Test
    fun `maskPhoneForLogging never echoes the full input`() {
        // Defence-in-depth: regardless of input, the masked string must
        // not contain the original full normalized form.
        val inputs = listOf(
            "+12345678900",
            "2345678900",
            "+442071838750",
            "(234) 567-8900",
            "+1-555-867-5309",
        )
        for (input in inputs) {
            val masked = PhoneNumberUtils.maskPhoneForLogging(input)
            val normalized = PhoneNumberUtils.normalizePhoneNumber(input)
            assertThat(masked).doesNotContain(normalized.drop(2)) // body, not country code
        }
    }
}
