package com.waterfountainmachine.app.core.backend

import android.content.Context
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import com.waterfountainmachine.app.core.security.SecurityModule
import com.waterfountainmachine.app.core.utils.AppLog
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/**
 * Backend Machine Service
 * 
 * Handles certificate-authenticated communication with backend machine functions
 * Used for checking machine status (enable/disable state)
 */
class BackendMachineService private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "BackendMachineService"
        
        @Volatile
        private var INSTANCE: BackendMachineService? = null
        
        fun getInstance(context: Context): BackendMachineService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BackendMachineService(context.applicationContext).also { INSTANCE = it }
            }
        }

        /**
         * Pure parser for the `getMachineStatus` callable response. Extracted so
         * it can be unit-tested without mocking the Firebase Functions client.
         * Returns a failing [Result] for the same missing/invalid-field cases
         * the production call path rejects.
         */
        internal fun parseMachineStatus(data: Any?): Result<MachineStatus> {
            val map = data as? Map<*, *>
                ?: return Result.failure(Exception("Invalid response format"))
            val status = map["status"] as? String
                ?: return Result.failure(Exception("Missing status field"))
            return Result.success(
                MachineStatus(
                    status = status,
                    disabledReason = map["disabledReason"] as? String,
                    disabledMessage = map["disabledMessage"] as? String,
                    disabledAt = (map["disabledAt"] as? Number)?.toLong()
                )
            )
        }

        /**
         * Pure parser for the `renewCertificate` callable response. See
         * [parseMachineStatus] for rationale.
         */
        internal fun parseCertificateRenewal(data: Any?): Result<CertificateRenewalResult> {
            val map = data as? Map<*, *>
                ?: return Result.failure(Exception("Invalid response format"))
            val certificatePem = map["certificatePem"] as? String
                ?: return Result.failure(Exception("Missing certificatePem field"))
            val serialNumber = map["serialNumber"] as? String
                ?: return Result.failure(Exception("Missing serialNumber field"))
            val expiresAt = map["expiresAt"] as? String
                ?: return Result.failure(Exception("Missing expiresAt field"))
            return Result.success(
                CertificateRenewalResult(
                    certificatePem = certificatePem,
                    serialNumber = serialNumber,
                    expiresAt = expiresAt
                )
            )
        }
    }
    
    // Lazy so unit tests that exercise pure callers (e.g. MachineHealth
    // counter logic) do not need FirebaseApp.initializeApp() up front.
    private val functions: FirebaseFunctions by lazy { Firebase.functions }
    
    /**
     * Machine status data class
     */
    data class MachineStatus(
        val status: String,
        val disabledReason: String?,
        val disabledMessage: String?,
        val disabledAt: Long?
    )
    
    /**
     * Get machine status from backend
     * Returns current enable/disable state
     */
    suspend fun getMachineStatus(machineId: String): Result<MachineStatus> {
        return try {
            AppLog.d(TAG, "Checking machine status for: $machineId")
            
            val payload = JSONObject().apply {
                put("machineId", machineId)
            }
            
            val authenticatedRequest = SecurityModule.createAuthenticatedRequest(
                endpoint = "getMachineStatus",
                payload = payload
            )
            
            val result = functions
                .getHttpsCallable("getMachineStatus")
                .call(authenticatedRequest.toMap())
                .await()

            val parsed = parseMachineStatus(result.data)
            parsed.onSuccess { AppLog.d(TAG, "Machine status: ${it.status}") }
            parsed
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to get machine status", e)
            Result.failure(e)
        }
    }
    
    /**
     * Certificate renewal response data class
     */
    data class CertificateRenewalResult(
        val certificatePem: String,
        val serialNumber: String,
        val expiresAt: String
    )
    
    /**
     * Renew machine certificate (automatic rotation)
     * Uses existing certificate to authenticate the renewal request
     * 
     * @return New certificate data on success
     */
    suspend fun renewCertificate(machineId: String): Result<CertificateRenewalResult> {
        return try {
            AppLog.d(TAG, "Renewing certificate for machine: $machineId")
            
            val payload = JSONObject().apply {
                put("machineId", machineId)
            }
            
            val authenticatedRequest = SecurityModule.createAuthenticatedRequest(
                endpoint = "renewCertificate",
                payload = payload
            )
            
            val result = functions
                .getHttpsCallable("renewCertificate")
                .call(authenticatedRequest.toMap())
                .await()

            val parsed = parseCertificateRenewal(result.data)
            parsed.onSuccess { AppLog.d(TAG, "Certificate renewed successfully. New serial: ${it.serialNumber}") }
            parsed
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to renew certificate", e)
            Result.failure(e)
        }
    }
    
    /**
     * Helper extension to convert JSONObject to Map for Firebase Functions
     */
    private fun JSONObject.toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        keys().forEach { key ->
            map[key] = when (val value = get(key)) {
                is JSONObject -> value.toMap()
                is JSONArray -> {
                    val list = mutableListOf<Any?>()
                    for (i in 0 until value.length()) {
                        list.add(value.get(i))
                    }
                    list
                }
                else -> value
            }
        }
        return map
    }
}
