package com.nettarion.hyperborea

import android.app.Application
import android.content.Intent
import com.nettarion.hyperborea.platform.DiagnosticBootstrap
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class HyperboreaApp : Application() {

    @Inject lateinit var diagnosticBootstrap: DiagnosticBootstrap

    override fun onCreate() {
        super.onCreate()
        diagnosticBootstrap.start()
        startService(Intent(this, HyperboreaService::class.java))
    }
}
