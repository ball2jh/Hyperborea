package com.nettarion.hyperborea.di

import android.content.Context
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.BroadcastAdapter
import com.nettarion.hyperborea.core.EcosystemManager
import com.nettarion.hyperborea.core.HardwareAdapter
import com.nettarion.hyperborea.core.LogStore
import com.nettarion.hyperborea.core.Orchestrator
import com.nettarion.hyperborea.core.ProfileRepository
import com.nettarion.hyperborea.core.RideRecorder
import com.nettarion.hyperborea.core.SystemController
import com.nettarion.hyperborea.core.SystemLogCapture
import com.nettarion.hyperborea.core.SystemLogStore
import com.nettarion.hyperborea.core.SystemMonitor
import com.nettarion.hyperborea.core.UserPreferences
import com.nettarion.hyperborea.platform.AndroidSystemController
import com.nettarion.hyperborea.platform.AndroidSystemMonitor
import com.nettarion.hyperborea.platform.RingBufferLogStore
import com.nettarion.hyperborea.data.HyperboreaDatabase
import com.nettarion.hyperborea.data.ProfileDao
import com.nettarion.hyperborea.data.RoomProfileRepository
import com.nettarion.hyperborea.platform.ProfileUserPreferences
import com.nettarion.hyperborea.platform.systemlog.RingBufferSystemLogStore
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
    fun provideRingBufferLogStore(): RingBufferLogStore = RingBufferLogStore()

    @Provides
    @Singleton
    fun provideAppLogger(store: RingBufferLogStore): AppLogger = store

    @Provides
    @Singleton
    fun provideLogStore(store: RingBufferLogStore): LogStore = store

    @Provides
    @Singleton
    fun provideSystemMonitor(
        @ApplicationContext context: Context,
        logger: AppLogger,
    ): SystemMonitor = AndroidSystemMonitor(context, logger)

    @Provides
    @Singleton
    fun provideSystemController(
        @ApplicationContext context: Context,
        logger: AppLogger,
    ): SystemController = AndroidSystemController(context, logger)

    @Provides
    @Singleton
    fun provideRingBufferSystemLogStore(
        logger: AppLogger,
        scope: CoroutineScope,
    ): RingBufferSystemLogStore = RingBufferSystemLogStore(logger, scope)

    @Provides
    @Singleton
    fun provideSystemLogCapture(store: RingBufferSystemLogStore): SystemLogCapture = store

    @Provides
    @Singleton
    fun provideSystemLogStore(store: RingBufferSystemLogStore): SystemLogStore = store

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): HyperboreaDatabase = androidx.room.Room.databaseBuilder(
        context,
        HyperboreaDatabase::class.java,
        "hyperborea.db",
    ).addMigrations(HyperboreaDatabase.MIGRATION_1_2).build()

    @Provides
    @Singleton
    fun provideProfileDao(database: HyperboreaDatabase): ProfileDao = database.profileDao()

    @Provides
    @Singleton
    fun provideProfileRepository(
        database: HyperboreaDatabase,
        dao: ProfileDao,
        logger: AppLogger,
        scope: CoroutineScope,
    ): ProfileRepository = RoomProfileRepository(database, dao, logger, scope)

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
    fun provideOrchestrator(
        systemMonitor: SystemMonitor,
        systemController: SystemController,
        ecosystemManager: EcosystemManager,
        hardwareAdapter: HardwareAdapter,
        broadcastAdapters: Set<@JvmSuppressWildcards BroadcastAdapter>,
        userPreferences: UserPreferences,
        rideRecorder: RideRecorder,
        logger: AppLogger,
        scope: CoroutineScope,
    ): Orchestrator = Orchestrator(
        systemMonitor, systemController, ecosystemManager,
        hardwareAdapter, broadcastAdapters, userPreferences, rideRecorder, logger, scope,
    )
}
