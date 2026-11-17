package com.nettarion.hyperborea.core.ftms

import com.nettarion.hyperborea.core.model.ExerciseData
import kotlin.math.roundToLong

// Not thread-safe — must be called from a single collector
class RevolutionCounter {
    var cumulativeWheelRevs: Long = 0
        private set
    var lastWheelEventTime: Int = 0
        private set
    var cumulativeCrankRevs: Long = 0
        private set
    var lastCrankEventTime: Int = 0
        private set

    private var lastTimestampMs: Long = -1
    private var wheelRevRemainder: Double = 0.0
    private var crankRevRemainder: Double = 0.0

    fun update(data: ExerciseData, nowMs: Long) {
        if (lastTimestampMs < 0) {
            lastTimestampMs = nowMs
            return
        }
        val deltaMs = nowMs - lastTimestampMs
        if (deltaMs <= 0) return

        val deltaSec = deltaMs / 1000.0

        // Wheel revolutions from speed using standard 700c×23mm circumference (2.096m).
        // This is a virtual encoding constant, not a physical measurement — the S22i has no
        // wheel. Zwift defaults to the same value, so speed round-trips faithfully:
        // bike speed → revolutions (here) → speed (Zwift).
        val speedMps = (data.speed ?: 0f) / 3.6f
        val wheelRevsInDelta = (speedMps * deltaSec) / 2.096
        wheelRevRemainder += wheelRevsInDelta
        val wholeWheelRevs = wheelRevRemainder.toLong()
        wheelRevRemainder -= wholeWheelRevs
        cumulativeWheelRevs += wholeWheelRevs
        // 1/2048s resolution, wraps at 0xFFFF
        if (wholeWheelRevs > 0) {
            lastWheelEventTime = eventTime(nowMs, deltaMs, wheelRevsInDelta, wheelRevRemainder, 2048)
        }

        // Crank revolutions from cadence
        val rpm = data.cadence ?: 0
        val crankRevsInDelta = (rpm * deltaSec) / 60.0
        crankRevRemainder += crankRevsInDelta
        val wholeCrankRevs = crankRevRemainder.toLong()
        crankRevRemainder -= wholeCrankRevs
        cumulativeCrankRevs += wholeCrankRevs
        // 1/1024s resolution, wraps at 0xFFFF
        if (wholeCrankRevs > 0) {
            lastCrankEventTime = eventTime(nowMs, deltaMs, crankRevsInDelta, crankRevRemainder, 1024)
        }

        lastTimestampMs = nowMs
    }

    /**
     * CSC/CPS "last event time" is when the most recent *whole* revolution completed — not the
     * sample time. It must stay put when no revolution completed (clients divide the revolution
     * delta by the event-time delta), and is interpolated back from [nowMs] by the fractional
     * revolution carried over, assuming a constant rate across this sample.
     */
    private fun eventTime(nowMs: Long, deltaMs: Long, revsInDelta: Double, remainder: Double, ticksPerSecond: Long): Int {
        val eventMs = nowMs - (remainder / revsInDelta) * deltaMs
        return ((eventMs * ticksPerSecond / 1000.0).roundToLong() % 0x10000).toInt()
    }

    fun reset() {
        cumulativeWheelRevs = 0
        lastWheelEventTime = 0
        cumulativeCrankRevs = 0
        lastCrankEventTime = 0
        lastTimestampMs = -1
        wheelRevRemainder = 0.0
        crankRevRemainder = 0.0
    }
}
