package com.nettarion.hyperborea.di

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.LogStore
import com.nettarion.hyperborea.platform.RingBufferLogStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LoggingModule {

    @Provides
    @Singleton
    fun provideRingBufferLogStore(): RingBufferLogStore = RingBufferLogStore()

    @Provides
    @Singleton
    fun provideAppLogger(store: RingBufferLogStore): AppLogger = store

    @Provides
    @Singleton
    fun provideLogStore(store: RingBufferLogStore): LogStore = store
}
