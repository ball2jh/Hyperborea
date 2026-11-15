package com.nettarion.hyperborea.core.program

import kotlin.math.ceil

sealed interface ProgramEvent {
    /** 3/2/1 are countdown tones; 0 is the distinct target-change tone. */
    data class Cue(val count: Int) : ProgramEvent
    data class Target(val target: ProgramTarget, val controlSpeed: Boolean = true, val controlIncline: Boolean = true) : ProgramEvent
    data object Finished : ProgramEvent
}

/** Deterministic session clock. Only its owner may advance it, after verifying hardware is ready. */
class ProgramEngine(program: WorkoutProgram) {
    init { require(program.validationError() == null) }
    var state = ProgramRunState(program)
        private set
    private var leadInMillis = 3000L
    private var boundaryPaddingMillis = 0L
    private var lastCue: Pair<Int, Int>? = null
    private var lastTarget: ProgramEvent.Target? = null

    companion object {
        // A larger step can jump across two countdown buckets and omit a warning beep.
        const val MAX_TICK_MILLIS = 1000L
    }

    fun begin(): List<ProgramEvent> {
        if (!state.active || state.status == ProgramStatus.RUNNING || state.status == ProgramStatus.COUNTDOWN) return emptyList()
        leadInMillis = 3000
        val current = state.program.blocks[state.blockIndex]
        val next = state.program.blocks.getOrNull(state.blockIndex + 1) ?: state.program.blocks.first().takeIf { state.program.loop }
        val remaining = state.program.blocks.take(state.blockIndex + 1).sumOf { it.durationSeconds } * 1000L - state.elapsedMillis
        // Resuming in the last seconds of a block must still give the next change all three cues.
        boundaryPaddingMillis = if (state.elapsedMillis > 0 && (next == null ||
            next.speedKph != current.speedKph || next.inclinePercent != current.inclinePercent || state.manualSpeed || state.manualIncline)) (3000L - remaining).coerceAtLeast(0) else 0
        lastCue = null
        lastTarget = null
        state = state.copy(status = ProgramStatus.COUNTDOWN, countdown = 3, nextTarget = state.target, nextChangeSeconds = 3, message = "Targets change in 3")
        return listOf(ProgramEvent.Cue(3))
    }

    /** A user adjustment owns just that axis until the next block, without freezing the clock. */
    fun overrideForBlock(speed: Boolean = false, incline: Boolean = false) {
        if (!state.active) return
        state = state.copy(manualSpeed = state.manualSpeed || speed, manualIncline = state.manualIncline || incline)
        val remaining = state.program.blocks.take(state.blockIndex + 1).sumOf { it.durationSeconds } * 1000L - state.elapsedMillis
        // A late adjustment restarts the warning, delaying the boundary rather than reclaiming
        // the user's target with only one or two beeps. Subsequent held-key edits restart it again.
        if (remaining + boundaryPaddingMillis < 3000) {
            boundaryPaddingMillis = (3000 - remaining).coerceAtLeast(0)
            lastCue = null
            state = state.copy(countdown = null)
        }
    }

    fun hold(manual: Boolean = false, message: String = "Program paused") {
        if (!state.active) return
        state = state.copy(status = if (manual) ProgramStatus.MANUAL else ProgramStatus.PAUSED, countdown = null, message = message)
    }

    fun end(status: ProgramStatus, message: String) {
        state = state.copy(status = status, countdown = null, message = message)
    }

