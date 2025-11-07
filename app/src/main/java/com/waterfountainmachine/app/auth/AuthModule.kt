package com.waterfountainmachine.app.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.waterfountainmachine.app.config.ApiEnvironment
import com.waterfountainmachine.app.security.CertificateManager
import com.waterfountainmachine.app.security.NonceGenerator
import com.waterfountainmachine.app.security.RequestSigner
import com.waterfountainmachine.app.utils.AppLog

/**
 * Dependency injection module for authentication.
 * 
 * This module manages the lifecycle of the authentication repository
 * and provides a global access point following the Singleton pattern.
 * 
 * Usage:
 * 1. Initialize in Application.onCreate():
 *    AuthModule.initialize(context, useMockMode = BuildConfig.DEBUG)
 * 
 * 2. Get repository anywhere:
 *    val authRepo = AuthModule.getRepository()
 * 
 * 3. Access in activities/fragments:
 *    authRepo.requestOtp(phone)
 */
object AuthModule {
    
    private const val TAG = "AuthModule"
    private const val PREFS_NAME = "auth_config"
    private const val KEY_USE_MOCK_MODE = "use_mock_mode"
    private const val KEY_API_ENVIRONMENT = "api_environment"
    
    @Volatile
    private var authRepository: IAuthenticationRepository? = null
    
    @Volatile
    private var isInitialized = false
    
    /**
     * Initialize the authentication module.
     * Must be called before using getRepository().
     * 
     * @param context Application context
     * @param useMockMode If true, uses MockAuthenticationRepository. If false, uses RealAuthenticationRepository.
     */
    @Synchronized
    fun initialize(context: Context, useMockMode: Boolean) {
        if (isInitialized && authRepository != null) {
            AppLog.w(TAG, "AuthModule already initialized. Re-initializing...")
        }
        
        // Save preference
        saveApiModePreference(context, useMockMode)
        
        // Create appropriate repository
        authRepository = if (useMockMode) {
            AppLog.i(TAG, "Initializing with MockAuthenticationRepository")
            MockAuthenticationRepository(
                simulateNetworkDelay = true,
                successRate = 1.0f // 100% success rate by default
            )
        } else {
            AppLog.i(TAG, "Initializing with RealAuthenticationRepository (Firebase Functions SDK)")
            
            // Create security components
            val certificateManager = CertificateManager.getInstance(context)
            val requestSigner = RequestSigner()
            val nonceGenerator = NonceGenerator()
            
            RealAuthenticationRepository(
                certificateManager = certificateManager,
                requestSigner = requestSigner,
                nonceGenerator = nonceGenerator
            )
        }
        
        isInitialized = true
        AppLog.i(TAG, "AuthModule initialized successfully (mockMode=$useMockMode)")
    }
    
    /**
     * Get the authentication repository instance.
     * 
     * @return IAuthenticationRepository instance
     * @throws IllegalStateException if module is not initialized
     */
    fun getRepository(): IAuthenticationRepository {
        val repo = authRepository
        if (repo == null || !isInitialized) {
            throw IllegalStateException(
                "AuthModule not initialized. Call AuthModule.initialize() in Application.onCreate()"
            )
        }
        return repo
    }
    
    /**
     * Check if module is initialized
     */
    fun isInitialized(): Boolean = isInitialized
    
    /**
     * Check if currently using mock mode
     */
    fun isMockMode(): Boolean {
        return authRepository is MockAuthenticationRepository
    }
    
    /**
     * Load API mode preference from secure storage
     */
    fun loadApiModePreference(context: Context): Boolean {
        return try {
            val prefs = getEncryptedPreferences(context)
            prefs.getBoolean(KEY_USE_MOCK_MODE, true) // Default to mock mode
        } catch (e: Exception) {
            AppLog.e(TAG, "Error loading API mode preference: ${e.message}")
            true // Default to mock mode on error
        }
    }
    
    /**
     * Save API mode preference to secure storage
     */
    private fun saveApiModePreference(context: Context, useMockMode: Boolean) {
        try {
            val prefs = getEncryptedPreferences(context)
            prefs.edit().putBoolean(KEY_USE_MOCK_MODE, useMockMode).apply()
            AppLog.d(TAG, "API mode preference saved: mockMode=$useMockMode")
        } catch (e: Exception) {
            AppLog.e(TAG, "Error saving API mode preference: ${e.message}")
        }
    }
    
    /**
     * Get encrypted shared preferences for secure storage
     */
    private fun getEncryptedPreferences(context: Context) = 
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    
    /**
     * Load API environment preference
     */
    fun loadEnvironmentPreference(context: Context): ApiEnvironment {
        return try {
            val prefs = getEncryptedPreferences(context)
            val envName = prefs.getString(KEY_API_ENVIRONMENT, ApiEnvironment.DEV.name)
            ApiEnvironment.valueOf(envName ?: ApiEnvironment.DEV.name)
        } catch (e: Exception) {
            AppLog.e(TAG, "Error loading environment preference: ${e.message}")
            ApiEnvironment.DEV // Default to DEV
        }
    }
    
    /**
     * Save API environment preference
     */
    fun saveEnvironmentPreference(context: Context, environment: ApiEnvironment) {
        try {
            val prefs = getEncryptedPreferences(context)
            prefs.edit().putString(KEY_API_ENVIRONMENT, environment.name).apply()
            AppLog.d(TAG, "Environment preference saved: ${environment.name}")
        } catch (e: Exception) {
            AppLog.e(TAG, "Error saving environment preference: ${e.message}")
        }
    }
    
    /**
     * For testing: Reset the module
     */
    @Synchronized
    internal fun reset() {
        authRepository = null
        isInitialized = false
        AppLog.d(TAG, "AuthModule reset")
    }
}
