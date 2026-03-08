package com.nettarion.hyperborea.di

import android.content.Context
import com.nettarion.hyperborea.platform.update.AppInstaller
import com.nettarion.hyperborea.platform.update.HttpUrlConnectionClient
import com.nettarion.hyperborea.platform.update.UpdateHttpClient
import com.nettarion.hyperborea.platform.update.UpdateInstaller
import com.nettarion.hyperborea.platform.update.VersionProvider
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
abstract class UpdateModule {

    @Binds
    @Singleton
    abstract fun bindHttpClient(impl: HttpUrlConnectionClient): UpdateHttpClient

    @Binds
    @Singleton
    abstract fun bindInstaller(impl: AppInstaller): UpdateInstaller

    companion object {
        @Provides
        @Named("updateDir")
        fun provideUpdateDir(@ApplicationContext context: Context): String =
            context.filesDir.resolve("update").absolutePath

        @Provides
        fun provideVersionProvider(@ApplicationContext context: Context): VersionProvider =
            VersionProvider {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode
            }
    }
}
