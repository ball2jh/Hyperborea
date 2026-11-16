package com.nettarion.hyperborea

import android.app.Application
import android.os.Handler
import android.os.Looper
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
        // Double-post so both MainActivity.onCreate() and the initial Compose frame (whose class
        // verification can take >10s on debug builds on older MediaTek consoles) finish before the
        // system's 10s startForegroundService() ANR timer begins ticking.
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            mainHandler.post {
                startHyperboreaService()
            }
        }
    }
}
