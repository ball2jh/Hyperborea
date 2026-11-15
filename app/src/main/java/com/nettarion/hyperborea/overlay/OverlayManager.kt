package com.nettarion.hyperborea.overlay

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.adapter.HardwareAdapter
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.orchestration.Orchestrator
import com.nettarion.hyperborea.core.orchestration.OrchestratorState
import com.nettarion.hyperborea.core.profile.OverlayStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class OverlayManager(
    private val context: Context,
    private val orchestrator: Orchestrator,
    private val hardwareAdapter: HardwareAdapter,
    private val overlayEnabled: StateFlow<Boolean>,
    private val overlayStyle: StateFlow<OverlayStyle>,
    private val useImperial: StateFlow<Boolean>,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
    private val onStart: () -> Unit,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
    private val onStop: () -> Unit,
    private val programController: com.nettarion.hyperborea.program.ProgramController,
    private val foreground: StateFlow<Boolean>,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var view: View? = null
    private var contentView: OverlayContentView? = null
    private var exerciseDataJob: Job? = null
    private var stateJob: Job? = null
    private var programJob: Job? = null
    private var unitsJob: Job? = null
    private var userDismissed = false
    private var isAppInForeground = foreground.value
    private var lastState: OrchestratorState = OrchestratorState.Idle
    @Volatile private var destroyed = false
    private val visibilityJob: Job

    init {
        visibilityJob = scope.launch {
            combine(foreground, overlayEnabled, overlayStyle) { visible, _, _ -> visible }
                .collect { visible ->
                    mainHandler.post {
                        if (!destroyed) {
                            isAppInForeground = visible
                            logger.d(TAG, "App foreground: $visible")
                            // Rebuild on a style change; react to enable/disable without waiting
                            // for the next workout-state or activity event.
                            hide()
                            if (shouldShowOverlay()) show()
                        }
                    }
                }
        }
    }

    fun show() {
        if (destroyed || view != null) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            logger.e(TAG, "show() called off main thread: ${Thread.currentThread().name}")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            logger.w(TAG, "Cannot show overlay: SYSTEM_ALERT_WINDOW not granted")
            return
        }

        val params = createLayoutParams()
        val overlayView: View = when (overlayStyle.value) {
            OverlayStyle.METRICS -> OverlayBarView(
                context = context,
                layoutParams = params,
                windowManager = windowManager,
                onPauseClick = onPause,
                onResumeClick = onResume,
                onStopClick = onStop,
                onPositionChanged = { x, y -> savePosition(x, y) },
            )
            OverlayStyle.CONTROLS -> OverlayControlBarView(
                context = context,
                layoutParams = params,
                windowManager = windowManager,
                onAdjustIncline = { increase -> sendCommand(DeviceCommand.AdjustIncline(increase)) },
                onAdjustSpeed = { increase -> sendCommand(DeviceCommand.AdjustSpeed(increase)) },
                onStartClick = onStart,
                onPauseClick = onPause,
                onResumeClick = onResume,
                onStopClick = onStop,
                onPositionChanged = { x, y -> savePosition(x, y) },
            ).apply { setUseImperial(useImperial.value) }
        }

        windowManager.addView(overlayView, params)
        view = overlayView
        contentView = overlayView as OverlayContentView
        userDismissed = false

        contentView?.updateState(lastState)
        startCollectors()
        logger.d(TAG, "Overlay shown (style=${overlayStyle.value})")
    }

    fun hide() {
        val v = view ?: return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            logger.e(TAG, "hide() called off main thread: ${Thread.currentThread().name}")
        }
        exerciseDataJob?.cancel()
        stateJob?.cancel()
        programJob?.cancel()
        programJob = null
        unitsJob?.cancel()
        exerciseDataJob = null
        stateJob = null
        unitsJob = null

        windowManager.removeView(v)
        view = null
        contentView = null
        logger.d(TAG, "Overlay hidden")
    }

    fun toggle() {
        if (view != null) {
            logger.d(TAG, "Overlay toggle: dismissing")
            userDismissed = true
            hide()
        } else if (isAppInForeground) {
            logger.d(TAG, "Overlay toggle: app in foreground, not showing")
            userDismissed = false
        } else {
            logger.d(TAG, "Overlay toggle: showing")
            userDismissed = false
            show()
        }
    }

    fun onStateChanged(state: OrchestratorState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            logger.d(TAG, "onStateChanged dispatching to main thread (state=$state)")
        }
        mainHandler.post {
            if (destroyed) return@post
            lastState = state
            if (stateAllowsOverlay(overlayStyle.value, state)) {
                if (shouldShowOverlay()) show()
                contentView?.updateState(state)
            } else {
                // A hide-causing state ends the "user dismissed it this workout" suppression, so
                // the overlay comes back for the next workout (and, in CONTROLS style, for the
                // post-workout Idle). Mid-workout dismissal recovery is the notification toggle.
                logger.d(TAG, "Overlay auto-hidden (state=$state)")
                userDismissed = false
                hide()
            }
        }
    }

    fun destroy() {
        destroyed = true
        visibilityJob.cancel()
        hide()
    }

    private fun shouldShowOverlay(): Boolean {
        val result = overlayEnabled.value &&
            !isAppInForeground &&
            !userDismissed &&
            stateAllowsOverlay(overlayStyle.value, lastState)
        logger.d(
            TAG,
            "shouldShowOverlay=$result (enabled=${overlayEnabled.value}, style=${overlayStyle.value}, " +
                "fg=$isAppInForeground, dismissed=$userDismissed, state=$lastState)",
        )
        return result
    }

    private fun sendCommand(command: DeviceCommand) {
        scope.launch {
            try {
                programController.manualControl(command)
                hardwareAdapter.sendCommand(command)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, "Overlay command failed: $command — ${e.message}")
            }
        }
    }

    private fun startCollectors() {
        programJob = scope.launch {
            programController.state.collect { state -> view?.post { contentView?.updateProgram(state, useImperial.value) } }
        }
        exerciseDataJob = scope.launch {
            hardwareAdapter.exerciseData.collect { data ->
                view?.post { contentView?.updateExerciseData(data) }
            }
        }
        stateJob = scope.launch {
            orchestrator.state.collect { state ->
                view?.post { contentView?.updateState(state) }
            }
        }
        unitsJob = scope.launch {
            useImperial.collect { imperial ->
                view?.post {
                    (contentView as? OverlayControlBarView)?.setUseImperial(imperial)
                    contentView?.updateProgram(programController.state.value, imperial)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun createLayoutParams(): WindowManager.LayoutParams {
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, DEFAULT_X)
            y = prefs.getInt(KEY_Y, DEFAULT_Y)
        }
    }

    private fun savePosition(x: Int, y: Int) {
        prefs.edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }

    fun resetPosition() {
        prefs.edit().remove(KEY_X).remove(KEY_Y).apply()
        val v = view ?: return
        val params = v.layoutParams as WindowManager.LayoutParams
        params.x = DEFAULT_X
        params.y = DEFAULT_Y
        windowManager.updateViewLayout(v, params)
    }

    companion object {
        private const val TAG = "Overlay"
        private const val PREFS_NAME = "overlay_position"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val DEFAULT_X = 0
        private const val DEFAULT_Y = 100
    }
}

/**
 * Which orchestrator states each overlay style may be visible in. METRICS is a mid-workout HUD;
 * CONTROLS is a standing remote, so it also persists through Idle (start a workout from another
 * app) and the armed/preparing states. Error and Stopping hide both — errors route the user to
 * the app for detail.
 */
internal fun stateAllowsOverlay(style: OverlayStyle, state: OrchestratorState): Boolean = when (style) {
    OverlayStyle.METRICS ->
        state is OrchestratorState.Running || state is OrchestratorState.Paused
    OverlayStyle.CONTROLS ->
        state is OrchestratorState.Idle ||
            state is OrchestratorState.Preparing ||
            state is OrchestratorState.AwaitingConsoleStart ||
            state is OrchestratorState.Running ||
            state is OrchestratorState.Paused
}
