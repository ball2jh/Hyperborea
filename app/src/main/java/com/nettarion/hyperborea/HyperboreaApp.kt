package com.nettarion.hyperborea

import android.app.Application
import com.nettarion.hyperborea.platform.DiagnosticBootstrap
import com.nettarion.hyperborea.platform.ScreenSleepController
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class HyperboreaApp : Application() {

    @Inject lateinit var logger: com.nettarion.hyperborea.core.AppLogger
    @Inject lateinit var diagnosticBootstrap: DiagnosticBootstrap
    @Inject lateinit var screenSleepController: ScreenSleepController
    @Inject lateinit var appVisibility: com.nettarion.hyperborea.platform.AppVisibility

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(appVisibility)
        diagnosticBootstrap.start()
        screenSleepController.start()
        try {
            startHyperboreaService()
        } catch (e: IllegalStateException) {
            // A package replacement can create Application in the background on API 31+,
            // without permission to start a new FGS. The visible activity retries in onStart.
            if (android.os.Build.VERSION.SDK_INT >= 31 && e is android.app.ForegroundServiceStartNotAllowedException) {
                logger.w(TAG, "Deferring bridge service until the app is visible: ${e.message}")
            } else throw e
        }
    }

    private companion object { const val TAG = "Application" }
}
