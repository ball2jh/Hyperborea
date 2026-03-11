package com.nettarion.hyperborea

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.LicenseChecker
import com.nettarion.hyperborea.core.LicenseState
import com.nettarion.hyperborea.core.adapter.HardwareAdapter
import com.nettarion.hyperborea.core.orchestration.BroadcastManager
import com.nettarion.hyperborea.core.orchestration.Orchestrator
import com.nettarion.hyperborea.core.orchestration.OrchestratorState
import com.nettarion.hyperborea.overlay.OverlayManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@AndroidEntryPoint
class HyperboreaService : Service() {

    @Inject lateinit var orchestrator: Orchestrator
    @Inject lateinit var broadcastManager: BroadcastManager
    @Inject lateinit var hardwareAdapter: HardwareAdapter
    @Inject lateinit var logger: AppLogger
    @Inject lateinit var scope: CoroutineScope
    @Inject lateinit var licenseChecker: LicenseChecker

    private var stateObserverJob: Job? = null
    private lateinit var overlayManager: OverlayManager

    @SuppressLint("ForegroundServiceType") // API 25 target — no foregroundServiceType needed
    override fun onCreate() {
        super.onCreate()
        @Suppress("DEPRECATION")
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
        overlayManager = OverlayManager(
            context = this,
            orchestrator = orchestrator,
            hardwareAdapter = hardwareAdapter,
            logger = logger,
            scope = scope,
            onPause = { pause() },
            onResume = { resume() },
            onStop = { deactivate(saveRide = true) },
        )
        scope.launch { broadcastManager.start() }
        startStateObserver()
        logger.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BOOT -> logger.i(TAG, "Started from boot — idle, waiting for activation")
            ACTION_ACTIVATE -> activate()
            ACTION_DEACTIVATE -> deactivate(saveRide = true)
            ACTION_DEACTIVATE_DISCARD -> deactivate(saveRide = false)
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_TOGGLE_OVERLAY -> overlayManager.toggle()
            ACTION_SHUTDOWN -> shutdown()
            else -> logger.i(TAG, "Started with no action — idle")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        overlayManager.destroy()
        stateObserverJob?.cancel()
        runBlocking {
            withTimeoutOrNull(STOP_TIMEOUT_MS) {
                orchestrator.stop()
            } ?: logger.w(TAG, "Orchestrator stop timed out in onDestroy")
            withTimeoutOrNull(STOP_TIMEOUT_MS) {
                broadcastManager.stop()
            } ?: logger.w(TAG, "BroadcastManager stop timed out in onDestroy")
        }
        logger.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        logger.i(TAG, "Task removed — shutting down")
        scope.launch {
            orchestrator.stop()
            broadcastManager.stop()
            stopSelf()
        }
    }

    private fun activate() {
        logger.i(TAG, "Activating orchestrator")
        scope.launch {
            licenseChecker.check(silent = true)
            if (licenseChecker.state.value !is LicenseState.Licensed) {
                logger.w(TAG, "Cannot activate: not licensed")
                return@launch
            }
            orchestrator.start()
        }
    }

    private fun deactivate(saveRide: Boolean) {
        logger.i(TAG, "Deactivating orchestrator (saveRide=$saveRide)")
        scope.launch {
            orchestrator.stop(saveRide = saveRide)
        }
    }

    private fun pause() {
        logger.i(TAG, "Pausing orchestrator")
        scope.launch {
            orchestrator.pause()
        }
    }

    private fun resume() {
        logger.i(TAG, "Resuming orchestrator")
        scope.launch {
            orchestrator.resume()
        }
    }

    private fun shutdown() {
        logger.i(TAG, "Shutting down")
        scope.launch {
            orchestrator.stop()
            broadcastManager.stop()
            stopSelf()
        }
    }

    private fun startStateObserver() {
        stateObserverJob = scope.launch {
            orchestrator.state.collect { state ->
                updateNotification(state)
                overlayManager.onStateChanged(state)
            }
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("NotificationPermission") // API 25 target — POST_NOTIFICATIONS not required
    private fun updateNotification(state: OrchestratorState) {
        val text = when (state) {
            is OrchestratorState.Idle -> "Idle"
            is OrchestratorState.Preparing -> state.step
            is OrchestratorState.Running -> if (state.degraded != null) "Degraded: ${state.degraded}" else "Broadcasting to Zwift"
            is OrchestratorState.Paused -> "Paused"
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

        val overlayIntent = Intent(this, HyperboreaService::class.java).apply {
            action = ACTION_TOGGLE_OVERLAY
        }
        val overlayPendingIntent = PendingIntent.getService(
            this, 1, overlayIntent, PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopIntent = Intent(this, HyperboreaService::class.java).apply {
            action = ACTION_SHUTDOWN
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(this)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Hyperborea")
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null, "Overlay", overlayPendingIntent,
                ).build(),
            )
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
        const val ACTION_DEACTIVATE_DISCARD = "com.nettarion.hyperborea.action.DEACTIVATE_DISCARD"
        const val ACTION_PAUSE = "com.nettarion.hyperborea.action.PAUSE"
        const val ACTION_RESUME = "com.nettarion.hyperborea.action.RESUME"
        const val ACTION_TOGGLE_OVERLAY = "com.nettarion.hyperborea.action.TOGGLE_OVERLAY"
        const val ACTION_SHUTDOWN = "com.nettarion.hyperborea.action.SHUTDOWN"

        private const val NOTIFICATION_ID = 1
        private const val STOP_TIMEOUT_MS = 3000L
        private const val TAG = "Service"
    }
}
