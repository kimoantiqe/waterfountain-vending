package com.waterfountainmachine.app.config

import com.waterfountainmachine.app.BuildConfig

/**
 * API Configuration for different environments
 * 
 * Automatically configured via product flavors (dev/prod)
 * No manual switching required - environment is determined at build time.
 * 
 * Environments:
 * - DEV: Development Firebase project (waterfountain-dev)
 * - PROD: Production Firebase project (waterfountain-25886)
 * 
 * Build commands:
 * - Development: ./gradlew assembleDevDebug or assembleDevRelease
 * - Production: ./gradlew assembleProdDebug or assembleProdRelease
 */
object ApiEnvironment {
    
    /**
     * Base URL for Firebase Functions
     * Set via BuildConfig from product flavors
     */
    val baseUrl: String = BuildConfig.API_BASE_URL
    
    /**
     * Firebase Project ID
     * Set via BuildConfig from product flavors
     */
    val projectId: String = BuildConfig.FIREBASE_PROJECT_ID
    
    /**
     * Check if running in production environment
     */
    val isProduction: Boolean = BuildConfig.IS_PRODUCTION
    
    /**
     * Get environment name for logging
     */
    val environmentName: String = BuildConfig.ENVIRONMENT
    
    /**
     * Firebase Functions region
     */
    const val region: String = "us-central1"
    
    /**
     * Get full endpoint URL for a Firebase Function
     * 
     * @param functionName Name of the Firebase Function (e.g., "requestOtpFn")
     * @return Full HTTPS URL to the function
     */
    fun getEndpointUrl(functionName: String): String {
        return "$baseUrl/$functionName"
    }
}
