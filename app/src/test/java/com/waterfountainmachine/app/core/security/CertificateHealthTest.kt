package com.waterfountainmachine.app.core.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Boundary tests for [CertificateHealth.from] and
 * [CertificateHealth.shouldRenew].
 *
 * These thresholds drive admin warnings AND the renewal worker, so they MUST
 * stay aligned with:
 *   - [SecurityModule.shouldRenewCertificate]: delegates to
 *     [CertificateHealth.shouldRenew] — daysRemaining in
 *     0 until [CertificateHealth.RENEWAL_THRESHOLD_DAYS].
 *   - [SecurityModule.isCertificateExpiringSoon]: daysRemaining <= 30 (ExpiringSoon + Critical)
 *   - [SecurityModule.isCertificateExpired]:     daysRemaining < 0    (Expired)
 *
 * The display bands (Critical <= 7, ExpiringSoon <= 30) are intentionally
 * independent of the renewal trigger ([RENEWAL_THRESHOLD_DAYS] = 15): the
 * worker starts renewing while the cert is still in the ExpiringSoon band.
 *
 * If you tweak the bands or the threshold, update both the runtime checks and
 * these tests together.
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

    // ---------- shouldRenew (renewal trigger) ----------

    @Test
    fun `renewal threshold is fifteen days`() {
        // Guards the ~50%-of-lifetime policy: if the 30-day cert lifetime or
        // the threshold changes, this test forces a deliberate update.
        assertThat(CertificateHealth.RENEWAL_THRESHOLD_DAYS).isEqualTo(15)
    }

    @Test
    fun `shouldRenew is false when not enrolled`() {
        assertThat(CertificateHealth.shouldRenew(null)).isFalse()
    }

    @Test
    fun `shouldRenew is false for an already-expired cert`() {
        // An expired cert can't self-renew (renewal needs a valid client
        // cert); it must be re-enrolled, so we must NOT report renewable.
        assertThat(CertificateHealth.shouldRenew(-1)).isFalse()
        assertThat(CertificateHealth.shouldRenew(-30)).isFalse()
    }

    @Test
    fun `shouldRenew is true at zero days remaining`() {
        assertThat(CertificateHealth.shouldRenew(0)).isTrue()
    }

    @Test
    fun `shouldRenew is true just inside the window at fourteen days`() {
        assertThat(CertificateHealth.shouldRenew(14)).isTrue()
    }

    @Test
    fun `shouldRenew is false exactly at the threshold of fifteen days`() {
        // Window is [0, 15) — 15 days is outside, renewal not yet due.
        assertThat(CertificateHealth.shouldRenew(15)).isFalse()
    }

    @Test
    fun `shouldRenew is false well before the window`() {
        assertThat(CertificateHealth.shouldRenew(20)).isFalse()
        assertThat(CertificateHealth.shouldRenew(30)).isFalse()
    }

    @Test
    fun `shouldRenew covers the old seven-day trigger point`() {
        // Regression guard: the previous 7-day trigger must still renew under
        // the widened window.
        assertThat(CertificateHealth.shouldRenew(6)).isTrue()
        assertThat(CertificateHealth.shouldRenew(7)).isTrue()
    }
}
