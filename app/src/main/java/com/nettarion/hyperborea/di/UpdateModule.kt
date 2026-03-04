package com.nettarion.hyperborea.di

import com.nettarion.hyperborea.platform.update.HttpUrlConnectionClient
import com.nettarion.hyperborea.platform.update.UpdateHttpClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {

    @Binds
    @Singleton
    abstract fun bindHttpClient(impl: HttpUrlConnectionClient): UpdateHttpClient
}
