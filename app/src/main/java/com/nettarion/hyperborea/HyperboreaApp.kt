package com.nettarion.hyperborea

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class HyperboreaApp : Application() {

    @Inject lateinit var diagnosticBootstrap: DiagnosticBootstrap

    override fun onCreate() {
        super.onCreate()
        diagnosticBootstrap.start()
    }
}
