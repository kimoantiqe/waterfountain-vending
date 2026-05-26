package com.waterfountainmachine.app.core.di

import com.waterfountainmachine.app.workers.CertificateBackend
import com.waterfountainmachine.app.workers.CertificateSecurity
import com.waterfountainmachine.app.workers.RealCertificateBackend
import com.waterfountainmachine.app.workers.RealCertificateSecurity
import com.waterfountainmachine.app.workers.RealRenewalAnalytics
import com.waterfountainmachine.app.workers.RenewalAnalytics
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the [CertificateRenewalWorker] facades to their production
 * implementations. Tests provide fakes via Hilt's TestInstallIn (or by
 * driving the worker directly with a custom `WorkerFactory`).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CertificateRenewalModule {

    @Binds
    @Singleton
    abstract fun bindCertificateSecurity(impl: RealCertificateSecurity): CertificateSecurity

    @Binds
    @Singleton
    abstract fun bindCertificateBackend(impl: RealCertificateBackend): CertificateBackend

    @Binds
    @Singleton
    abstract fun bindRenewalAnalytics(impl: RealRenewalAnalytics): RenewalAnalytics
}
