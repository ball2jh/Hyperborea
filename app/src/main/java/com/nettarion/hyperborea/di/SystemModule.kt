package com.nettarion.hyperborea.di

import android.content.Context
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.system.SystemController
import com.nettarion.hyperborea.core.system.SystemLogCapture
import com.nettarion.hyperborea.core.system.SystemLogStore
import com.nettarion.hyperborea.core.system.SystemMonitor
import com.nettarion.hyperborea.platform.AndroidSystemController
import com.nettarion.hyperborea.platform.AndroidSystemMonitor
import com.nettarion.hyperborea.platform.systemlog.RingBufferSystemLogStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SystemModule {

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
}
