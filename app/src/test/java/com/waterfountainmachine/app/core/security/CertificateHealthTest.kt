package com.waterfountainmachine.app.core.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Boundary tests for [CertificateHealth.from].
 *
 * These thresholds drive admin warnings AND the renewal worker, so they MUST
 * stay aligned with:
 *   - [SecurityModule.shouldRenewCertificate]: daysRemaining in 0..6  (Critical)
 *   - [SecurityModule.isCertificateExpiringSoon]: daysRemaining <= 30 (ExpiringSoon + Critical)
 *   - [SecurityModule.isCertificateExpired]:     daysRemaining < 0    (Expired)
 *
 * If you tweak the bands, update both the runtime checks and these tests
 * together.
 */
class CertificateHealthTest {

    @Test
    fun `null days remaining maps to NotEnrolled`() {
        assertThat(CertificateHealth.from(null)).isEqualTo(CertificateHealth.NotEnrolled)
    }

    @Test
    fun `negative days reports overdue count, not the raw negative`() {
        assertThat(CertificateHealth.from(-1))
            .isEqualTo(CertificateHealth.Expired(daysOverdue = 1))
        assertThat(CertificateHealth.from(-15))
            .isEqualTo(CertificateHealth.Expired(daysOverdue = 15))
    }

    @Test
    fun `zero days is still Critical, not Expired`() {
        // Cert is valid right up until the expiry moment. The worker is
        // expected to attempt one last renewal at this point.
        assertThat(CertificateHealth.from(0))
            .isEqualTo(CertificateHealth.Critical(daysRemaining = 0))
    }

    @Test
    fun `seven days is the upper bound of Critical`() {
        assertThat(CertificateHealth.from(7))
            .isEqualTo(CertificateHealth.Critical(daysRemaining = 7))
    }

    @Test
    fun `eight days flips to ExpiringSoon`() {
        assertThat(CertificateHealth.from(8))
            .isEqualTo(CertificateHealth.ExpiringSoon(daysRemaining = 8))
    }

    @Test
    fun `thirty days is the upper bound of ExpiringSoon`() {
        assertThat(CertificateHealth.from(30))
            .isEqualTo(CertificateHealth.ExpiringSoon(daysRemaining = 30))
    }

    @Test
    fun `thirty one days flips to Healthy`() {
        assertThat(CertificateHealth.from(31))
            .isEqualTo(CertificateHealth.Healthy(daysRemaining = 31))
    }

    @Test
    fun `long-lived certificate maps to Healthy with original day count`() {
        assertThat(CertificateHealth.from(365))
            .isEqualTo(CertificateHealth.Healthy(daysRemaining = 365))
    }
}
