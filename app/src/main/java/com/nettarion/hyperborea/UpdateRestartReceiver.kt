package com.nettarion.hyperborea

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives ACTION_MY_PACKAGE_REPLACED after a self-update via PackageInstaller.
 * Launches MainActivity so the updated app is visible to the user.
 */
class UpdateRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i(TAG, "Package replaced, launching activity")
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
        )
    }

    companion object {
        private const val TAG = "Hyperborea.UpdateR"
    }
}
