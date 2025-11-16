package com.waterfountainmachine.app.di

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.functions
import com.waterfountainmachine.app.auth.IAuthenticationRepository
import com.waterfountainmachine.app.auth.MockAuthenticationRepository
import com.waterfountainmachine.app.auth.RealAuthenticationRepository
import com.waterfountainmachine.app.security.CertificateManager
import com.waterfountainmachine.app.security.NonceGenerator
import com.waterfountainmachine.app.security.RequestSigner
import com.waterfountainmachine.app.utils.AppLog
import com.waterfountainmachine.app.utils.SecurePreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for authentication dependencies
 */
@Module
@InstallIn(SingletonComponent::class)
object AuthModule {
    
    private const val TAG = "AuthModule"
    private const val KEY_USE_MOCK_MODE = "use_mock_mode"
    
    /**
     * Initialize auth mode (called from Admin panel)
     * @param context Application context
     * @param useMockMode true for mock, false for real API
     */
    fun initialize(context: Context, useMockMode: Boolean) {
        AppLog.i(TAG, "initialize() called with useMockMode=$useMockMode")
        
        // Use EncryptedSharedPreferences for consistency
        val prefs = SecurePreferences.getSystemSettings(context)
        prefs.edit()
            .putBoolean(KEY_USE_MOCK_MODE, useMockMode)
            .apply()
        
        // Verify it was written
        val savedValue = prefs.getBoolean(KEY_USE_MOCK_MODE, true)
        AppLog.i(TAG, "EncryptedSharedPreferences written: useMockMode=$savedValue")
        
        val mode = if (useMockMode) "Mock" else "Real API"
        AppLog.i(TAG, "Authentication mode set to: $mode")
    }
    
    /**
     * Load current API mode preference
     * @param context Application context
     * @return true if using mock mode, false if using real API
     */
    fun loadApiModePreference(context: Context): Boolean {
        // Use EncryptedSharedPreferences for consistency
        val prefs = SecurePreferences.getSystemSettings(context)
        val useMockMode = prefs.getBoolean(KEY_USE_MOCK_MODE, true) // Default to mock for safety
        AppLog.i(TAG, "loadApiModePreference() reading from EncryptedSharedPreferences: useMockMode=$useMockMode")
        return useMockMode
    }
    
    /**
     * Provide Firebase Functions instance
     */
    @Provides
    @Singleton
    fun provideFirebaseFunctions(): FirebaseFunctions {
        return Firebase.functions
    }
    
    /**
     * Provide Authentication Repository
     * 
     * Injects the correct repository (Mock or Real) based on SharedPreferences
     * at app startup. Changes require app restart to take effect.
     */
    @Provides
    @Singleton
    fun provideAuthenticationRepository(
        @ApplicationContext context: Context,
        certificateManager: CertificateManager,
        requestSigner: RequestSigner,
        nonceGenerator: NonceGenerator
    ): IAuthenticationRepository {
        // Read preference once at app startup
        val useMockMode = loadApiModePreference(context)
        val mode = if (useMockMode) "Mock" else "Real API"
        
        AppLog.i(TAG, "Providing AuthenticationRepository: $mode")
        
        return if (useMockMode) {
            AppLog.i(TAG, "✅ Injecting MockAuthenticationRepository")
            MockAuthenticationRepository()
        } else {
            AppLog.i(TAG, "✅ Injecting RealAuthenticationRepository")
            RealAuthenticationRepository(
                certificateManager = certificateManager,
                requestSigner = requestSigner,
                nonceGenerator = nonceGenerator
            )
        }
    }
    
    /**
     * Legacy method for non-Hilt activities (SMSVerifyActivity, etc.)
     * TODO: Remove this once all activities are migrated to Hilt
     * 
     * @param context Application context
     * @return IAuthenticationRepository instance
     */
    @Deprecated("Use Hilt dependency injection instead", ReplaceWith("Inject via constructor or viewModels()"))
    fun getRepository(context: Context): IAuthenticationRepository {
        val useMockMode = loadApiModePreference(context)
        
        return if (useMockMode) {
            AppLog.i(TAG, "getRepository() returning MockAuthenticationRepository")
            MockAuthenticationRepository()
        } else {
            AppLog.i(TAG, "getRepository() returning RealAuthenticationRepository")
            val certificateManager = CertificateManager.getInstance(context)
            val requestSigner = RequestSigner()
            val nonceGenerator = NonceGenerator()
            
            RealAuthenticationRepository(
                certificateManager = certificateManager,
                requestSigner = requestSigner,
                nonceGenerator = nonceGenerator
            )
        }
    }
}
