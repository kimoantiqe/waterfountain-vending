package com.waterfountainmachine.app.core.security

/**
 * Single source of truth for the machine certificate's lifecycle state.
 *
 * Used by:
 *   - [SecurityModule.getCertificateHealth] for runtime checks.
 *   - [com.waterfountainmachine.app.features.admin.fragments.CertificateStatusFragment]
 *     to render the admin warning banner + status colour.
 *   - [com.waterfountainmachine.app.workers.CertificateRenewalWorker]
 *     to decide whether to renew, skip, or report expiry.
 *
 * Thresholds intentionally match the existing scheduling policy:
 *   - Renewal worker auto-renews when daysRemaining < 7 (-> Critical).
 *   - The 30-day window matches isCertificateExpiringSoon().
 *
 * This type is pure data + a pure factory so it can be unit tested without
 * touching Android Keystore.
 */
sealed class CertificateHealth {

    /** No certificate installed -- machine has not been enrolled. */
    object NotEnrolled : CertificateHealth()

    /** Certificate is valid with more than 30 days remaining. */
    data class Healthy(val daysRemaining: Int) : CertificateHealth()

    /** Certificate is valid but expires in 8-30 days. Admin should plan renewal. */
    data class ExpiringSoon(val daysRemaining: Int) : CertificateHealth()

    /**
     * Certificate is valid but expires in 0-7 days. The renewal worker is now
     * scheduled to attempt auto-renewal; if it keeps failing the admin must
     * intervene before the cert hits [Expired].
     */
    data class Critical(val daysRemaining: Int) : CertificateHealth()

    /** Certificate has expired. Machine is locked out of authenticated APIs. */
    data class Expired(val daysOverdue: Int) : CertificateHealth()

    companion object {
        /**
         * Categorise a certificate based on its `daysUntilExpiry` value.
         *
         * @param daysRemaining `null` means the machine is not enrolled.
         *     A negative value means the certificate has already expired
         *     (e.g. -3 -> 3 days overdue).
         */
        fun from(daysRemaining: Int?): CertificateHealth = when {
            daysRemaining == null -> NotEnrolled
            daysRemaining < 0 -> Expired(daysOverdue = -daysRemaining)
            daysRemaining <= 7 -> Critical(daysRemaining)
            daysRemaining <= 30 -> ExpiringSoon(daysRemaining)
            else -> Healthy(daysRemaining)
        }
    }
}
