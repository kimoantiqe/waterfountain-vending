package com.waterfountainmachine.app.utils

import android.content.Context
import com.waterfountainmachine.app.security.SecurityModule

/**
 * Centralized Machine ID Provider
 * 
 * Single source of truth for retrieving the machine ID.
 * Uses SecurityModule which gets ID from the certificate.
 * 
 * Usage:
 * ```kotlin
 * val machineId = MachineIdProvider.getMachineId(context)
 * if (machineId != null) {
 *     // Use machine ID
 * } else {
 *     // Handle not enrolled case
 * }
 * ```
 */
object MachineIdProvider {
    
    private const val TAG = "MachineIdProvider"
    
    /**
     * Get machine ID from certificate.
     * 
     * This is the ONLY method to get machine ID in the app.
     * Do NOT use SharedPreferences("machine_config") directly.
     * 
     * @param context Application context
     * @return Machine ID or null if not enrolled
     */
    fun getMachineId(context: Context): String? {
        SecurityModule.initialize(context)
        val machineId = SecurityModule.getMachineId()
        
        if (machineId == null) {
            AppLog.w(TAG, "Machine ID not found - device may not be enrolled")
        }
        
        return machineId
    }
    
    /**
     * Check if machine is enrolled with a valid certificate.
     * 
     * @param context Application context
     * @return true if machine has valid certificate
     */
    fun isEnrolled(context: Context): Boolean {
        SecurityModule.initialize(context)
        return SecurityModule.isEnrolled()
    }
    
    /**
     * Get machine ID or throw exception if not enrolled.
     * Use this when machine ID is required and should not be null.
     * 
     * @param context Application context
     * @return Machine ID (never null)
     * @throws IllegalStateException if not enrolled
     */
    fun requireMachineId(context: Context): String {
        return getMachineId(context) 
            ?: throw IllegalStateException("Machine not enrolled - certificate required")
    }
}
