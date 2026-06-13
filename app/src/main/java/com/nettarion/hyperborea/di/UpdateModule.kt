package com.nettarion.hyperborea.di

import android.content.Context
import com.nettarion.hyperborea.core.orchestration.Orchestrator
import com.nettarion.hyperborea.core.orchestration.OrchestratorState
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
import kotlinx.coroutines.flow.StateFlow
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
            object : VersionProvider {
                private fun info() = context.packageManager.getPackageInfo(context.packageName, 0)

                @Suppress("DEPRECATION")
                override fun getVersionCode(): Int = info().versionCode

                override fun getVersionName(): String = info().versionName ?: "unknown"
            }

        @Provides
        @Named("orchestratorState")
        fun provideOrchestratorState(orchestrator: Orchestrator): StateFlow<OrchestratorState> =
            orchestrator.state
    }
}
