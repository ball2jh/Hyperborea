package com.nettarion.hyperborea.di

import com.nettarion.hyperborea.core.orchestration.EcosystemManager
import com.nettarion.hyperborea.ecosystem.ifit.IfitEcosystemManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class EcosystemModule {

    @Binds
    @Singleton
    abstract fun bindEcosystemManager(impl: IfitEcosystemManager): EcosystemManager
}
