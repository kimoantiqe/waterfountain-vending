package com.waterfountainmachine.app.core.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Base64

/**
 * Unit tests for [NonceGenerator].
 *
 * The backend relies on nonces being unique-per-request and URL-safe
 * (no '/' or '+', no '=' padding) so they can be used as Firestore
 * document IDs for replay protection.
 */
class NonceGeneratorTest {

    private val generator = NonceGenerator()

    @Test
    fun `generate produces 32 random bytes encoded with URL-safe base64`() {
        val nonce = generator.generate()

        // 32 raw bytes -> 43 base64url chars without padding.
        assertThat(nonce).hasLength(43)
        // URL-safe alphabet only.
        assertThat(nonce).matches("^[A-Za-z0-9_-]+$")
        // Decodable.
        val bytes = Base64.getUrlDecoder().decode(nonce)
        assertThat(bytes).hasLength(32)
    }

    @Test
    fun `generate never returns the same value twice across many calls`() {
        // 10k samples is well within the birthday bound for 256 bits of
        // entropy. A collision here means the RNG is broken.
        val seen = HashSet<String>()
        repeat(10_000) { seen.add(generator.generate()) }
        assertThat(seen).hasSize(10_000)
    }

    @Test
    fun `generated nonces never contain Firestore-incompatible characters`() {
        // Firestore document IDs disallow '/'. Base64url avoids '+' and '/'.
        // Padding '=' is also stripped. Sample a batch and check.
        repeat(1_000) {
            val nonce = generator.generate()
            assertThat(nonce).doesNotContain("/")
            assertThat(nonce).doesNotContain("+")
            assertThat(nonce).doesNotContain("=")
        }
    }

    @Test
    fun `singleton instance generates same length as direct instance`() {
        assertThat(NonceGenerator.instance.generate()).hasLength(43)
    }
}
