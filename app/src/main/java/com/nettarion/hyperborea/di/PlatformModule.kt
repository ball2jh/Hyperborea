package com.nettarion.hyperborea.di

import android.content.Context
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.profile.ProfileRepository
import com.nettarion.hyperborea.core.profile.RideRecorder
import com.nettarion.hyperborea.core.profile.UserPreferences
import com.nettarion.hyperborea.data.ProfileUserPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PlatformModule {

    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope {
        val handler = CoroutineExceptionHandler { _, throwable ->
            android.util.Log.e("Hyperborea.AppScope", "Uncaught coroutine exception", throwable)
        }
        return CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
    }

    @Provides
    @Singleton
    fun provideUserPreferences(
        @ApplicationContext context: Context,
        logger: AppLogger,
    ): UserPreferences = ProfileUserPreferences(context, logger)

    @Provides
    @Singleton
    fun provideRideRecorder(
        profileRepository: ProfileRepository,
        logger: AppLogger,
        scope: CoroutineScope,
    ): RideRecorder = RideRecorder(profileRepository, logger, scope)
}
