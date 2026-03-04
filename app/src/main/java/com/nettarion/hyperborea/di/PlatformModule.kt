package com.nettarion.hyperborea.di

import android.content.Context
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.LogStore
import com.nettarion.hyperborea.core.SystemController
import com.nettarion.hyperborea.core.SystemLogCapture
import com.nettarion.hyperborea.core.SystemLogStore
import com.nettarion.hyperborea.core.SystemMonitor
import com.nettarion.hyperborea.platform.AndroidSystemController
import com.nettarion.hyperborea.platform.AndroidSystemMonitor
import com.nettarion.hyperborea.platform.RingBufferLogStore
import com.nettarion.hyperborea.platform.systemlog.RingBufferSystemLogStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PlatformModule {

    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
        scope: CoroutineScope,
        logger: AppLogger,
    ): SystemMonitor = AndroidSystemMonitor(context, scope, logger)

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
}
