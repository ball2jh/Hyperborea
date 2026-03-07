package com.nettarion.hyperborea.di

import android.content.Context
import com.nettarion.hyperborea.broadcast.ftms.FtmsAdapter
import com.nettarion.hyperborea.broadcast.wftnp.NsdRegistrar
import com.nettarion.hyperborea.broadcast.wftnp.WftnpAdapter
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.BroadcastAdapter
import com.nettarion.hyperborea.core.HardwareAdapter
import com.nettarion.hyperborea.hardware.fitpro.FitProAdapter
import com.nettarion.hyperborea.hardware.fitpro.transport.HidTransportFactory
import com.nettarion.hyperborea.hardware.fitpro.transport.UsbHidTransportFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Named
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
    abstract fun bindWftnpAdapter(impl: WftnpAdapter): BroadcastAdapter

    companion object {
        @Provides
        @Singleton
        @IntoSet
        fun provideFtmsBroadcastAdapter(
            @ApplicationContext context: Context,
            logger: AppLogger,
            @Named("deviceName") deviceName: () -> String?,
        ): BroadcastAdapter = FtmsAdapter(context, logger, deviceName)

        @Provides
        @Singleton
        fun provideHidTransportFactory(
            @ApplicationContext context: Context,
            logger: AppLogger,
        ): HidTransportFactory = UsbHidTransportFactory(context, logger)

        @Provides
        @Singleton
        fun provideNsdRegistrar(
            @ApplicationContext context: Context,
            logger: AppLogger,
        ): NsdRegistrar = NsdRegistrar(context, logger)

        @Provides
        @Named("deviceName")
        fun provideDeviceName(
            hardwareAdapter: HardwareAdapter,
        ): () -> String? = { hardwareAdapter.deviceInfo.value?.name }
    }
}
