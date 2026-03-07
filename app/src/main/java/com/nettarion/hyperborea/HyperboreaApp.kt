package com.nettarion.hyperborea

import android.app.Application
import android.content.Intent
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class HyperboreaApp : Application() {

    @Inject lateinit var diagnosticBootstrap: DiagnosticBootstrap

    override fun onCreate() {
        super.onCreate()
        if (!SignatureVerifier.verify(this)) {
            android.util.Log.e("Hyperborea.App", "Signature verification failed — aborting")
            android.os.Process.killProcess(android.os.Process.myPid())
            return
        }
        diagnosticBootstrap.start()
        val intent = Intent(this, HyperboreaService::class.java).apply {
            action = HyperboreaService.ACTION_ACTIVATE
        }
        startService(intent)
    }
}
