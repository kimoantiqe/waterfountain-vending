package com.waterfountainmachine.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier annotations for different dispatcher types
 * Used to distinguish between dispatcher injection points
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IODispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

/**
 * Hilt module providing CoroutineDispatcher instances
 * 
 * Provides injectable dispatchers for:
 * - IO operations (network, database, file operations)
 * - Default operations (CPU-intensive work)
 * - Main thread operations (UI updates)
 * 
 * Benefits:
 * - Testability: Can inject test dispatchers in tests
 * - Flexibility: Can switch dispatcher implementations
 * - Best practices: Avoid hardcoded Dispatchers.IO/Main
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {
    
    /**
     * Provide IO dispatcher for I/O operations
     * Used for: Network calls, database operations, file I/O
     */
    @Provides
    @Singleton
    @IODispatcher
    fun provideIODispatcher(): CoroutineDispatcher {
        return Dispatchers.IO
    }
    
    /**
     * Provide Default dispatcher for CPU-intensive work
     * Used for: Heavy computations, data processing
     */
    @Provides
    @Singleton
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher {
        return Dispatchers.Default
    }
    
    /**
     * Provide Main dispatcher for UI operations
     * Used for: UI updates, user interactions
     */
    @Provides
    @Singleton
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher {
        return Dispatchers.Main
    }
}
