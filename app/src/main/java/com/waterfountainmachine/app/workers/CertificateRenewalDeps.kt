package com.waterfountainmachine.app.workers

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.waterfountainmachine.app.core.backend.BackendMachineService
import com.waterfountainmachine.app.core.security.SecurityModule
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin facade over [SecurityModule] for [CertificateRenewalWorker].
 *
 * We do NOT convert `SecurityModule` (a Kotlin `object`) into an
 * injectable class because it has 20+ call sites across the app and the
 * blast radius isn't worth it. Instead, the worker depends on this
 * tiny surface — only the 5 methods it actually needs — and the
 * production binding ([RealCertificateSecurity]) delegates to the
 * static `SecurityModule`. Tests bind a fake.
 */
interface CertificateSecurity {
    fun isEnrolled(): Boolean
    fun getMachineId(): String?
    fun getDaysUntilExpiry(): Int?
    fun shouldRenewCertificate(): Boolean
    fun installCertificate(pem: String)
}

/**
 * Thin facade over [BackendMachineService.renewCertificate] — see
 * [CertificateSecurity] for rationale (`BackendMachineService` is also
 * a static `getInstance()` singleton today).
 */
interface CertificateBackend {
    suspend fun renewCertificate(
        machineId: String
    ): Result<BackendMachineService.CertificateRenewalResult>
}

/**
 * Thin facade over [FirebaseAnalytics.logEvent] so tests can assert
 * which events were logged without touching Firebase.
 */
interface RenewalAnalytics {
    fun logEvent(name: String, params: Bundle)
}

// ----- production bindings -----

@Singleton
class RealCertificateSecurity @Inject constructor() : CertificateSecurity {
    override fun isEnrolled(): Boolean = SecurityModule.isEnrolled()
    override fun getMachineId(): String? = SecurityModule.getMachineId()
    override fun getDaysUntilExpiry(): Int? = SecurityModule.getDaysUntilExpiry()
    override fun shouldRenewCertificate(): Boolean = SecurityModule.shouldRenewCertificate()
    override fun installCertificate(pem: String) {
        SecurityModule.installCertificate(pem)
    }
}

@Singleton
class RealCertificateBackend @Inject constructor(
    @ApplicationContext private val context: Context,
) : CertificateBackend {
    override suspend fun renewCertificate(
        machineId: String
    ): Result<BackendMachineService.CertificateRenewalResult> =
        BackendMachineService.getInstance(context).renewCertificate(machineId)
}

@Singleton
class RealRenewalAnalytics @Inject constructor(
    @ApplicationContext private val context: Context,
) : RenewalAnalytics {
    private val analytics by lazy { FirebaseAnalytics.getInstance(context) }
    override fun logEvent(name: String, params: Bundle) {
        analytics.logEvent(name, params)
    }
}
