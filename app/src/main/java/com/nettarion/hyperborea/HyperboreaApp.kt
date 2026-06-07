package com.nettarion.hyperborea

import android.app.Application
import com.nettarion.hyperborea.platform.DiagnosticBootstrap
import com.nettarion.hyperborea.platform.ScreenSleepController
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class HyperboreaApp : Application() {

    @Inject lateinit var diagnosticBootstrap: DiagnosticBootstrap
    @Inject lateinit var screenSleepController: ScreenSleepController

    override fun onCreate() {
        super.onCreate()
        diagnosticBootstrap.start()
        screenSleepController.start()
        startHyperboreaService()
    }
}
