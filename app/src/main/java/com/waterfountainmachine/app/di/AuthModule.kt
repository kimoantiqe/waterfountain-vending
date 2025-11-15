package com.waterfountainmachine.app.di

import com.google.firebase.Firebase
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.functions
import com.waterfountainmachine.app.auth.IAuthenticationRepository
import com.waterfountainmachine.app.auth.MockAuthenticationRepository
import com.waterfountainmachine.app.auth.RealAuthenticationRepository
import com.waterfountainmachine.app.security.CertificateManager
import com.waterfountainmachine.app.security.NonceGenerator
import com.waterfountainmachine.app.security.RequestSigner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for authentication dependencies
 */
@Module
@InstallIn(SingletonComponent::class)
object AuthModule {
    
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
     * Can be switched between Real and Mock implementations
     * For testing: Replace with MockAuthenticationRepository
     */
    @Provides
    @Singleton
    fun provideAuthenticationRepository(
        certificateManager: CertificateManager,
        requestSigner: RequestSigner,
        nonceGenerator: NonceGenerator
    ): IAuthenticationRepository {
        // Production: Use real implementation
        return RealAuthenticationRepository(
            certificateManager = certificateManager,
            requestSigner = requestSigner,
            nonceGenerator = nonceGenerator
        )
        
        // For testing: Uncomment below and comment above
        // return MockAuthenticationRepository()
    }
}
