package com.nettarion.hyperborea

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.adapter.HardwareAdapter
import com.nettarion.hyperborea.core.orchestration.BroadcastManager
import com.nettarion.hyperborea.core.orchestration.Orchestrator
import com.nettarion.hyperborea.core.orchestration.OrchestratorState
import com.nettarion.hyperborea.core.profile.UserPreferences
import com.nettarion.hyperborea.overlay.OverlayManager
import com.nettarion.hyperborea.platform.runTeardownWithin
import com.nettarion.hyperborea.platform.update.UpdateManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class HyperboreaService : Service() {

    @Inject lateinit var orchestrator: Orchestrator
    @Inject lateinit var broadcastManager: BroadcastManager
    @Inject lateinit var hardwareAdapter: HardwareAdapter
    @Inject lateinit var userPreferences: UserPreferences
    @Inject lateinit var logger: AppLogger
    @Inject lateinit var scope: CoroutineScope
    @Inject lateinit var updateManager: UpdateManager
    @Inject lateinit var appVisibility: com.nettarion.hyperborea.platform.AppVisibility

    @Inject lateinit var programController: com.nettarion.hyperborea.program.ProgramController

    private var stateObserverJob: Job? = null
    private var programNotificationJob: Job? = null
    private lateinit var overlayManager: OverlayManager

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForegroundCompat(buildNotification("Starting…"))
        overlayManager = OverlayManager(
            context = this,
            orchestrator = orchestrator,
            hardwareAdapter = hardwareAdapter,
            overlayEnabled = userPreferences.overlayEnabled,
            overlayStyle = userPreferences.overlayStyle,
            useImperial = userPreferences.useImperial,
            logger = logger,
            scope = scope,
            programController = programController,
            foreground = appVisibility.foreground,
            onStart = { activate() },
            onPause = { pause() },
            onResume = { resume() },
            onStop = {
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                    ?: Intent(this, MainActivity::class.java)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchIntent.putExtra("show_stop_dialog", true)
                startActivity(launchIntent)
            },
        )
        programController.startMonitoring()
        orchestrator.startProbing()
        scope.launch {
            broadcastManager.start()
            updateManager.startAutoUpdate()
        }
        // Persist the identified device type so device-aware UI copy (profile picker prompt)
        // survives restarts and renders before the USB probe completes.
        scope.launch {
            hardwareAdapter.deviceInfo
                .filterNotNull()
                .map { it.type }
                .distinctUntilChanged()
                .collect { userPreferences.setLastKnownDeviceType(it) }
        }
        startStateObserver()
        logger.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BOOT -> logger.i(TAG, "Started from boot — idle, waiting for activation")
            ACTION_START_PROGRAM -> scope.launch {
                try {
                    val program = com.nettarion.hyperborea.program.ProgramCodec.decode(intent.getStringExtra(EXTRA_PROGRAM) ?: error("Missing program"))
                    programController.start(program)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    logger.e(TAG, "Couldn't start program", e)
                    // The Programs screen has already navigated away; tell the user why nothing runs.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            this@HyperboreaService,
                            "Couldn't start program: ${e.message ?: "unexpected error"}",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            ACTION_ACTIVATE -> activate()
            ACTION_DEACTIVATE -> deactivate(saveRide = true)
            ACTION_DEACTIVATE_DISCARD -> deactivate(saveRide = false)
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_TOGGLE_OVERLAY -> {
                logger.d(TAG, "Toggle overlay")
                overlayManager.toggle()
            }
            ACTION_SHUTDOWN -> shutdown()
            else -> logger.i(TAG, "Started with no action — idle")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        overlayManager.destroy()
        stateObserverJob?.cancel()
        programNotificationJob?.cancel()
        orchestrator.stopProbing()
        // Deliberately blocks the main thread: orchestrator.stop() is what halts a treadmill belt,
        // and once onDestroy returns the process may die before an async teardown finishes — so
        // wait for it, but only up to STOP_TIMEOUT_MS. The stops run on the application scope,
        // not inside this wait: orchestrator.stop() is uncancellable once begun (a half-run
        // teardown strands the state machine) and can take tens of seconds against a controller
        // that has stopped answering, which would be an ANR if we joined it. On timeout we stop
        // waiting; the teardown itself carries on for as long as the process lives. The belt
        // PAUSE is the first thing it writes, so it lands well inside the budget either way.
        val finished = runTeardownWithin(
            scope, STOP_TIMEOUT_MS,
            { programController.stopMonitoring() },
            { orchestrator.stop() },
            { broadcastManager.stop() },
        )
        if (!finished) logger.w(TAG, "Teardown still running after ${STOP_TIMEOUT_MS}ms — onDestroy not waiting further")
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
            programController.clearFinished()
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
        programNotificationJob = scope.launch {
            programController.state.map { run -> run?.let { com.nettarion.hyperborea.program.programStatusText(it, userPreferences.useImperial.value) } }
                .distinctUntilChanged().collect { updateNotification(orchestrator.state.value) }
        }
        stateObserverJob = scope.launch {
            orchestrator.state.collect { state ->
                updateNotification(state)
                overlayManager.onStateChanged(state)
            }
        }
    }

    private fun updateNotification(state: OrchestratorState) {
        val text = when (state) {
            is OrchestratorState.Idle -> "Idle"
            is OrchestratorState.Preparing -> state.step
            is OrchestratorState.AwaitingConsoleStart -> state.message
            is OrchestratorState.Running -> if (state.degraded != null) "Degraded: ${state.degraded}" else "Broadcasting to Zwift"
            is OrchestratorState.Paused -> "Paused"
            is OrchestratorState.Error -> "Error: ${state.message}"
            is OrchestratorState.Stopping -> "Stopping…"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val program = programController.state.value?.takeIf { it.active }
        manager.notify(NOTIFICATION_ID, buildNotification(program?.let { com.nettarion.hyperborea.program.programStatusText(it, userPreferences.useImperial.value) } ?: text))
    }

    /**
     * `connectedDevice` because we own a USB↔BLE/WiFi bridge; the type is mandatory on API 34+
     * (`MissingForegroundServiceTypeException` otherwise) and the manifest declares
     * `FOREGROUND_SERVICE_CONNECTED_DEVICE` + `CHANGE_NETWORK_STATE` to authorise it. The 3-arg
     * `startForeground` overload is API 29+.
     */
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Notification channels are required when posting on API 26+; a no-op below that. */
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Hyperborea", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the bridge to Zwift running"
                setShowBadge(false)
            },
        )
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(contentText: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val overlayIntent = Intent(this, HyperboreaService::class.java).apply {
            action = ACTION_TOGGLE_OVERLAY
        }
        val overlayPendingIntent = PendingIntent.getService(
            this, 1, overlayIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, HyperboreaService::class.java).apply {
            action = ACTION_SHUTDOWN
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Notification icons are rendered by the *system* process, so they must be
        // app-owned (not android.R.drawable.*) AND raster PNGs (not vector drawables):
        // stock iFit console firmware ships a stripped framework-res whose system process
        // can't inflate a <vector>, and an icon that fails to load makes the system reject
        // the whole notification ("Bad notification for startForeground") and kill us.
        // The two action icons aren't drawn by the standard notification template, so they
        // reuse the status icon rather than carrying their own raster sets.
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_stat_hyperborea)
            .setContentTitle("Hyperborea")
            .setContentText(contentText)
            .setStyle(Notification.BigTextStyle().bigText(contentText))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_stat_hyperborea, "Overlay", overlayPendingIntent,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_stat_hyperborea, "Stop", stopPendingIntent,
                ).build(),
            )
            .build()
    }

    companion object {
        const val ACTION_START_PROGRAM = "com.nettarion.hyperborea.action.START_PROGRAM"
        const val EXTRA_PROGRAM = "workout_program"
        const val ACTION_BOOT = "com.nettarion.hyperborea.action.BOOT"
        const val ACTION_ACTIVATE = "com.nettarion.hyperborea.action.ACTIVATE"
        const val ACTION_DEACTIVATE = "com.nettarion.hyperborea.action.DEACTIVATE"
        const val ACTION_DEACTIVATE_DISCARD = "com.nettarion.hyperborea.action.DEACTIVATE_DISCARD"
        const val ACTION_PAUSE = "com.nettarion.hyperborea.action.PAUSE"
        const val ACTION_RESUME = "com.nettarion.hyperborea.action.RESUME"
        const val ACTION_TOGGLE_OVERLAY = "com.nettarion.hyperborea.action.TOGGLE_OVERLAY"
        const val ACTION_SHUTDOWN = "com.nettarion.hyperborea.action.SHUTDOWN"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "hyperborea_service"
        // Upper bound on how long onDestroy blocks the main thread waiting for teardown — kept
        // well inside the ANR window. It bounds the wait only; the teardown is never cut short.
        private const val STOP_TIMEOUT_MS = 3000L
        private const val TAG = "Service"
    }
}
