package com.nettarion.hyperborea.di

import android.content.Context
import com.nettarion.hyperborea.broadcast.ftms.FtmsAdapter
import com.nettarion.hyperborea.broadcast.wifi.NsdRegistrar
import com.nettarion.hyperborea.broadcast.wifi.WifiAdapter
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.adapter.BroadcastAdapter
import com.nettarion.hyperborea.core.adapter.HardwareAdapter
import com.nettarion.hyperborea.core.adapter.SensorAdapter
import com.nettarion.hyperborea.hardware.fitpro.FitProAdapter
import com.nettarion.hyperborea.hardware.fitpro.transport.HidTransportFactory
import com.nettarion.hyperborea.hardware.fitpro.transport.UsbHidTransportFactory
import com.nettarion.hyperborea.sensor.hrm.HrmAdapter
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
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
    abstract fun bindWifiAdapter(impl: WifiAdapter): BroadcastAdapter

    companion object {
        @Provides
        @Singleton
        @IntoSet
        fun provideFtmsBroadcastAdapter(
            @ApplicationContext context: Context,
            logger: AppLogger,
        ): BroadcastAdapter = FtmsAdapter(context, logger)

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
        @Singleton
        fun provideHrmAdapter(
            @ApplicationContext context: Context,
            logger: AppLogger,
            scope: CoroutineScope,
        ): SensorAdapter = HrmAdapter(context, logger, scope)
    }
}
