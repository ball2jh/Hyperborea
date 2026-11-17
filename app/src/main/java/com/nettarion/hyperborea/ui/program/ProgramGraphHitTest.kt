package com.nettarion.hyperborea.ui.program

import com.nettarion.hyperborea.core.program.WorkoutProgram

/** A ramp handle belongs to its block even though its x coordinate lies on the next boundary. */
internal fun programGraphBlockAt(
    program: WorkoutProgram,
    seconds: Float,
    value: Float,
    speed: Boolean,
    pixelsPerSecond: Float,
    pixelsPerValue: Float,
    handleRadius: Float,
): Int {
    var start = 0f
    var nearest = 0
    var distance = Float.POSITIVE_INFINITY
    program.blocks.forEachIndexed { index, block ->
        val end = start + block.durationSeconds
        val markerTime = if (block.ramp && index > 0) end else (start + end) / 2
        val markerValue = if (speed) block.speedKph else block.inclinePercent
        val dx = (seconds - markerTime) * pixelsPerSecond
        val dy = (value - markerValue) * pixelsPerValue
        val squaredDistance = dx * dx + dy * dy
        if (squaredDistance < distance) { nearest = index; distance = squaredDistance }
        start = end
    }
    if (distance <= handleRadius * handleRadius) return nearest
    var end = 0f
    return program.blocks.indexOfFirst { block -> end += block.durationSeconds; seconds < end }
        .takeIf { it >= 0 } ?: program.blocks.lastIndex
}
