package com.nettarion.hyperborea

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.Orchestrator
import com.nettarion.hyperborea.core.OrchestratorState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class HyperboreaService : Service() {

    @Inject lateinit var orchestrator: Orchestrator
    @Inject lateinit var logger: AppLogger
    @Inject lateinit var scope: CoroutineScope

    private var stateObserverJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        @Suppress("DEPRECATION")
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
        startStateObserver()
        logger.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BOOT -> logger.i(TAG, "Started from boot — idle, waiting for activation")
            ACTION_ACTIVATE -> activate()
            ACTION_DEACTIVATE -> deactivate()
            ACTION_SHUTDOWN -> shutdown()
            else -> logger.i(TAG, "Started with no action — idle")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stateObserverJob?.cancel()
        logger.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    private fun activate() {
        logger.i(TAG, "Activating orchestrator")
        scope.launch {
            orchestrator.start()
        }
    }

    private fun deactivate() {
        logger.i(TAG, "Deactivating orchestrator")
        scope.launch {
            orchestrator.stop()
        }
    }

    private fun shutdown() {
        logger.i(TAG, "Shutting down")
        scope.launch {
            orchestrator.stop()
            stopSelf()
        }
    }

    private fun startStateObserver() {
        stateObserverJob = scope.launch {
            orchestrator.state.collect { state ->
                updateNotification(state)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun updateNotification(state: OrchestratorState) {
        val text = when (state) {
            is OrchestratorState.Idle -> "Idle"
            is OrchestratorState.Preparing -> state.step
            is OrchestratorState.Running -> "Broadcasting to Zwift"
            is OrchestratorState.Error -> "Error: ${state.message}"
            is OrchestratorState.Stopping -> "Stopping…"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(contentText: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopIntent = Intent(this, HyperboreaService::class.java).apply {
            action = ACTION_SHUTDOWN
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(this)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Hyperborea")
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null, "Stop", stopPendingIntent,
                ).build(),
            )
            .build()
    }

    companion object {
        const val ACTION_BOOT = "com.nettarion.hyperborea.action.BOOT"
        const val ACTION_ACTIVATE = "com.nettarion.hyperborea.action.ACTIVATE"
        const val ACTION_DEACTIVATE = "com.nettarion.hyperborea.action.DEACTIVATE"
        const val ACTION_SHUTDOWN = "com.nettarion.hyperborea.action.SHUTDOWN"

        private const val NOTIFICATION_ID = 1
        private const val TAG = "Service"
    }
}
