package com.nettarion.hyperborea.di

import com.nettarion.hyperborea.broadcast.dircon.DirconAdapter
import com.nettarion.hyperborea.broadcast.ftms.FtmsAdapter
import com.nettarion.hyperborea.core.BroadcastAdapter
import com.nettarion.hyperborea.core.HardwareAdapter
import com.nettarion.hyperborea.hardware.fitpro.FitProAdapter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AdapterModule {

    @Binds
    @Singleton
    abstract fun bindHardwareAdapter(impl: FitProAdapter): HardwareAdapter

    @Binds
    @Singleton
    @IntoSet
    abstract fun bindFtmsAdapter(impl: FtmsAdapter): BroadcastAdapter

    @Binds
    @Singleton
    @IntoSet
    abstract fun bindDirconAdapter(impl: DirconAdapter): BroadcastAdapter
}
