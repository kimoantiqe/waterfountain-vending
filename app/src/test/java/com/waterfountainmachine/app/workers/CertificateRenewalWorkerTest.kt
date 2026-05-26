package com.waterfountainmachine.app.workers

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.waterfountainmachine.app.core.backend.BackendMachineService
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Unit tests for [CertificateRenewalWorker].
 *
 * Coverage is split into:
 *  - Pure helpers ([CertificateRenewalWorker.decideRenewalAction],
 *    [CertificateRenewalWorker.isRetryableError]) tested directly off
 *    the companion (no Robolectric needed for these — they only touch
 *    String / Int / Throwable).
 *  - End-to-end [CertificateRenewalWorker.doWork] tested with fake
 *    facades injected via a custom [WorkerFactory], wired through
 *    [TestListenableWorkerBuilder]. Robolectric is required here
 *    because `android.os.Bundle` is used inside the worker, and
 *    `runBlocking` actually drives the suspend `doWork()`.
 *
 * The application is set to `android.app.Application::class` to avoid
 * `WaterFountainApplication.onCreate` (which hits Keystore / Firebase
 * and crashes on the JVM).
 */
@RunWith(AndroidJUnit4::class)
@Config(
    manifest = Config.NONE,
    sdk = [33],
    application = Application::class,
)
class CertificateRenewalWorkerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // TestListenableWorkerBuilder needs WorkManager initialized so it
        // can build the WorkerParameters; the synchronous test init does
        // not actually run any work.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.DEBUG)
                .build()
        )
    }

    // ---------- decideRenewalAction (pure) ----------

    @Test
    fun `decideRenewalAction returns EXPIRED_FAIL when days remaining is negative`() {
        assertThat(CertificateRenewalWorker.decideRenewalAction(-1, true))
            .isEqualTo(RenewalAction.EXPIRED_FAIL)
    }

    @Test
    fun `decideRenewalAction EXPIRED_FAIL trumps shouldRenew=false`() {
        assertThat(CertificateRenewalWorker.decideRenewalAction(-30, false))
            .isEqualTo(RenewalAction.EXPIRED_FAIL)
    }

    @Test
    fun `decideRenewalAction returns NOT_NEEDED when valid and shouldRenew is false`() {
        assertThat(CertificateRenewalWorker.decideRenewalAction(30, false))
            .isEqualTo(RenewalAction.NOT_NEEDED)
    }

    @Test
    fun `decideRenewalAction returns RENEW when valid and shouldRenew is true`() {
        assertThat(CertificateRenewalWorker.decideRenewalAction(5, true))
            .isEqualTo(RenewalAction.RENEW)
    }

    @Test
    fun `decideRenewalAction treats null daysRemaining as not-expired`() {
        assertThat(CertificateRenewalWorker.decideRenewalAction(null, false))
            .isEqualTo(RenewalAction.NOT_NEEDED)
        assertThat(CertificateRenewalWorker.decideRenewalAction(null, true))
            .isEqualTo(RenewalAction.RENEW)
    }

    @Test
    fun `decideRenewalAction treats zero days remaining as still-valid`() {
        // Boundary: exactly 0 days. Not yet expired (>= 0), policy
        // delegates to shouldRenew.
        assertThat(CertificateRenewalWorker.decideRenewalAction(0, true))
            .isEqualTo(RenewalAction.RENEW)
    }

    // ---------- isRetryableError (pure) ----------

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
        assertThat(CertificateRenewalWorker.isRetryableError(RuntimeException("???")))
            .isFalse()
    }

    @Test
    fun `isRetryableError returns false for errors with null message`() {
        assertThat(CertificateRenewalWorker.isRetryableError(RuntimeException()))
            .isFalse()
    }

    // ---------- doWork() (integration via TestListenableWorkerBuilder) ----------

    @Test
    fun `doWork returns success when machine is not enrolled`() = runBlocking<Unit> {
        val security = FakeSecurity(enrolled = false)
        val backend = FakeBackend()
        val analytics = RecordingAnalytics()

        val worker = buildWorker(security, backend, analytics)
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        // Nothing should have been logged — we bailed before the analytics
        // calls.
        assertThat(analytics.events).isEmpty()
        assertThat(backend.calls).isEmpty()
    }

    @Test
    fun `doWork returns success when machine has no machineId`() = runBlocking<Unit> {
        val security = FakeSecurity(enrolled = true, machineId = null)
        val worker = buildWorker(security, FakeBackend(), RecordingAnalytics())

        assertThat(worker.doWork()).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun `doWork returns failure and logs certificate_expired event when cert is expired`() =
        runBlocking {
            val security = FakeSecurity(
                enrolled = true,
                machineId = "machine-1",
                daysUntilExpiry = -5,
                shouldRenew = true, // ignored — expiry takes precedence
            )
            val backend = FakeBackend()
            val analytics = RecordingAnalytics()

            val worker = buildWorker(security, backend, analytics)
            val result = worker.doWork()

            assertThat(result).isEqualTo(ListenableWorker.Result.failure())
            assertThat(analytics.events.map { it.first }).containsExactly("certificate_expired")
            val params = analytics.events.single().second
            assertThat(params.getString("status")).isEqualTo("expired")
            assertThat(params.getInt("days_overdue")).isEqualTo(5)
            // Backend MUST NOT be called for an expired cert — the call
            // itself would fail auth.
            assertThat(backend.calls).isEmpty()
        }

    @Test
    fun `doWork returns success and logs status_check when renewal not needed`() = runBlocking<Unit> {
        val security = FakeSecurity(
            enrolled = true,
            machineId = "machine-1",
            daysUntilExpiry = 60,
            shouldRenew = false,
        )
        val backend = FakeBackend()
        val analytics = RecordingAnalytics()

        val worker = buildWorker(security, backend, analytics)
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(analytics.events.map { it.first })
            .containsExactly("certificate_status_check")
        assertThat(analytics.events.single().second.getString("status")).isEqualTo("valid")
        assertThat(backend.calls).isEmpty()
    }

    @Test
    fun `doWork renews successfully and installs new certificate`() = runBlocking<Unit> {
        val security = FakeSecurity(
            enrolled = true,
            machineId = "machine-1",
            daysUntilExpiry = 3,
            shouldRenew = true,
        )
        val backend = FakeBackend(
            result = Result.success(
                BackendMachineService.CertificateRenewalResult(
                    certificatePem = "-----BEGIN CERTIFICATE-----\nNEW\n-----END CERTIFICATE-----",
                    serialNumber = "serial-42",
                    expiresAt = "2027-01-01T00:00:00Z",
                )
            )
        )
        val analytics = RecordingAnalytics()

        val worker = buildWorker(security, backend, analytics)
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        // Renewal attempt + success events logged, in order.
        assertThat(analytics.events.map { it.first })
            .containsExactly("certificate_renewal_attempt", "certificate_renewed").inOrder()
        // The new PEM was installed.
        assertThat(security.installedPems).containsExactly(
            "-----BEGIN CERTIFICATE-----\nNEW\n-----END CERTIFICATE-----"
        )
        assertThat(backend.calls).containsExactly("machine-1")
    }

    @Test
    fun `doWork returns retry for transient network failure`() = runBlocking<Unit> {
        val security = FakeSecurity(
            enrolled = true,
            machineId = "machine-1",
            daysUntilExpiry = 2,
            shouldRenew = true,
        )
        val backend = FakeBackend(
            result = Result.failure(RuntimeException("network unreachable"))
        )
        val analytics = RecordingAnalytics()

        val worker = buildWorker(security, backend, analytics)
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
        assertThat(analytics.events.map { it.first })
            .containsExactly("certificate_renewal_attempt", "certificate_renewal_failed")
            .inOrder()
        val failureParams = analytics.events.last().second
        assertThat(failureParams.getBoolean("retryable")).isTrue()
        assertThat(security.installedPems).isEmpty()
    }

    @Test
    fun `doWork returns failure for permanent auth error`() = runBlocking<Unit> {
        val security = FakeSecurity(
            enrolled = true,
            machineId = "machine-1",
            daysUntilExpiry = 2,
            shouldRenew = true,
        )
        val backend = FakeBackend(
            result = Result.failure(RuntimeException("Unauthorized"))
        )
        val analytics = RecordingAnalytics()

        val worker = buildWorker(security, backend, analytics)
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
        val failureParams = analytics.events.last().second
        assertThat(failureParams.getBoolean("retryable")).isFalse()
    }

    @Test
    fun `doWork returns failure and logs unexpected_error when a facade throws`() = runBlocking<Unit> {
        // Backend that throws instead of returning a Result.failure — the
        // outer try / catch in doWork() must convert it to Result.failure
        // and log certificate_renewal_error.
        val security = FakeSecurity(
            enrolled = true,
            machineId = "machine-1",
            daysUntilExpiry = 2,
            shouldRenew = true,
        )
        val backend = ThrowingBackend(IllegalStateException("boom"))
        val analytics = RecordingAnalytics()

        val worker = buildWorker(security, backend, analytics)
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
        assertThat(analytics.events.map { it.first })
            .contains("certificate_renewal_error")
    }

    // ---------- helpers ----------

    private fun buildWorker(
        security: CertificateSecurity,
        backend: CertificateBackend,
        analytics: RenewalAnalytics,
    ): CertificateRenewalWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker = CertificateRenewalWorker(
                appContext, workerParameters, security, backend, analytics
            )
        }
        return TestListenableWorkerBuilder
            .from(context, CertificateRenewalWorker::class.java)
            .setWorkerFactory(factory)
            .build() as CertificateRenewalWorker
    }
}

