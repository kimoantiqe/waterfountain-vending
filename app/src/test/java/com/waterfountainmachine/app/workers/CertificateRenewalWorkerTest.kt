package com.waterfountainmachine.app.workers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for the pure policy helpers on [CertificateRenewalWorker].
 *
 * The worker itself currently uses `SecurityModule` (a Kotlin `object`)
 * and `BackendMachineService.getInstance(applicationContext)` via static
 * calls, plus `FirebaseAnalytics.getInstance(...)`, so its `doWork()` is
 * not unit-testable without one of:
 *   - converting to `@HiltWorker` + `@AssistedInject` (recommended
 *     follow-up; requires `androidx.hilt:hilt-work`, swapping
 *     SecurityModule to an interface, and registering
 *     HiltWorkerFactory in Application),
 *   - or PowerMock-style static mocking (rejected — fragile).
 *
 * In the meantime, we test the two decision policies that drive
 * `doWork()`:
 *
 *   1. [CertificateRenewalWorker.Companion.decideRenewalAction] — picks
 *      EXPIRED_FAIL / NOT_NEEDED / RENEW from the days-until-expiry
 *      reading and SecurityModule.shouldRenewCertificate flag.
 *   2. [CertificateRenewalWorker.Companion.isRetryableError] — decides
 *      between WorkManager Result.retry() and Result.failure() based on
 *      the exception message keyword.
 *
 * These cover every branch the worker can take after its dependency
 * lookups succeed.
 */
class CertificateRenewalWorkerTest {

    // ---------- decideRenewalAction ----------

    @Test
    fun `decideRenewalAction returns EXPIRED_FAIL when days remaining is negative`() {
        val action = CertificateRenewalWorker.decideRenewalAction(
            daysRemaining = -1,
            shouldRenew = true
        )
        assertThat(action).isEqualTo(RenewalAction.EXPIRED_FAIL)
    }

    @Test
    fun `decideRenewalAction returns EXPIRED_FAIL even when shouldRenew is false`() {
        // Expiry trumps the shouldRenew flag — we never silently succeed on
        // an expired cert.
        val action = CertificateRenewalWorker.decideRenewalAction(
            daysRemaining = -30,
            shouldRenew = false
        )
        assertThat(action).isEqualTo(RenewalAction.EXPIRED_FAIL)
    }

    @Test
    fun `decideRenewalAction returns NOT_NEEDED when cert is valid and shouldRenew is false`() {
        val action = CertificateRenewalWorker.decideRenewalAction(
            daysRemaining = 30,
            shouldRenew = false
        )
        assertThat(action).isEqualTo(RenewalAction.NOT_NEEDED)
    }

    @Test
    fun `decideRenewalAction returns RENEW when cert is valid but shouldRenew is true`() {
        val action = CertificateRenewalWorker.decideRenewalAction(
            daysRemaining = 5,
            shouldRenew = true
        )
        assertThat(action).isEqualTo(RenewalAction.RENEW)
    }

    @Test
    fun `decideRenewalAction treats null daysRemaining as not-expired`() {
        // Null means SecurityModule could not read an expiry — treat as
        // unknown / not-expired and let shouldRenew decide.
        assertThat(
            CertificateRenewalWorker.decideRenewalAction(null, shouldRenew = false)
        ).isEqualTo(RenewalAction.NOT_NEEDED)
        assertThat(
            CertificateRenewalWorker.decideRenewalAction(null, shouldRenew = true)
        ).isEqualTo(RenewalAction.RENEW)
    }

    @Test
    fun `decideRenewalAction treats zero days remaining as still-valid (renews if requested)`() {
        // Boundary: exactly 0 days. Not yet expired (>= 0), policy delegates
        // to shouldRenew.
        assertThat(
            CertificateRenewalWorker.decideRenewalAction(0, shouldRenew = true)
        ).isEqualTo(RenewalAction.RENEW)
    }

    // ---------- isRetryableError ----------

    @Test
    fun `isRetryableError returns true for transient network keywords`() {
        listOf(
            "Network unreachable",
            "Connection refused",
            "Read timeout",
            "Service unavailable",
            "NETWORK error", // case-insensitive
        ).forEach { msg ->
            assertThat(CertificateRenewalWorker.isRetryableError(RuntimeException(msg)))
                .isTrue()
        }
    }

    @Test
    fun `isRetryableError returns false for auth or validation keywords`() {
        listOf(
            "Permission denied",
            "Unauthorized",
            "Invalid certificate",
            "Certificate has expired",
            "Certificate revoked"
        ).forEach { msg ->
            assertThat(CertificateRenewalWorker.isRetryableError(RuntimeException(msg)))
                .isFalse()
        }
    }

    @Test
    fun `isRetryableError returns false for unknown errors (safe default)`() {
        // Anything we don't recognize is treated as non-retryable so
        // WorkManager doesn't burn battery on an unrecoverable failure.
        assertThat(
            CertificateRenewalWorker.isRetryableError(RuntimeException("???"))
        ).isFalse()
    }

    @Test
    fun `isRetryableError returns false for errors with null message`() {
        // Null message must not crash the helper.
        assertThat(
            CertificateRenewalWorker.isRetryableError(RuntimeException())
        ).isFalse()
    }
}
