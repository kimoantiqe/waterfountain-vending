package com.waterfountainmachine.app.core.security

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SignatureException
import java.util.Base64

/**
 * Unit tests for [RequestSigner].
 *
 * Verifies the canonical signing contract:
 *   signed string = "endpoint:timestamp:nonce:payload"
 *   signature      = Base64(RSA-SHA256(privateKey, signed string))
 *
 * Uses an in-memory JCA RSA-2048 key pair -- no Android Keystore -- so the
 * tests run on plain JVM under :app:testDevDebugUnitTest.
 */
class RequestSignerTest {

    private lateinit var signer: RequestSigner
    private lateinit var keyPair: KeyPair

    @Before
    fun setup() {
        signer = RequestSigner()
        keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }

    @Test
    fun `signRequest produces a non-empty Base64 signature`() {
        val sig = signer.signRequest(
            endpoint = "requestOtpFn",
            timestamp = 1_700_000_000_000L,
            nonce = "nonce-abc",
            payload = "{\"phone\":\"+12345678900\"}",
            privateKey = keyPair.private
        )

        assertThat(sig).isNotEmpty()
        // Round-trip Base64 decoding succeeds.
        val bytes = Base64.getDecoder().decode(sig)
        // RSA-2048 signature is exactly 256 bytes.
        assertThat(bytes).hasLength(256)
    }

    @Test
    fun `signRequest is deterministic for same inputs`() {
        // RSA PKCS#1 v1.5 signatures (the JDK default for SHA256withRSA) are
        // deterministic. If the implementation ever switches to a randomized
        // scheme this test should be updated.
        val args = arrayOf("verifyOtpFn", 1_700_000_000_000L, "n1", "{\"a\":\"b\"}")
        val sig1 = signer.signRequest(args[0] as String, args[1] as Long, args[2] as String, args[3] as String, keyPair.private)
        val sig2 = signer.signRequest(args[0] as String, args[1] as Long, args[2] as String, args[3] as String, keyPair.private)

        assertThat(sig1).isEqualTo(sig2)
    }

    @Test
    fun `verifySignature returns true for matching data and public key`() {
        val endpoint = "requestOtpFn"
        val timestamp = 1_700_000_000_000L
        val nonce = "nonce-xyz"
        val payload = "{\"phone\":\"+12345678900\"}"
        val sig = signer.signRequest(endpoint, timestamp, nonce, payload, keyPair.private)

        val canonical = "$endpoint:$timestamp:$nonce:$payload"
        assertThat(signer.verifySignature(canonical, sig, keyPair.public)).isTrue()
    }

    @Test
    fun `verifySignature returns false when payload was tampered with`() {
        val sig = signer.signRequest("ep", 1L, "n", "{\"a\":\"1\"}", keyPair.private)
        // Same endpoint/timestamp/nonce but mutated payload -> signature mismatch.
        val tampered = "ep:1:n:{\"a\":\"2\"}"
        assertThat(signer.verifySignature(tampered, sig, keyPair.public)).isFalse()
    }

    @Test
    fun `verifySignature returns false when verified with a different key`() {
        val otherKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val sig = signer.signRequest("ep", 1L, "n", "p", keyPair.private)

        val canonical = "ep:1:n:p"
        assertThat(signer.verifySignature(canonical, sig, otherKey.public)).isFalse()
    }

    @Test
    fun `verifySignature returns false for malformed signature string`() {
        assertThat(signer.verifySignature("ep:1:n:p", "!!!not base64!!!", keyPair.public)).isFalse()
    }

    @Test(expected = SignatureException::class)
    fun `signRequest throws SignatureException when given a non-RSA key`() {
        val ecKey = KeyPairGenerator.getInstance("EC").generateKeyPair().private
        signer.signRequest("ep", 1L, "n", "p", ecKey)
    }
}
