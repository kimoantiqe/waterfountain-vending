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
 * Thresholds:
 *   - Renewal worker auto-renews when daysRemaining < [RENEWAL_THRESHOLD_DAYS]
 *     (proactive: opens well before the Critical band so a short offline /
 *     maintenance window doesn't consume the whole renewal opportunity).
 *   - The 30-day window matches isCertificateExpiringSoon().
 *
 * The display bands ([Critical] <= 7, [ExpiringSoon] <= 30) are about human
 * urgency and are deliberately independent of the renewal trigger; with a
 * 15-day trigger the worker starts renewing while still in [ExpiringSoon].
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
     * Certificate is valid but expires in 0-7 days. Renewal has already been
     * attempted for several days by this point (trigger is
     * [RENEWAL_THRESHOLD_DAYS]); reaching Critical means renewal keeps failing
     * and the admin must intervene before the cert hits [Expired].
     */
    data class Critical(val daysRemaining: Int) : CertificateHealth()

    /** Certificate has expired. Machine is locked out of authenticated APIs. */
    data class Expired(val daysOverdue: Int) : CertificateHealth()

    companion object {
        /**
         * Days-remaining threshold at which the renewal worker starts
         * attempting auto-renewal. Set to ~50% of the 30-day certificate
         * lifetime so a transient offline/maintenance period is unlikely to
         * span the entire window and force a manual re-enrollment.
         */
        const val RENEWAL_THRESHOLD_DAYS = 15

        /**
         * Should the renewal worker attempt renewal for a cert with
         * [daysRemaining] left? True only inside the pre-expiry window
         * `[0, RENEWAL_THRESHOLD_DAYS)`. An already-expired cert
         * (`daysRemaining < 0`) returns false — it can't self-renew and must
         * be re-enrolled. `null` (not enrolled) returns false.
         */
        fun shouldRenew(daysRemaining: Int?): Boolean =
            daysRemaining != null && daysRemaining in 0 until RENEWAL_THRESHOLD_DAYS

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
