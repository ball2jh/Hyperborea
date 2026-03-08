package com.nettarion.hyperborea.di

import com.nettarion.hyperborea.platform.support.HttpUrlConnectionSupportClient
import com.nettarion.hyperborea.platform.support.SupportHttpClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SupportModule {

    @Binds
    @Singleton
    abstract fun bindSupportHttpClient(impl: HttpUrlConnectionSupportClient): SupportHttpClient
}
