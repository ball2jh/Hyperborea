package com.nettarion.hyperborea.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.orchestration.OrchestratorState

/**
 * "Control bar" overlay style: a floating treadmill remote — incline −/+, speed −/+, and
 * start/pause/resume/stop — usable while another app (e.g. iFit) is in the foreground. Unlike
 * [OverlayBarView] it is also shown while Idle so a workout can be started from anywhere.
 *
 * −/+ buttons auto-repeat on press-and-hold, mirroring the dashboard clusters; the hardware
 * session accumulates each step into a pending target exactly like the physical console keys.
 */
// SetTextI18n: fixed-orientation English-only kiosk HUD, never localised.
@SuppressLint("ViewConstructor", "SetTextI18n")
class OverlayControlBarView(
    context: Context,
    private val layoutParams: WindowManager.LayoutParams,
    private val windowManager: WindowManager,
    private val onAdjustIncline: (increase: Boolean) -> Unit,
    private val onAdjustSpeed: (increase: Boolean) -> Unit,
    private val onStartClick: () -> Unit,
    private val onPauseClick: () -> Unit,
    private val onResumeClick: () -> Unit,
    private val onStopClick: () -> Unit,
    private val onPositionChanged: ((x: Int, y: Int) -> Unit)? = null,
) : FrameLayout(context), OverlayContentView {

    private val inclValue: TextView
    private val spdValue: TextView
    private val spdUnit: TextView
    private val startPauseButton: TextView
    private val stopButton: TextView
    private val adjustButtons: List<TextView>

    private var state: OrchestratorState = OrchestratorState.Idle
    private var useImperial = false
    private var lastData: ExerciseData? = null

    init {
        val dp = { value: Int -> dpToPx(value) }

        setBackgroundColor(OverlayPalette.SURFACE)
        alpha = 0.92f

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), dp(8), dp(4))
        }

        // Drag handle
        val dragHandle = TextView(context).apply {
            text = "⠿"
            setTextColor(OverlayPalette.TEXT_LOW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        setupDragHandle(dragHandle)
        root.addView(dragHandle)
        root.addView(makeDivider())

        // Incline cluster: − INCL x.x% +
        val inclDown = makeAdjustButton("−") { onAdjustIncline(false) }
        inclValue = makeValueText()
        val inclUp = makeAdjustButton("+") { onAdjustIncline(true) }
        root.addView(inclDown)
        root.addView(makeMetricCell("INCL", inclValue))
        root.addView(inclUp)
        root.addView(makeDivider())

        // Speed cluster: − SPD xx.x unit +
        val spdDown = makeAdjustButton("−") { onAdjustSpeed(false) }
        spdValue = makeValueText()
        spdUnit = TextView(context).apply {
            setTextColor(OverlayPalette.TEXT_MEDIUM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(2), 0, 0, 0)
        }
        val spdUp = makeAdjustButton("+") { onAdjustSpeed(true) }
        root.addView(spdDown)
        root.addView(makeMetricCell("SPD", spdValue, spdUnit))
        root.addView(spdUp)
        root.addView(makeDivider())

        adjustButtons = listOf(inclDown, inclUp, spdDown, spdUp)

        // Start / pause / resume — repurposed per state.
        startPauseButton = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                when (state) {
                    is OrchestratorState.Idle, is OrchestratorState.Error -> onStartClick()
                    is OrchestratorState.Running -> onPauseClick()
                    is OrchestratorState.Paused -> onResumeClick()
                    else -> {} // Preparing / AwaitingConsoleStart / Stopping: inert.
                }
            }
        }
        root.addView(startPauseButton)

        stopButton = TextView(context).apply {
            text = "■"
            setTextColor(OverlayPalette.STATUS_ERROR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { onStopClick() }
        }
        root.addView(stopButton)

        addView(root, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        applyState()
    }

    fun setUseImperial(imperial: Boolean) {
        useImperial = imperial
        updateExerciseData(lastData)
    }

    override fun updateExerciseData(data: ExerciseData?) {
        lastData = data
        inclValue.text = data?.incline?.let { formatIncline(it) } ?: "—"
        spdValue.text = data?.speed?.let {
            "%.1f".format(if (useImperial) it * KM_TO_MI else it)
        } ?: "—"
        spdUnit.text = if (useImperial) "mph" else "km/h"
    }

    override fun updateState(state: OrchestratorState) {
        this.state = state
        applyState()
    }

    private fun applyState() {
        val (label, color) = when (state) {
            is OrchestratorState.Idle, is OrchestratorState.Error -> "▶ START" to OverlayPalette.STATUS_ACTIVE
            is OrchestratorState.Preparing -> "…" to OverlayPalette.TEXT_MEDIUM
            is OrchestratorState.AwaitingConsoleStart -> "PRESS START ON CONSOLE" to OverlayPalette.AMBER
            is OrchestratorState.Running -> "⏸" to OverlayPalette.AMBER
            is OrchestratorState.Paused -> "▶" to OverlayPalette.STATUS_ACTIVE
            is OrchestratorState.Stopping -> "…" to OverlayPalette.TEXT_MEDIUM
        }
        startPauseButton.text = label
        startPauseButton.setTextColor(color)

        // Stop is meaningful once something is in flight (armed, running, or paused).
        val stopVisible = state is OrchestratorState.Running ||
            state is OrchestratorState.Paused ||
            state is OrchestratorState.AwaitingConsoleStart
        stopButton.visibility = if (stopVisible) View.VISIBLE else View.GONE

        // Speed/incline targets only apply to a moving belt; while Paused the belt is stopped
        // and while armed a stored target would kick in the moment the belt starts.
        val adjustEnabled = state is OrchestratorState.Running
        adjustButtons.forEach {
            it.alpha = if (adjustEnabled) 1f else 0.35f
            it.isEnabled = adjustEnabled
        }
    }

    private fun formatIncline(value: Float): String {
        return if (value == value.toLong().toFloat()) {
            "${value.toInt()}%"
        } else {
            "${"%.1f".format(value)}%"
        }
    }

    private fun makeMetricCell(label: String, valueView: TextView, unitView: TextView? = null): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))

            val labelText = TextView(context).apply {
                text = label
                setTextColor(OverlayPalette.TEXT_MEDIUM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, dpToPx(4), 0)
            }
            addView(labelText)
            addView(valueView)
            if (unitView != null) addView(unitView)
        }
    }

    private fun makeValueText(): TextView {
        return TextView(context).apply {
            text = "—"
            setTextColor(OverlayPalette.TEXT_HIGH)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    /** −/+ button with tap + press-and-hold auto-repeat. */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeAdjustButton(symbol: String, onTick: () -> Unit): TextView {
        return TextView(context).apply {
            text = symbol
            setTextColor(OverlayPalette.TEXT_HIGH)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))

            val handler = Handler(Looper.getMainLooper())
            val repeater = object : Runnable {
                override fun run() {
                    onTick()
                    handler.postDelayed(this, HOLD_REPEAT_INTERVAL_MS)
                }
            }
            setOnTouchListener { _, event ->
                if (!isEnabled) return@setOnTouchListener true
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        onTick()
                        handler.postDelayed(repeater, HOLD_REPEAT_INITIAL_MS)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(repeater)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun makeDivider(): View {
        return View(context).apply {
            setBackgroundColor(OverlayPalette.DIVIDER)
            layoutParams = LinearLayout.LayoutParams(dpToPx(1), dpToPx(24))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragHandle(handle: View) {
        var startX = 0
        var startY = 0
        var startParamX = 0
        var startParamY = 0

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX.toInt()
                    startY = event.rawY.toInt()
                    startParamX = layoutParams.x
                    startParamY = layoutParams.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = startParamX + (event.rawX.toInt() - startX)
                    layoutParams.y = startParamY + (event.rawY.toInt() - startY)
                    windowManager.updateViewLayout(this@OverlayControlBarView, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    onPositionChanged?.invoke(layoutParams.x, layoutParams.y)
                    true
                }
                else -> false
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics,
        ).toInt()
    }

    companion object {
        private const val KM_TO_MI = 0.621371f
        private const val HOLD_REPEAT_INITIAL_MS = 500L
        private const val HOLD_REPEAT_INTERVAL_MS = 350L
    }
}
