package com.waterfountainmachine.app.config

/**
 * API Configuration for different environments
 * 
 * Environments:
 * - LOCAL: Firebase Emulator on localhost
 * - DEV: Development Firebase project
 * - PROD: Production Firebase project
 */
enum class ApiEnvironment(
    val baseUrl: String,
    val projectId: String,
    val region: String = "us-central1"
) {
    /**
     * Local Firebase Emulator
     * Run: firebase emulators:start
     */
    LOCAL(
        baseUrl = "http://10.0.2.2:5001/waterfountain-dev/us-central1", // Android emulator
        projectId = "waterfountain-dev"
    ),
    
    /**
     * Development Firebase Project
     */
    DEV(
        baseUrl = "https://us-central1-waterfountain-dev.cloudfunctions.net",
        projectId = "waterfountain-dev"
    ),
    
    /**
     * Production Firebase Project
     */
    PROD(
        baseUrl = "https://us-central1-waterfountain-25886.cloudfunctions.net",
        projectId = "waterfountain-25886"
    );
    
    /**
     * Get full endpoint URL
     */
    fun getEndpointUrl(functionName: String): String {
        return "$baseUrl/$functionName"
    }
    
    companion object {
        /**
         * Get current environment (can be configured)
         */
        fun getCurrent(): ApiEnvironment {
            // TODO: Read from BuildConfig or SharedPreferences
            return DEV // Default to DEV
        }
    }
}
