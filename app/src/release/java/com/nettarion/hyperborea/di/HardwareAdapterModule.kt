package com.nettarion.hyperborea.di

import com.nettarion.hyperborea.core.adapter.HardwareAdapter
import com.nettarion.hyperborea.hardware.fitpro.FitProAdapter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Release builds always talk to the real FitPro USB hardware. */
@Module
@InstallIn(SingletonComponent::class)
abstract class HardwareAdapterModule {

    @Binds
    @Singleton
    abstract fun bindHardwareAdapter(impl: FitProAdapter): HardwareAdapter
}