    fun advance(deltaMillis: Long): List<ProgramEvent> {
        require(deltaMillis in 0..MAX_TICK_MILLIS) { "Late ticks must pause automation, never skip target-change cues" }
        if (state.status != ProgramStatus.RUNNING && state.status != ProgramStatus.COUNTDOWN) return emptyList()
        val events = mutableListOf<ProgramEvent>()
        var finishedLeadIn = false
        if (state.status == ProgramStatus.COUNTDOWN) {
            leadInMillis = (leadInMillis - deltaMillis).coerceAtLeast(0)
            val count = ceil(leadInMillis / 1000.0).toInt()
            if (count > 0) {
                if (count != state.countdown) events += ProgramEvent.Cue(count)
                state = state.copy(countdown = count, nextChangeSeconds = count, message = "Targets change in $count")
                return events
            }
            state = state.copy(status = ProgramStatus.RUNNING, countdown = null)
            events += ProgramEvent.Cue(0)
            finishedLeadIn = true
        } else {
            val padding = minOf(deltaMillis, boundaryPaddingMillis)
            boundaryPaddingMillis -= padding
            state = state.copy(elapsedMillis = state.elapsedMillis + deltaMillis - padding)
        }
        val program = state.program
        var wrapped = false
        if (state.elapsedMillis >= program.totalSeconds * 1000L) {
            if (program.loop) {
                state = state.copy(elapsedMillis = state.elapsedMillis % (program.totalSeconds * 1000L), completedLoops = state.completedLoops + 1, countdown = null)
                lastCue = null
                wrapped = true
            } else {
                state = state.copy(status = ProgramStatus.COMPLETE, elapsedMillis = program.totalSeconds * 1000L, countdown = null, message = "Session complete")
                events += ProgramEvent.Cue(0)
                events += ProgramEvent.Finished
                return events
            }
        }
        val previousIndex = state.blockIndex
        var start = 0L
        val index = program.blocks.indexOfFirst { block ->
            if (state.elapsedMillis < start + block.durationSeconds * 1000L) true
            else { start += block.durationSeconds * 1000L; false }
        }
        val reclaiming = (wrapped || index != previousIndex) && (state.manualSpeed || state.manualIncline)
        if (wrapped || index != previousIndex) state = state.copy(manualSpeed = false, manualIncline = false)
        val block = program.blocks[index]
        val previous = program.blocks.getOrNull(index - 1)
        val fraction = ((state.elapsedMillis - start) / 1000L).toFloat() / block.durationSeconds
        val inclineFraction = (state.elapsedMillis - start).toFloat() / (block.durationSeconds * 1000L)
        val target = if (block.ramp && previous != null) ProgramTarget(
            previous.speedKph + (block.speedKph - previous.speedKph) * fraction,
            previous.inclinePercent + (block.inclinePercent - previous.inclinePercent) * inclineFraction,
        ) else ProgramTarget(block.speedKph, block.inclinePercent)
        val loopingBoundary = program.loop && index == program.blocks.lastIndex
        val nextBlock = program.blocks.getOrNull(index + 1) ?: program.blocks.first().takeIf { program.loop }
        val next = nextBlock?.takeIf { it.speedKph != block.speedKph || it.inclinePercent != block.inclinePercent || state.manualSpeed || state.manualIncline }
        val ending = !program.loop && index == program.blocks.lastIndex
        val seconds = if (next != null || ending || loopingBoundary) ceil((start + block.durationSeconds * 1000L - state.elapsedMillis + boundaryPaddingMillis) / 1000.0).toInt() else null
        val count = seconds?.takeIf { (next != null || ending) && !finishedLeadIn && it in 1..3 }
        val transitionFrom = if (wrapped) program.blocks.last() else previous
        if ((wrapped || index != previousIndex) && transitionFrom != null && (transitionFrom.speedKph != block.speedKph || transitionFrom.inclinePercent != block.inclinePercent || reclaiming)) events += ProgramEvent.Cue(0)
        if (count != null && lastCue != (index to count)) {
            events += ProgramEvent.Cue(count)
            lastCue = index to count
        }
        val command = ProgramEvent.Target(target, !state.manualSpeed, !state.manualIncline)
        if (command != lastTarget) {
            if (command.controlSpeed || command.controlIncline) events += command
            lastTarget = command
        }
        state = state.copy(blockIndex = index, target = target, nextTarget = next?.let { ProgramTarget(it.speedKph, it.inclinePercent) }, nextChangeSeconds = seconds, countdown = count, message = if (ending && count != null) "Belt stops in $count" else block.name)
        return events
    }
}
