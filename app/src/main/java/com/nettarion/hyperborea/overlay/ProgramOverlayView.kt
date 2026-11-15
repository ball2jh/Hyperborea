package com.nettarion.hyperborea.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.orchestration.OrchestratorState
import com.nettarion.hyperborea.core.program.ProgramRunState
import com.nettarion.hyperborea.program.programOverlayPresentation
import com.nettarion.hyperborea.program.ProgramOverlayPresentation

/** The compact Next target design shared by both background overlay styles during a program. */
@SuppressLint("ViewConstructor", "SetTextI18n")
internal class ProgramOverlayView(
    context: Context,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
    private val onStop: () -> Unit,
) : LinearLayout(context) {
    private val number = label(36f, OverlayPalette.AMBER, bold = true)
    private val caption = label(12f, OverlayPalette.AMBER)
    val dragHandle = label(22f, OverlayPalette.TEXT_MEDIUM).apply {
        text = "⠿"
        gravity = Gravity.CENTER
        contentDescription = "Drag widget"
        layoutParams = LayoutParams(dp(44), dp(44))
    }
    private val speedFrom = label(12f, SECONDARY)
    private val speedTo = label(30f, OverlayPalette.TEXT_HIGH, bold = true)
    private val inclineFrom = label(12f, SECONDARY)
    private val inclineTo = label(30f, OverlayPalette.TEXT_HIGH, bold = true)
    private val pause = action("Pause", OverlayPalette.TEXT_HIGH) {
        if (workout is OrchestratorState.Paused) onResume() else onPause()
    }
    private val stop = action("Stop", OverlayPalette.STATUS_ERROR, onStop)
    private var run: ProgramRunState? = null
    private var data: ExerciseData? = null
    private var imperial = false
    private var workout: OrchestratorState = OrchestratorState.Idle
    private var displayed: Pair<ProgramOverlayPresentation, OrchestratorState>? = null

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply {
            setColor(OverlayPalette.SURFACE)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), OverlayPalette.DIVIDER)
        }
        visibility = GONE
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val status = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(number)
                addView(caption, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
                contentDescription = "Program countdown; tap to open Hyperborea"
                setOnClickListener {
                    context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                        context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            addView(status, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(dragHandle)
        }
        addView(header)
        addView(metric("Speed", speedFrom, speedTo))
        addView(metric("Incline", inclineFrom, inclineTo))
        addView(View(context).apply { setBackgroundColor(OverlayPalette.DIVIDER) }, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(10) })
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(pause, LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(5) })
            addView(stop, LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(5) })
        })
    }

    fun updateProgram(value: ProgramRunState?, useImperial: Boolean) {
        run = value
        imperial = useImperial
        visibility = if (value?.active == true) VISIBLE else GONE
        render()
    }

    fun updateData(value: ExerciseData?) { data = value; render() }
    fun updateWorkout(value: OrchestratorState) { workout = value; render() }

    private fun render() {
        val state = run ?: return
        val model = programOverlayPresentation(state, data, imperial)
        val next = model to workout
        if (displayed == next) return
        displayed = next
        number.text = model.value
        number.textSize = if (model.value.toIntOrNull() == null) 25f else 36f
        caption.text = model.caption
        number.setTextColor(if (model.warning) OverlayPalette.AMBER else OverlayPalette.TEXT_HIGH)
        caption.setTextColor(if (model.warning) OverlayPalette.AMBER else SECONDARY)
        speedFrom.text = "From ${model.currentSpeed} ${model.speedUnit}"
        speedTo.text = targetText(model.targetSpeed, " ${model.speedUnit}")
        inclineFrom.text = "From ${model.currentIncline}%"
        inclineTo.text = targetText(model.targetIncline, "%")
        pause.text = if (workout is OrchestratorState.Paused) "▶  Resume" else "Ⅱ  Pause"
        val actionable = workout is OrchestratorState.Running || workout is OrchestratorState.Paused
        pause.isEnabled = actionable
        pause.alpha = if (actionable) 1f else .4f
        stop.isEnabled = actionable || workout is OrchestratorState.AwaitingConsoleStart
        stop.alpha = if (stop.isEnabled) 1f else .4f
    }

    private fun targetText(value: String, unit: String): CharSequence {
        val text = "→ $value$unit"
        return android.text.SpannableString(text).apply {
            setSpan(android.text.style.RelativeSizeSpan(.55f), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.RelativeSizeSpan(.43f), text.length - unit.length, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun metric(name: String, from: TextView, target: TextView) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(9), 0, dp(9))
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(label(14f, SECONDARY).apply { text = name })
            addView(from)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(target)
    }

    private fun label(size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun action(name: String, color: Int, onClick: () -> Unit) = Button(context).apply {
        text = name
        isAllCaps = false
        textSize = 14f
        setTextColor(color)
        setPadding(dp(8), 0, dp(8), 0)
        minimumWidth = 0
        minimumHeight = 0
        background = GradientDrawable().apply {
            setColor(OverlayPalette.SURFACE)
            cornerRadius = dp(7).toFloat()
            setStroke(dp(1), OverlayPalette.TEXT_LOW)
        }
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private companion object { const val SECONDARY = 0xFFA1AABC.toInt() }
}
