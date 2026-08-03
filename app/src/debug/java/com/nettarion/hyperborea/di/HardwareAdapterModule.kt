package com.nettarion.hyperborea.di

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.adapter.HardwareAdapter
import com.nettarion.hyperborea.demo.DemoHardwareAdapter
import com.nettarion.hyperborea.demo.DemoMode
import com.nettarion.hyperborea.hardware.fitpro.FitProAdapter
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/**
 * Debug builds substitute a simulated treadmill on emulators (no FitPro USB hardware there) so
 * the full UI/overlay/broadcast pipeline can be previewed locally. On real consoles the debug
 * APK still uses the real [FitProAdapter] — `Lazy` keeps the USB stack from ever spinning up in
 * the demo case.
 */
@Module
@InstallIn(SingletonComponent::class)
object HardwareAdapterModule {

    @Provides
    @Singleton
    fun provideHardwareAdapter(
        fitPro: Lazy<FitProAdapter>,
        logger: AppLogger,
        scope: CoroutineScope,
    ): HardwareAdapter =
        if (DemoMode.isEmulator) DemoHardwareAdapter(logger, scope) else fitPro.get()
}
