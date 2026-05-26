package com.yishengkj.logging

/**
 * SensitiveDataRedactor - Redacts personally identifiable information (PII) from log messages
 * before they are stored or uploaded to prevent data leaks.
 *
 * This is the LAST-LINE defence against PII reaching remote storage. Callers should
 * still avoid logging raw secrets at the source -- this exists to catch mistakes.
 *
 * Redacts:
 * - Email addresses
 * - Phone numbers: dashed/dotted/plain 10-digit, parenthesised, and E.164 (+1...)
 * - OTP / verification code / PIN values when prefixed with the keyword
 * - PEM blocks (certificates, private keys)
 * - JWT-style tokens (three base64url segments separated by dots)
 * - IP addresses
 * - Authentication tokens, keys, passwords with explicit prefix
 */
object SensitiveDataRedactor {

    // Regex patterns for detecting sensitive data
    private val EMAIL_PATTERN = Regex(
        "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}",
        RegexOption.IGNORE_CASE
    )

    // Plain 10-digit US phone with optional separators: 234-567-8900, 234.567.8900, 2345678900
    private val PHONE_PATTERN = Regex(
        "\\b\\d{3}[-.]?\\d{3}[-.]?\\d{4}\\b"
    )

    // Parenthesised US phone: (234) 567-8900, (234)567-8900, (234) 567 8900
    private val PHONE_PAREN_PATTERN = Regex(
        "\\(\\d{3}\\)\\s?\\d{3}[\\s.-]?\\d{4}"
    )

    // E.164 international phone: +12345678900 (8-15 digits after +).
    // Word-boundary on the right prevents matching inside longer digit runs like timestamps.
    private val PHONE_E164_PATTERN = Regex(
        "\\+[1-9]\\d{7,14}\\b"
    )

    // OTP / verification code / PIN with explicit keyword. Captures the keyword
    // (group 1) so we can keep it in the redacted output but strip the digits.
    // Matches: "otp: 123456", "code = 1234", "PIN 9999", "verification code 654321"
    private val OTP_PIN_PATTERN = Regex(
        "\\b(otp|pin|verification\\s*code|code)\\b\\s*[:=]?\\s*[\"']?\\d{3,8}[\"']?",
        RegexOption.IGNORE_CASE
    )

    // PEM-encoded blocks (certificates, private keys, etc.). DOTALL so we span newlines.
    private val PEM_BLOCK_PATTERN = Regex(
        "-----BEGIN [A-Z ]+-----[\\s\\S]*?-----END [A-Z ]+-----"
    )

    // JWT: three base64url segments separated by dots, starting with the standard
    // "eyJ" header prefix (base64 of '{"').
    private val JWT_PATTERN = Regex(
        "eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"
    )

    private val IP_PATTERN = Regex(
        "\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b"
    )

    private val TOKEN_PATTERN = Regex(
        "(token|key|secret|password|authorization|bearer|auth)\\s*[:=]\\s*[\\w\\-._~+/]{10,}",
        RegexOption.IGNORE_CASE
    )

    private const val REDACTED_PLACEHOLDER = "[REDACTED]"

    /**
     * Redacts sensitive information from a log message.
     *
     * @param message The original log message that may contain sensitive data
     * @return The message with sensitive data replaced by [REDACTED]
     */
    fun redact(message: String): String {
        var redacted = message

        // PEM blocks first -- they can contain anything else (emails, base64).
        redacted = PEM_BLOCK_PATTERN.replace(redacted, REDACTED_PLACEHOLDER)

        // JWT tokens (must come before generic TOKEN_PATTERN -- they have no keyword prefix).
        redacted = JWT_PATTERN.replace(redacted, REDACTED_PLACEHOLDER)

        // Redact emails
        redacted = EMAIL_PATTERN.replace(redacted, REDACTED_PLACEHOLDER)

        // Redact OTP / PIN values while keeping the keyword for log readability.
        redacted = OTP_PIN_PATTERN.replace(redacted) { matchResult ->
            val keyword = matchResult.groupValues[1]
            "$keyword: $REDACTED_PLACEHOLDER"
        }

        // Redact phone numbers (E.164 first since it's the longest match).
        redacted = PHONE_E164_PATTERN.replace(redacted, REDACTED_PLACEHOLDER)
        redacted = PHONE_PAREN_PATTERN.replace(redacted, REDACTED_PLACEHOLDER)
        redacted = PHONE_PATTERN.replace(redacted, REDACTED_PLACEHOLDER)

        // Redact IP addresses
        redacted = IP_PATTERN.replace(redacted, REDACTED_PLACEHOLDER)

        // Redact tokens/keys/passwords
        redacted = TOKEN_PATTERN.replace(redacted) { matchResult ->
            val prefix = matchResult.groupValues[1]
            "$prefix: $REDACTED_PLACEHOLDER"
        }

        return redacted
    }
    
    /**
     * Redacts sensitive information from a tag.
     * Typically tags are safe, but this provides an extra layer of protection.
     * 
     * @param tag The log tag
     * @return The tag with sensitive data replaced by [REDACTED]
     */
    fun redactTag(tag: String): String {
        // Only redact emails and tokens from tags (less aggressive)
        var redacted = EMAIL_PATTERN.replace(tag, REDACTED_PLACEHOLDER)
        redacted = TOKEN_PATTERN.replace(redacted) { matchResult ->
            val prefix = matchResult.groupValues[1]
            "$prefix: $REDACTED_PLACEHOLDER"
        }
        return redacted
    }
}
