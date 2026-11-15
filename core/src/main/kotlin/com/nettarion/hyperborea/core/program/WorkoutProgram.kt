package com.nettarion.hyperborea.core.program

import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.DeviceType
import java.util.UUID

data class ProgramBlock(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Block",
    val durationSeconds: Int = 180,
    val speedKph: Float = 8f,
    val inclinePercent: Float = 0f,
    val ramp: Boolean = false,
)

data class WorkoutProgram(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "My custom run",
    val blocks: List<ProgramBlock> = listOf(
        ProgramBlock(name = "Warm-up", durationSeconds = 300, speedKph = 5f),
        ProgramBlock(name = "Main", durationSeconds = 600, speedKph = 8f, inclinePercent = 1f),
        ProgramBlock(name = "Cool-down", durationSeconds = 300, speedKph = 4f),
    ),
    val loop: Boolean = false,
) {
    val totalSeconds: Int get() = blocks.sumOf { it.durationSeconds }

    fun validationError(device: DeviceInfo? = null): String? = when {
        name.isBlank() || name.length > 60 -> "Give the program a name (up to 60 characters)."
        blocks.isEmpty() || blocks.size > 100 -> "A program needs between 1 and 100 blocks."
        blocks.map { it.id }.distinct().size != blocks.size -> "Each block must have its own identity."
        blocks.any { it.name.isBlank() || it.name.length > 40 } -> "Give each block a name (up to 40 characters)."
        blocks.any { it.durationSeconds !in 10..3600 } -> "Each block must last 10 seconds to 60 minutes."
        blocks.any { !it.speedKph.isFinite() || it.speedKph !in 0.5f..30f || !it.inclinePercent.isFinite() || it.inclinePercent !in -10f..40f } -> "Speed or incline is outside the supported range."
        device != null && device.type != DeviceType.TREADMILL -> "Programs require a treadmill."
        device != null && blocks.any { it.speedKph > device.maxSpeed || it.inclinePercent !in device.minIncline..device.maxIncline } -> "This program exceeds the connected treadmill's speed or incline limits. Edit it before starting."
        else -> null
    }

    fun moveBlock(from: Int, to: Int): WorkoutProgram {
        if (from !in blocks.indices || to !in blocks.indices || from == to) return this
        val reordered = blocks.toMutableList()
        reordered.add(to, reordered.removeAt(from))
        return copy(blocks = reordered)
    }
}

object ProgramTemplates {
    val all: List<WorkoutProgram> = listOf(
        WorkoutProgram(id = "builtin-steady", name = "Steady run"),
        WorkoutProgram(id = "builtin-intervals", name = "Intervals", blocks = buildList {
            add(ProgramBlock(name = "Warm-up", durationSeconds = 300, speedKph = 5f))
            repeat(3) {
                add(ProgramBlock(name = "Effort", durationSeconds = 180, speedKph = 10f, inclinePercent = 1f))
                add(ProgramBlock(name = "Recover", durationSeconds = 120, speedKph = 6f))
            }
            add(ProgramBlock(name = "Cool-down", durationSeconds = 300, speedKph = 4f))
        }),
        WorkoutProgram(id = "builtin-hills", name = "Rolling hills", blocks = listOf(
            ProgramBlock(name = "Warm-up", durationSeconds = 300, speedKph = 5f),
            ProgramBlock(name = "Climb", durationSeconds = 300, speedKph = 7f, inclinePercent = 5f, ramp = true),
            ProgramBlock(name = "Easy", durationSeconds = 300, speedKph = 7f, inclinePercent = 1f, ramp = true),
            ProgramBlock(name = "Climb", durationSeconds = 300, speedKph = 7f, inclinePercent = 7f, ramp = true),
            ProgramBlock(name = "Cool-down", durationSeconds = 300, speedKph = 4f, ramp = true),
        )),
    )
}

data class ProgramTarget(val speedKph: Float, val inclinePercent: Float)
enum class ProgramStatus { READY, COUNTDOWN, RUNNING, PAUSED, MANUAL, COMPLETE, CANCELLED, ERROR }

data class ProgramRunState(
    val program: WorkoutProgram,
    val status: ProgramStatus = ProgramStatus.READY,
    val elapsedMillis: Long = 0,
    val blockIndex: Int = 0,
    val target: ProgramTarget = ProgramTarget(program.blocks.first().speedKph, program.blocks.first().inclinePercent),
    val nextTarget: ProgramTarget? = null,
    val nextChangeSeconds: Int? = null,
    val countdown: Int? = null,
    val message: String = "Press the physical console START key",
    val completedLoops: Long = 0,
) {
    val active: Boolean get() = status !in setOf(ProgramStatus.COMPLETE, ProgramStatus.CANCELLED, ProgramStatus.ERROR)
    val remainingSeconds: Int get() = (program.totalSeconds - elapsedMillis / 1000).toInt().coerceAtLeast(0)
    val block: ProgramBlock get() = program.blocks[blockIndex]
}
