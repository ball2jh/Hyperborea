package com.nettarion.hyperborea.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
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

@SuppressLint("ViewConstructor")
class OverlayBarView(
    context: Context,
    private val layoutParams: WindowManager.LayoutParams,
    private val windowManager: WindowManager,
    private val onPauseClick: () -> Unit,
    private val onResumeClick: () -> Unit,
    private val onStopClick: () -> Unit,
) : FrameLayout(context) {

    private val resValue: TextView
    private val pwrValue: TextView
    private val pwrTarget: TextView
    private val rpmValue: TextView
    private val inclValue: TextView
    private val inclTarget: TextView
    private val pauseResumeButton: TextView
    private val stopButton: TextView

    private var isPaused = false

    init {
        val dp = { value: Int -> dpToPx(value) }

        setBackgroundColor(SURFACE_COLOR)
        alpha = 0.92f

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), dp(8), dp(4))
        }

        // Drag handle
        val dragHandle = TextView(context).apply {
            text = "⠿"
            setTextColor(TEXT_LOW_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        setupDragHandle(dragHandle)
        root.addView(dragHandle)

        // Divider after handle
        root.addView(makeDivider())

        // RES cell
        resValue = makeValueText()
        root.addView(makeMetricCell("RES", resValue))
        root.addView(makeDivider())

        // PWR cell (with target)
        pwrValue = makeValueText()
        pwrTarget = makeTargetText()
        root.addView(makeMetricCell("PWR", pwrValue, pwrTarget, "W"))
        root.addView(makeDivider())

        // RPM cell
        rpmValue = makeValueText()
        root.addView(makeMetricCell("RPM", rpmValue))
        root.addView(makeDivider())

        // INCL cell (with target)
        inclValue = makeValueText()
        inclTarget = makeTargetText()
        root.addView(makeMetricCell("INCL", inclValue, inclTarget, "%"))
        root.addView(makeDivider())

        // Pause/Resume button
        pauseResumeButton = makeButton("⏸").apply {
            setOnClickListener {
                if (isPaused) onResumeClick() else onPauseClick()
            }
        }
        root.addView(pauseResumeButton)

        // Stop button
        stopButton = makeButton("■").apply {
            setTextColor(STATUS_ERROR_COLOR)
            setOnClickListener { onStopClick() }
        }
        root.addView(stopButton)

        addView(root, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun updateExerciseData(data: ExerciseData?) {
        if (data == null) {
            resValue.text = "—"
            pwrValue.text = "—"
            pwrTarget.visibility = View.GONE
            rpmValue.text = "—"
            inclValue.text = "—"
            inclTarget.visibility = View.GONE
            return
        }

        resValue.text = data.resistance?.toString() ?: "—"

        pwrValue.text = data.power?.let { "${it}W" } ?: "—"
        if (data.targetPower != null) {
            pwrTarget.text = "→ ${data.targetPower}"
            pwrTarget.visibility = View.VISIBLE
        } else {
            pwrTarget.visibility = View.GONE
        }

        rpmValue.text = data.cadence?.toString() ?: "—"

        inclValue.text = data.incline?.let { formatIncline(it) } ?: "—"
        if (data.targetIncline != null) {
            inclTarget.text = "→ ${formatIncline(data.targetIncline!!)}"
            inclTarget.visibility = View.VISIBLE
        } else {
            inclTarget.visibility = View.GONE
        }
    }

    fun updateState(state: OrchestratorState) {
        isPaused = state is OrchestratorState.Paused
        pauseResumeButton.text = if (isPaused) "▶" else "⏸"
        pauseResumeButton.setTextColor(if (isPaused) STATUS_ACTIVE_COLOR else AMBER_COLOR)
    }

    private fun formatIncline(value: Float): String {
        return if (value == value.toLong().toFloat()) {
            "${value.toInt()}%"
        } else {
            "${"%.1f".format(value)}%"
        }
    }

    private fun makeMetricCell(
        label: String,
        valueView: TextView,
        targetView: TextView? = null,
        unit: String? = null,
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))

            val labelText = TextView(context).apply {
                text = label
                setTextColor(TEXT_MEDIUM_COLOR)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, dpToPx(4), 0)
            }
            addView(labelText)
            addView(valueView)
            if (targetView != null) {
                addView(targetView)
            }
        }
    }

    private fun makeValueText(): TextView {
        return TextView(context).apply {
            text = "—"
            setTextColor(TEXT_HIGH_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun makeTargetText(): TextView {
        return TextView(context).apply {
            setTextColor(ELECTRIC_BLUE_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dpToPx(4), 0, 0, 0)
            visibility = View.GONE
        }
    }

    private fun makeButton(symbol: String): TextView {
        return TextView(context).apply {
            text = symbol
            setTextColor(AMBER_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
        }
    }

    private fun makeDivider(): View {
        return View(context).apply {
            setBackgroundColor(DIVIDER_COLOR)
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
                    windowManager.updateViewLayout(this@OverlayBarView, layoutParams)
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
        // Colors matching ui/theme/Color.kt
        private const val SURFACE_COLOR = 0xFF0F1115.toInt()
        private const val TEXT_HIGH_COLOR = 0xFFF0F2F5.toInt()
        private const val TEXT_MEDIUM_COLOR = 0xFF7A8290.toInt()
        private const val TEXT_LOW_COLOR = 0xFF3D4350.toInt()
        private const val DIVIDER_COLOR = 0xFF1A1D24.toInt()
        private const val ELECTRIC_BLUE_COLOR = 0xFF3B82F6.toInt()
        private const val AMBER_COLOR = 0xFFF59E0B.toInt()
        private const val STATUS_ACTIVE_COLOR = 0xFF22C55E.toInt()
        private const val STATUS_ERROR_COLOR = 0xFFEF4444.toInt()
    }
}
