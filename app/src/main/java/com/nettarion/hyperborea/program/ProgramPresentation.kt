package com.nettarion.hyperborea.program

import com.nettarion.hyperborea.core.program.ProgramRunState
import com.nettarion.hyperborea.core.program.ProgramStatus
import java.util.Locale

fun programStatusText(state: ProgramRunState, imperial: Boolean): String {
    fun speed(value: Float) = String.format(Locale.US, "%.1f %s", value * if (imperial) .621371f else 1f, if (imperial) "mph" else "km/h")
    fun incline(value: Float) = String.format(Locale.US, "%.1f%%", value)
    val time = "%d:%02d".format(state.remainingSeconds / 60, state.remainingSeconds % 60)
    val progress = if (state.program.loop) "Loop ${state.completedLoops + 1} · $time left in loop" else "$time left"
    val head = "${state.program.name} · ${state.block.name} · $progress"
    val current = "Targets: ${if (state.manualSpeed) "Speed manual until next block" else speed(state.target.speedKph)} · ${if (state.manualIncline) "Incline manual until next block" else incline(state.target.inclinePercent)}"
    if (!state.active) return "$head\n${state.message} · Save this program in the app"
    if (state.status == ProgramStatus.COUNTDOWN) return "$head\nCHANGE IN ${state.countdown}\nAfter count-in: ${speed(state.target.speedKph)} · ${incline(state.target.inclinePercent)}"
    if (state.status == ProgramStatus.MANUAL || state.status == ProgramStatus.PAUSED) return "$head\n${state.message}\nProgram targets on resume: ${speed(state.target.speedKph)} · ${incline(state.target.inclinePercent)}"
    val next = state.nextTarget?.let { "Next in ${state.nextChangeSeconds}s: ${if (state.manualSpeed) "Manual speed" else speed(state.target.speedKph)} → ${speed(it.speedKph)} · ${if (state.manualIncline) "Manual incline" else incline(state.target.inclinePercent)} → ${incline(it.inclinePercent)}" }
    return listOfNotNull(head, state.countdown?.let { if (state.nextTarget == null && !state.program.loop) "BELT STOPS IN $it" else "CHANGE IN $it" } ?: state.message, if (state.manualSpeed || state.manualIncline) listOfNotNull(current, next).joinToString("\n") else next ?: current).joinToString("\n")
}
