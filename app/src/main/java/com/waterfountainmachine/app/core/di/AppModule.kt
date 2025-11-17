package com.waterfountainmachine.app.di

import android.content.Context
import com.waterfountainmachine.app.config.WaterFountainConfig
import com.waterfountainmachine.app.hardware.WaterFountainManager
import com.waterfountainmachine.app.security.CertificateManager
import com.waterfountainmachine.app.security.NonceGenerator
import com.waterfountainmachine.app.security.RequestSigner
import com.waterfountainmachine.app.utils.SoundManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing app-level dependencies
 * 
 * SingletonComponent = Dependencies live as long as the application
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    /**
     * Provide WaterFountainConfig singleton
     * Thread-safe lazy initialization
     */
    @Provides
    @Singleton
    fun provideWaterFountainConfig(
        @ApplicationContext context: Context
    ): WaterFountainConfig {
        return WaterFountainConfig.getInstance(context)
    }
    
    /**
     * Provide WaterFountainManager singleton
     * Lazy initialization - only created when first requested
     */
    @Provides
    @Singleton
    fun provideWaterFountainManager(
        @ApplicationContext context: Context
    ): WaterFountainManager {
        return WaterFountainManager.getInstance(context)
    }
    
    /**
     * Provide CertificateManager singleton
     */
    @Provides
    @Singleton
    fun provideCertificateManager(
        @ApplicationContext context: Context
    ): CertificateManager {
        return CertificateManager.getInstance(context)
    }
    
    /**
     * Provide RequestSigner
     */
    @Provides
    @Singleton
    fun provideRequestSigner(): RequestSigner {
        return RequestSigner()
    }
    
    /**
     * Provide NonceGenerator
     */
    @Provides
    @Singleton
    fun provideNonceGenerator(): NonceGenerator {
        return NonceGenerator()
    }
}
