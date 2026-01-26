package com.waterfountainmachine.app.workers

import android.content.Context
import android.os.Bundle
import androidx.work.*
import com.google.firebase.analytics.FirebaseAnalytics
import com.waterfountainmachine.app.core.backend.BackendMachineService
import com.waterfountainmachine.app.security.SecurityModule
import com.waterfountainmachine.app.utils.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Worker to automatically renew machine certificate before expiration.
 * 
 * Renewal Strategy:
 * - Runs daily to check certificate expiry
 * - Renews when less than 7 days remaining
 * - Uses existing certificate to authenticate renewal
 * - Installs new certificate seamlessly (no downtime)
 * 
 * Scheduling:
 * - Periodic work: Every 24 hours
 * - Flexible time window: 15 minutes
 * - Exponential backoff on failure
 * 
 * Error Handling:
 * - Retries on network errors
 * - Logs failures for monitoring
 * - Does not renew expired certificates (requires re-enrollment)
 */
class CertificateRenewalWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "CertRenewalWorker"
        const val WORK_NAME = "certificate_renewal"
        private const val REPEAT_INTERVAL_HOURS = 24L
        private const val FLEX_TIME_MINUTES = 15L

        /**
         * Schedule periodic certificate renewal checks.
         * Should be called after successful enrollment.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val renewalRequest = PeriodicWorkRequestBuilder<CertificateRenewalWorker>(
                REPEAT_INTERVAL_HOURS, TimeUnit.HOURS,
                FLEX_TIME_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                renewalRequest
            )

            AppLog.d(TAG, "Certificate renewal worker scheduled")
        }

        /**
         * Cancel certificate renewal worker.
         * Should be called on unenrollment.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            AppLog.d(TAG, "Certificate renewal worker cancelled")
        }

        /**
         * Trigger an immediate check (for testing or manual refresh).
         */
        fun triggerImmediateCheck(context: Context) {
            val oneTimeRequest = OneTimeWorkRequestBuilder<CertificateRenewalWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueue(oneTimeRequest)
            AppLog.d(TAG, "Immediate certificate check triggered")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val analytics = FirebaseAnalytics.getInstance(applicationContext)
        
        try {
            AppLog.d(TAG, "Starting certificate renewal check")

            // Check if machine is enrolled
            if (!SecurityModule.isEnrolled()) {
                AppLog.w(TAG, "Machine not enrolled, skipping renewal check")
                return@withContext Result.success()
            }

            // Get machine ID
            val machineId = SecurityModule.getMachineId()
            if (machineId == null) {
                AppLog.w(TAG, "No machine ID found, skipping renewal check")
                return@withContext Result.success()
            }

            // Check if renewal is needed
            val daysRemaining = SecurityModule.getDaysUntilExpiry()
            
            // Log certificate status to Analytics
            val statusParams = Bundle().apply {
                putString("machine_id", machineId)
                putInt("days_remaining", daysRemaining ?: -999)
            }
            
            // CRITICAL: If certificate is expired, log error and fail
            if (daysRemaining != null && daysRemaining < 0) {
                AppLog.e(
                    TAG,
                    "CRITICAL: Certificate has EXPIRED. Machine locked out. " +
                    "Days overdue: ${-daysRemaining}. Manual re-enrollment required."
                )
                
                // Log critical analytics event
                statusParams.putString("status", "expired")
                statusParams.putInt("days_overdue", -daysRemaining)
                analytics.logEvent("certificate_expired", statusParams)
                
                // Return failure to trigger alerts/monitoring
                return@withContext Result.failure()
            }
            
            if (!SecurityModule.shouldRenewCertificate()) {
                AppLog.d(TAG, "Certificate renewal not needed. Days remaining: $daysRemaining")
                
                // Log valid status
                statusParams.putString("status", "valid")
                analytics.logEvent("certificate_status_check", statusParams)
                
                return@withContext Result.success()
            }

            AppLog.i(TAG, "Certificate needs renewal (< 7 days remaining)")
            
            // Log renewal attempt
            val renewalParams = Bundle().apply {
                putString("machine_id", machineId)
                putInt("days_remaining", daysRemaining ?: 0)
            }
            analytics.logEvent("certificate_renewal_attempt", renewalParams)

            // Call backend to renew certificate
            val backendService = BackendMachineService.getInstance(applicationContext)
            val result = backendService.renewCertificate(machineId)

            result.fold(
                onSuccess = { certData ->
                    // Install new certificate
                    SecurityModule.installCertificate(certData.certificatePem)
                    
                    AppLog.i(
                        TAG,
                        "Certificate renewed successfully. " +
                        "New serial: ${certData.serialNumber}, Expires: ${certData.expiresAt}"
                    )
                    
                    // Log successful renewal
                    val successParams = Bundle().apply {
                        putString("machine_id", machineId)
                        putString("new_serial", certData.serialNumber)
                        putString("expires_at", certData.expiresAt)
                    }
                    analytics.logEvent("certificate_renewed", successParams)
                    
                    return@withContext Result.success()
                },
                onFailure = { error ->
                    AppLog.e(TAG, "Certificate renewal failed", error)
                    
                    // Log failure
                    val failureParams = Bundle().apply {
                        putString("machine_id", machineId)
                        putString("error", error.message ?: "Unknown error")
                        putBoolean("retryable", isRetryableError(error))
                    }
                    analytics.logEvent("certificate_renewal_failed", failureParams)
                    
                    // Retry on network errors, fail on auth/validation errors
                    return@withContext if (isRetryableError(error)) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "Unexpected error during certificate renewal", e)
            
            // Log unexpected error
            val errorParams = Bundle().apply {
                putString("error", e.message ?: "Unknown error")
            }
            analytics.logEvent("certificate_renewal_error", errorParams)
            
            return@withContext Result.failure()
        }
    }

    /**
     * Determine if an error is retryable (network/temporary) vs permanent (auth/validation).
     */
    private fun isRetryableError(error: Throwable): Boolean {
        val message = error.message?.lowercase() ?: ""
        return when {
            message.contains("network") -> true
            message.contains("timeout") -> true
            message.contains("connection") -> true
            message.contains("unavailable") -> true
            message.contains("permission") -> false
            message.contains("unauthorized") -> false
            message.contains("invalid") -> false
            message.contains("expired") -> false
            message.contains("revoked") -> false
            else -> false // Default to non-retryable for unknown errors
        }
    }
}
