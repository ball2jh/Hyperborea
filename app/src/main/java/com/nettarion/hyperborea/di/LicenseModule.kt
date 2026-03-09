package com.nettarion.hyperborea.di

import android.content.Context
import android.content.SharedPreferences
import com.nettarion.hyperborea.BuildConfig
import com.nettarion.hyperborea.core.LicenseChecker
import com.nettarion.hyperborea.platform.license.HttpUrlConnectionLicenseClient
import com.nettarion.hyperborea.platform.license.LicenseCheckerImpl
import com.nettarion.hyperborea.platform.license.LicenseHttpClient
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LicenseModule {

    @Binds
    @Singleton
    abstract fun bindLicenseChecker(impl: LicenseCheckerImpl): LicenseChecker

    @Binds
    @Singleton
    abstract fun bindLicenseHttpClient(impl: HttpUrlConnectionLicenseClient): LicenseHttpClient

    companion object {
        @Provides
        @Singleton
        fun provideLicensePreferences(
            @ApplicationContext context: Context,
        ): SharedPreferences =
            context.getSharedPreferences("hyperborea_license", Context.MODE_PRIVATE)

        @Provides
        @Named("licensePublicKey")
        fun provideLicensePublicKey(): String = BuildConfig.LICENSE_PUBLIC_KEY
    }
}
