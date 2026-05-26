package com.waterfountainmachine.app.core.backend

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [BackendMachineService] response-parsing logic.
 *
 * The Firebase Functions client itself (final classes, network I/O) is
 * deliberately NOT exercised here — its semantics belong to integration
 * tests, not unit tests. Instead we expose the pure response parsers
 * ([BackendMachineService.parseMachineStatus] and
 * [BackendMachineService.parseCertificateRenewal]) and verify they
 * accept/reject the same shapes the suspend functions do.
 *
 * If the production parsers ever drift from the JSON contract the
 * backend emits, these tests fail fast without needing a network or an
 * emulator.
 */
class BackendMachineServiceTest {

    // ---------- parseMachineStatus ----------

    @Test
    fun `parseMachineStatus accepts a minimal active payload`() {
        val result = BackendMachineService.parseMachineStatus(
            mapOf("status" to "active")
        )

        assertThat(result.isSuccess).isTrue()
        val status = result.getOrThrow()
        assertThat(status.status).isEqualTo("active")
        assertThat(status.disabledReason).isNull()
        assertThat(status.disabledAt).isNull()
    }

    @Test
    fun `parseMachineStatus accepts a fully-populated disabled payload`() {
        val result = BackendMachineService.parseMachineStatus(
            mapOf(
                "status" to "disabled",
                "disabledReason" to "maintenance",
                "disabledAt" to 1700000000000L
            )
        )

        val status = result.getOrThrow()
        assertThat(status.status).isEqualTo("disabled")
        assertThat(status.disabledReason).isEqualTo("maintenance")
        assertThat(status.disabledAt).isEqualTo(1700000000000L)
    }

    @Test
    fun `parseMachineStatus coerces an Int disabledAt to Long`() {
        // Firebase Functions JSON sometimes hands back Integer for small numbers.
        val result = BackendMachineService.parseMachineStatus(
            mapOf("status" to "disabled", "disabledAt" to 42)
        )

        assertThat(result.getOrThrow().disabledAt).isEqualTo(42L)
    }

    @Test
    fun `parseMachineStatus fails when response is not a Map`() {
        val result = BackendMachineService.parseMachineStatus("not-a-map")
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat()
            .contains("Invalid response format")
    }

    @Test
    fun `parseMachineStatus fails when response is null`() {
        val result = BackendMachineService.parseMachineStatus(null)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat()
            .contains("Invalid response format")
    }

    @Test
    fun `parseMachineStatus fails when status field is missing`() {
        val result = BackendMachineService.parseMachineStatus(
            mapOf("disabledReason" to "no status")
        )
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat()
            .contains("Missing status field")
    }

    @Test
    fun `parseMachineStatus fails when status field is wrong type`() {
        val result = BackendMachineService.parseMachineStatus(
            mapOf("status" to 42) // Int, not String
        )
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat()
            .contains("Missing status field")
    }

    // ---------- parseCertificateRenewal ----------

    @Test
    fun `parseCertificateRenewal accepts a complete payload`() {
        val result = BackendMachineService.parseCertificateRenewal(
            mapOf(
                "certificatePem" to "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----",
                "serialNumber" to "abc123",
                "expiresAt" to "2026-01-01T00:00:00Z"
            )
        )

        val cert = result.getOrThrow()
        assertThat(cert.serialNumber).isEqualTo("abc123")
        assertThat(cert.expiresAt).isEqualTo("2026-01-01T00:00:00Z")
        assertThat(cert.certificatePem).startsWith("-----BEGIN CERTIFICATE-----")
    }

    @Test
    fun `parseCertificateRenewal fails when response is not a Map`() {
        val result = BackendMachineService.parseCertificateRenewal(emptyList<Any>())
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat()
            .contains("Invalid response format")
    }

    @Test
    fun `parseCertificateRenewal fails when certificatePem is missing`() {
        val result = BackendMachineService.parseCertificateRenewal(
            mapOf("serialNumber" to "abc123", "expiresAt" to "2026-01-01T00:00:00Z")
        )
        assertThat(result.exceptionOrNull()).hasMessageThat()
            .contains("Missing certificatePem")
    }

    @Test
    fun `parseCertificateRenewal fails when serialNumber is missing`() {
        val result = BackendMachineService.parseCertificateRenewal(
            mapOf("certificatePem" to "pem-data", "expiresAt" to "2026-01-01T00:00:00Z")
        )
        assertThat(result.exceptionOrNull()).hasMessageThat()
            .contains("Missing serialNumber")
    }

    @Test
    fun `parseCertificateRenewal fails when expiresAt is missing`() {
        val result = BackendMachineService.parseCertificateRenewal(
            mapOf("certificatePem" to "pem-data", "serialNumber" to "abc123")
        )
        assertThat(result.exceptionOrNull()).hasMessageThat()
            .contains("Missing expiresAt")
    }

    @Test
    fun `parseCertificateRenewal fails when any required field is wrong type`() {
        val result = BackendMachineService.parseCertificateRenewal(
            mapOf(
                "certificatePem" to "pem-data",
                "serialNumber" to 12345, // Int, not String
                "expiresAt" to "2026-01-01T00:00:00Z"
            )
        )
        assertThat(result.exceptionOrNull()).hasMessageThat()
            .contains("Missing serialNumber")
    }
}
