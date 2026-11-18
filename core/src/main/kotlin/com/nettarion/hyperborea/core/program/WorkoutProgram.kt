package com.nettarion.hyperborea.core.program

import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.DeviceType
import java.util.UUID
import kotlin.math.ceil

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
    /** Ease the belt down to a walk before a single-pass program stops it; see [windDownBlock]. */
    val easeToStop: Boolean = true,
) {
    val totalSeconds: Int get() = blocks.sumOf { it.durationSeconds }

    /**
     * The taper a single-pass program runs after its last block, so the belt is at walking pace
     * when the workout stops instead of braking from running speed: a ramp from the last block's
     * speed down to [WIND_DOWN_KPH] at [WIND_DOWN_KPH_PER_SECOND], incline unchanged. Null when
     * there is nothing to ease — the option is off, the program loops (it never stops on its
     * own), or the last block is already within [WIND_DOWN_MARGIN_KPH] of walking pace.
     */
    fun windDownBlock(): ProgramBlock? {
        if (loop || !easeToStop) return null
        val last = blocks.lastOrNull() ?: return null
        if (last.id == WIND_DOWN_BLOCK_ID || last.speedKph <= WIND_DOWN_KPH + WIND_DOWN_MARGIN_KPH) return null
        val seconds = ceil((last.speedKph - WIND_DOWN_KPH) / WIND_DOWN_KPH_PER_SECOND / 5f).toInt() * 5
        return ProgramBlock(
            id = WIND_DOWN_BLOCK_ID,
            name = "Ease to a stop",
            durationSeconds = seconds.coerceIn(10, 120),
            speedKph = WIND_DOWN_KPH,
            inclinePercent = last.inclinePercent,
            ramp = true,
        )
    }

    /** What actually runs: this program plus its [windDownBlock], if it has one. */
    fun runPlan(): WorkoutProgram = windDownBlock()?.let { copy(blocks = blocks + it) } ?: this

    /**
     * Every block moved by the same speed and incline amount — the whole workout a notch harder
     * or easier with its shape intact. Null when a block would be pushed past the limit it is
     * moving towards: clamping some blocks but not others would flatten the profile, and shifting
     * back would no longer restore it.
     */
    fun shiftedBy(
        speedKph: Float = 0f,
        inclinePercent: Float = 0f,
        speedRange: ClosedFloatingPointRange<Float> = MIN_SPEED_KPH..MAX_SPEED_KPH,
        inclineRange: ClosedFloatingPointRange<Float> = MIN_INCLINE_PERCENT..MAX_INCLINE_PERCENT,
    ): WorkoutProgram? {
        fun shift(value: Float, delta: Float, range: ClosedFloatingPointRange<Float>): Float? {
            val moved = value + delta
            // Only the limit being approached can refuse the move, so a program built for a
            // faster or steeper treadmill can still be brought down into this one's range.
            return when {
                delta > 0f && moved > range.endInclusive + SHIFT_TOLERANCE -> null
                delta < 0f && moved < range.start - SHIFT_TOLERANCE -> null
                delta > 0f -> minOf(moved, maxOf(value, range.endInclusive))
                delta < 0f -> maxOf(moved, minOf(value, range.start))
                else -> value
            }
        }
        return copy(blocks = blocks.map { block ->
            block.copy(
                speedKph = shift(block.speedKph, speedKph, speedRange) ?: return null,
                inclinePercent = shift(block.inclinePercent, inclinePercent, inclineRange) ?: return null,
            )
        })
    }

    fun validationError(device: DeviceInfo? = null): String? = when {
        name.isBlank() || name.length > 60 -> "Give the program a name (up to 60 characters)."
        blocks.isEmpty() || blocks.size > 100 -> "A program needs between 1 and 100 blocks."
        blocks.map { it.id }.distinct().size != blocks.size -> "Each block must have its own identity."
        blocks.any { it.name.isBlank() || it.name.length > 40 } -> "Give each block a name (up to 40 characters)."
        blocks.any { it.durationSeconds !in 10..3600 } -> "Each block must last 10 seconds to 60 minutes."
        blocks.any { !it.speedKph.isFinite() || it.speedKph !in MIN_SPEED_KPH..MAX_SPEED_KPH || !it.inclinePercent.isFinite() || it.inclinePercent !in MIN_INCLINE_PERCENT..MAX_INCLINE_PERCENT } -> "Speed or incline is outside the supported range."
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

    companion object {
        const val MIN_SPEED_KPH = .5f
        const val MAX_SPEED_KPH = 30f
        const val MIN_INCLINE_PERCENT = -10f
        const val MAX_INCLINE_PERCENT = 40f

        /** Identifies the automatic taper block in a [runPlan]; never part of a saved program. */
        const val WIND_DOWN_BLOCK_ID = "wind-down"

        /** Walking pace the taper ends at (2.0 mph). */
        const val WIND_DOWN_KPH = 3.2f

        /** Endings no faster than this above [WIND_DOWN_KPH] already stop gently. */
        const val WIND_DOWN_MARGIN_KPH = 1f

        /** Taper rate: a 10 km/h finish takes about 35 s to reach walking pace. */
        const val WIND_DOWN_KPH_PER_SECOND = .2f

        /** Absorbs float error from repeated unit-converted steps (0.1 mph is 0.1609… km/h). */
        private const val SHIFT_TOLERANCE = 1e-3f
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
    /** What the engine is running: the chosen program's [WorkoutProgram.runPlan]. */
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
    val manualSpeed: Boolean = false,
    val manualIncline: Boolean = false,
    /** The program as the user built it, without the automatic taper — what saving must store. */
    val source: WorkoutProgram = program,
) {
    /** True while the automatic end-of-program taper is running. */
    val easingToStop: Boolean get() = block.id == WorkoutProgram.WIND_DOWN_BLOCK_ID
    val active: Boolean get() = status !in setOf(ProgramStatus.COMPLETE, ProgramStatus.CANCELLED, ProgramStatus.ERROR)
    val remainingSeconds: Int get() = (program.totalSeconds - elapsedMillis / 1000).toInt().coerceAtLeast(0)
    val block: ProgramBlock get() = program.blocks[blockIndex]
}
