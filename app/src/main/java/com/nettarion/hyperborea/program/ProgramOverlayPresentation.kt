package com.nettarion.hyperborea.program

import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.program.ProgramRunState
import com.nettarion.hyperborea.core.program.ProgramStatus
import com.nettarion.hyperborea.core.program.ProgramTarget
import java.util.Locale

internal data class ProgramOverlayPresentation(
    val value: String,
    val caption: String,
    val currentSpeed: String,
    val targetSpeed: String,
    val speedUnit: String,
    val currentIncline: String,
    val targetIncline: String,
    val warning: Boolean,
)

/** Live measurements are kept distinct from planned targets, including while starting or paused. */
internal fun programOverlayPresentation(run: ProgramRunState, data: ExerciseData?, imperial: Boolean): ProgramOverlayPresentation {
    val ramping = run.block.ramp && run.blockIndex > 0
    val ending = run.blockIndex == run.program.blocks.lastIndex
    val running = run.status == ProgramStatus.RUNNING
    val stopping = running && ending && !run.program.loop && (!ramping || run.countdown != null)
    val target = when {
        !running -> run.target
        stopping -> ProgramTarget(0f, run.target.inclinePercent)
        ramping && run.countdown == null -> ProgramTarget(run.block.speedKph, run.block.inclinePercent)
        else -> run.nextTarget ?: run.target
    }
    val blockRemaining = ((run.program.blocks.take(run.blockIndex + 1).sumOf { it.durationSeconds } * 1000L - run.elapsedMillis + 999) / 1000).toInt().coerceAtLeast(0)
    val seconds = if (ramping && run.countdown == null) blockRemaining else run.countdown ?: run.nextChangeSeconds
    val (value, caption) = when (run.status) {
        ProgramStatus.READY -> "Ready" to "Press Start on console"
        ProgramStatus.COUNTDOWN -> (run.countdown ?: 3).toString() to "seconds to change"
        ProgramStatus.PAUSED -> "Paused" to "Targets on resume"
        ProgramStatus.MANUAL -> "Manual" to when {
            run.message.contains("sound", ignoreCase = true) -> "Sound unavailable · open app"
            run.message.contains("Connection", ignoreCase = true) -> "Connection interrupted · open app"
            else -> "Resume program in app"
        }
        ProgramStatus.COMPLETE -> "Done" to "Session complete"
        ProgramStatus.CANCELLED -> "Ended" to "Program ended"
        ProgramStatus.ERROR -> "Stopped" to "Check the app"
        ProgramStatus.RUNNING -> when {
            stopping -> (seconds ?: blockRemaining).toString() to "seconds to stop"
            ramping && run.countdown == null -> blockRemaining.toString() to "seconds to target"
            ending && run.program.loop -> (seconds ?: blockRemaining).toString() to "seconds to repeat"
            seconds != null -> seconds.toString() to "seconds to change"
            else -> "Hold" to "Current targets"
        }
    }
    fun number(value: Float?) = value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
    fun speed(value: Float?) = number(value?.times(if (imperial) .621371f else 1f))
    return ProgramOverlayPresentation(value, caption, speed(data?.speed), speed(target.speedKph), if (imperial) "mph" else "km/h", number(data?.incline), number(target.inclinePercent), (running || run.status == ProgramStatus.COUNTDOWN) && run.countdown != null)
}
