package com.nettarion.hyperborea.overlay

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.adapter.HardwareAdapter
import com.nettarion.hyperborea.core.orchestration.Orchestrator
import com.nettarion.hyperborea.core.orchestration.OrchestratorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class OverlayManager(
    private val context: Context,
    private val orchestrator: Orchestrator,
    private val hardwareAdapter: HardwareAdapter,
    private val overlayEnabled: StateFlow<Boolean>,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
    private val onStop: () -> Unit,
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: OverlayBarView? = null
    private var exerciseDataJob: Job? = null
    private var stateJob: Job? = null
    private var userDismissed = false
    private var isAppInForeground = false
    private var lastState: OrchestratorState = OrchestratorState.Idle

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        private var startedCount = 0

        override fun onActivityStarted(activity: Activity) {
            startedCount++
            if (startedCount == 1) {
                isAppInForeground = true
                hide()
            }
        }

        override fun onActivityStopped(activity: Activity) {
            startedCount--
            if (startedCount == 0) {
                isAppInForeground = false
                if (shouldShowOverlay()) show()
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    init {
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(lifecycleCallbacks)
    }

    fun show() {
        if (view != null) return

        val params = createLayoutParams()
        val overlayView = OverlayBarView(
            context = context,
            layoutParams = params,
            windowManager = windowManager,
            onPauseClick = onPause,
            onResumeClick = onResume,
            onStopClick = onStop,
        )

        windowManager.addView(overlayView, params)
        view = overlayView
        userDismissed = false

        startCollectors(overlayView)
        logger.d(TAG, "Overlay shown")
    }

    fun hide() {
        val v = view ?: return
        exerciseDataJob?.cancel()
        stateJob?.cancel()
        exerciseDataJob = null
        stateJob = null

        windowManager.removeView(v)
        view = null
        logger.d(TAG, "Overlay hidden")
    }

    fun toggle() {
        if (view != null) {
            userDismissed = true
            hide()
        } else {
            userDismissed = false
            if (!isAppInForeground) show()
        }
    }

    fun onStateChanged(state: OrchestratorState) {
        lastState = state
        when (state) {
            is OrchestratorState.Running -> {
                if (shouldShowOverlay()) show()
                view?.post { view?.updateState(state) }
            }
            is OrchestratorState.Paused -> {
                view?.post { view?.updateState(state) }
            }
            is OrchestratorState.Idle,
            is OrchestratorState.Stopping,
            is OrchestratorState.Error,
            -> {
                userDismissed = false
                hide()
            }
            is OrchestratorState.Preparing -> {
                // No action during preparation
            }
        }
    }

    fun destroy() {
        hide()
        (context.applicationContext as Application).unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
    }

    private fun shouldShowOverlay(): Boolean {
        return overlayEnabled.value &&
            !isAppInForeground &&
            !userDismissed &&
            (lastState is OrchestratorState.Running || lastState is OrchestratorState.Paused)
    }

    private fun startCollectors(overlayView: OverlayBarView) {
        exerciseDataJob = scope.launch {
            hardwareAdapter.exerciseData.collect { data ->
                overlayView.post { overlayView.updateExerciseData(data) }
            }
        }
        stateJob = scope.launch {
            orchestrator.state.collect { state ->
                overlayView.post { overlayView.updateState(state) }
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
            x = 0
            y = 100
        }
    }

    companion object {
        private const val TAG = "Overlay"
    }
}
