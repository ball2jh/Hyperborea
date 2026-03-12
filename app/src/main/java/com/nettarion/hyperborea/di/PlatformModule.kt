package com.nettarion.hyperborea.di

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.adapter.BroadcastAdapter
import com.nettarion.hyperborea.core.adapter.HardwareAdapter
import com.nettarion.hyperborea.core.adapter.SensorAdapter
import com.nettarion.hyperborea.core.orchestration.BroadcastManager
import com.nettarion.hyperborea.core.orchestration.EcosystemManager
import com.nettarion.hyperborea.core.orchestration.Orchestrator
import com.nettarion.hyperborea.core.profile.ProfileRepository
import com.nettarion.hyperborea.core.profile.RideRecorder
import com.nettarion.hyperborea.core.profile.UserPreferences
import com.nettarion.hyperborea.core.system.SystemController
import com.nettarion.hyperborea.core.system.SystemMonitor
import com.nettarion.hyperborea.data.ProfileUserPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
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
        profileRepository: ProfileRepository,
        logger: AppLogger,
        scope: CoroutineScope,
    ): UserPreferences = ProfileUserPreferences(profileRepository, logger, scope)

    @Provides
    @Singleton
    fun provideRideRecorder(
        profileRepository: ProfileRepository,
        logger: AppLogger,
        scope: CoroutineScope,
    ): RideRecorder = RideRecorder(profileRepository, logger, scope)

    @Provides
    @Singleton
    fun provideBroadcastManager(
        broadcastAdapters: Set<@JvmSuppressWildcards BroadcastAdapter>,
        systemMonitor: SystemMonitor,
        userPreferences: UserPreferences,
        logger: AppLogger,
        scope: CoroutineScope,
    ): BroadcastManager = BroadcastManager(
        broadcastAdapters, systemMonitor, userPreferences, logger, scope,
    )

    @Provides
    @Singleton
    fun provideOrchestrator(
        systemMonitor: SystemMonitor,
        systemController: SystemController,
        ecosystemManager: EcosystemManager,
        hardwareAdapter: HardwareAdapter,
        broadcastManager: BroadcastManager,
        rideRecorder: RideRecorder,
        logger: AppLogger,
        scope: CoroutineScope,
        sensorAdapter: SensorAdapter,
    ): Orchestrator = Orchestrator(
        systemMonitor, systemController, ecosystemManager,
        hardwareAdapter, broadcastManager, rideRecorder, logger, scope,
        sensorAdapter = sensorAdapter,
    )
}
