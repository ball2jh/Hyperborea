package com.nettarion.hyperborea

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Starts [HyperboreaService], using [Context.startForegroundService] on API 26+ so the call is
 * legal even from a background context (the boot receiver, `Application.onCreate()`). The service
 * calls `startForeground()` synchronously in `onCreate()`, satisfying the 5-second window the
 * platform allows after a `startForegroundService()`.
 */
fun Context.startHyperboreaService(action: String? = null, programJson: String? = null) {
    val intent = Intent(this, HyperboreaService::class.java)
    if (action != null) intent.action = action
    if (programJson != null) intent.putExtra(HyperboreaService.EXTRA_PROGRAM, programJson)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(intent)
    } else {
        startService(intent)
    }
}
