package com.yishengkj.logging

/**
 * SensitiveDataRedactor - Redacts personally identifiable information (PII) from log messages
 * before they are stored or uploaded to prevent data leaks.
 * 
 * Redacts:
 * - Email addresses
 * - Phone numbers (US format)
 * - IP addresses
 * - Authentication tokens, keys, passwords
 */
object SensitiveDataRedactor {
    
    // Regex patterns for detecting sensitive data
    private val EMAIL_PATTERN = Regex(
        "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}",
        RegexOption.IGNORE_CASE
    )
    
    private val PHONE_PATTERN = Regex(
        "\\b\\d{3}[-.]?\\d{3}[-.]?\\d{4}\\b"
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
        
        // Redact emails
        redacted = EMAIL_PATTERN.replace(redacted, REDACTED_PLACEHOLDER)
        
        // Redact phone numbers
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