// ----- fakes (top-level so the test class stays readable) -----

private class FakeSecurity(
    private val enrolled: Boolean = true,
    private val machineId: String? = "machine-1",
    private val daysUntilExpiry: Int? = 30,
    private val shouldRenew: Boolean = false,
) : CertificateSecurity {
    val installedPems = mutableListOf<String>()
    override fun isEnrolled(): Boolean = enrolled
    override fun getMachineId(): String? = machineId
    override fun getDaysUntilExpiry(): Int? = daysUntilExpiry
    override fun shouldRenewCertificate(): Boolean = shouldRenew
    override fun installCertificate(pem: String) {
        installedPems += pem
    }
}

private class FakeBackend(
    private val result: Result<BackendMachineService.CertificateRenewalResult> =
        Result.failure(IllegalStateException("FakeBackend: no result configured"))
) : CertificateBackend {
    val calls = mutableListOf<String>()
    override suspend fun renewCertificate(
        machineId: String
    ): Result<BackendMachineService.CertificateRenewalResult> {
        calls += machineId
        return result
    }
}

private class ThrowingBackend(private val toThrow: Throwable) : CertificateBackend {
    override suspend fun renewCertificate(
        machineId: String
    ): Result<BackendMachineService.CertificateRenewalResult> {
        throw toThrow
    }
}

private class RecordingAnalytics : RenewalAnalytics {
    val events = mutableListOf<Pair<String, Bundle>>()
    override fun logEvent(name: String, params: Bundle) {
        events += name to params
    }
}
