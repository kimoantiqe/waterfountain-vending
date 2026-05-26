package com.waterfountainmachine.app.core.di
import android.content.Context
import com.waterfountainmachine.app.core.config.WaterFountainConfig
import com.waterfountainmachine.app.core.hardware.WaterFountainManager
import com.waterfountainmachine.app.core.security.CertificateManager
import com.waterfountainmachine.app.core.security.NonceGenerator
import com.waterfountainmachine.app.core.security.RequestSigner
import com.waterfountainmachine.app.core.utils.SoundManager
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
