package com.nettarion.hyperborea.hardware.fitpro.session

import com.nettarion.hyperborea.core.model.ExerciseData
import kotlin.math.roundToInt

class ExerciseDataAccumulator(
    private val clock: () -> Long = System::currentTimeMillis,
    initialElapsedSeconds: Long = 0L,
) {
    private var power: Int? = null
    private var cadence: Int? = null
    private var speed: Float? = null
    private var resistance: Int? = null
    private var incline: Float? = null
    private var heartRate: Int? = null
    private val distance = WorkoutCounter()
    private val calories = WorkoutCounter()
    private var targetSpeed: Float? = null
    // The only field written off the receive/poll loop: the session publishes a commanded incline
    // target straight from the command coroutine (see BaseFitProSession.handleInclineCommand), so
    // the loop's next snapshot() must see it.
    @Volatile private var targetIncline: Float? = null
    private var targetPower: Int? = null
    private var targetResistance: Int? = null
    private var workoutMode: Int? = null
    private val verticalGain = WorkoutCounter()
    private var strokeCount: Int? = null
    private var strokeRate: Int? = null
    @Volatile private var preserveCounterContinuity = false

    /**
     * Hold distance, calories and vertical gain while the ride clock is paused. Belt machines
     * only (the session sets it once it knows the equipment type): a paused belt is stopped, so
     * anything the console counts meanwhile is phantom. A paused bike's flywheel can still be
     * turning under the rider, so its counters keep following the console.
     */
    @Volatile var holdCountersWhilePaused = false

    /** A paused belt controller may reset its counters when its own pause timer expires. */
    fun preserveCountersAcrossReset() { preserveCounterContinuity = true }

    // Elapsed time tracking — own clock, pausable
    private var accumulatedSeconds: Long = initialElapsedSeconds
    private var runningStartTime: Long = 0L
    // Read by the counter updates on the receive/poll loop, written from the command path too.
    @Volatile private var paused: Boolean = false

    private val countersHeld: Boolean get() = paused && holdCountersWhilePaused

    fun start() {
        // Clock starts lazily on first non-zero cadence
    }

    fun startTimer() {
        if (runningStartTime == 0L && !paused) runningStartTime = clock()
    }

    fun pause() {
        if (!paused) {
            if (runningStartTime > 0L) accumulatedSeconds += (clock() - runningStartTime) / 1000L
            runningStartTime = 0L
            paused = true
        }
    }

    fun resume() {
        if (paused) {
            runningStartTime = clock()
            paused = false
        }
    }

    fun updatePower(value: Int) { power = value }
    fun updateCadence(value: Int) {
        cadence = value
        if (value > 0 && runningStartTime == 0L && !paused) runningStartTime = clock()
    }
    fun updateSpeed(value: Float) { speed = value }
    fun updateResistance(value: Int) { resistance = value }
    fun updateIncline(value: Float) { incline = value }
    /** A `null` value clears the reading — used when a grip sensor loses contact. */
    fun updateHeartRate(value: Int?) { heartRate = value }
    fun updateDistance(value: Float) = distance.update(value, frozen = countersHeld, preserve = preserveCounterContinuity)
    fun updateCalories(value: Int) = calories.update(value.toFloat(), frozen = countersHeld, preserve = preserveCounterContinuity)
    fun updateElapsedTime(seconds: Long) { /* ignored — we track our own clock */ }
    fun updateTargetSpeed(value: Float) { targetSpeed = value }
    fun updateTargetIncline(value: Float) { targetIncline = value }
    fun updateTargetPower(value: Int) { targetPower = value }
    fun updateTargetResistance(value: Int) { targetResistance = value }
    fun updateWorkoutMode(value: Int) { workoutMode = value }
    fun updateVerticalGain(meters: Float) = verticalGain.update(meters, frozen = countersHeld, preserve = preserveCounterContinuity)
    fun updateStrokeCount(value: Int) { strokeCount = value }
    fun updateStrokeRate(value: Int) { strokeRate = value }

    private fun elapsedSeconds(): Long {
        val running = if (runningStartTime > 0L) (clock() - runningStartTime) / 1000L else 0L
        return accumulatedSeconds + running
    }

    fun snapshot(): ExerciseData = ExerciseData(
        power = power,
        cadence = cadence,
        speed = speed,
        resistance = resistance,
        incline = incline,
        heartRate = heartRate,
        distance = distance.published,
        calories = calories.published?.roundToInt(),
        elapsedTime = elapsedSeconds(),
        targetSpeed = targetSpeed,
        targetIncline = targetIncline,
        targetPower = targetPower,
        targetResistance = targetResistance,
        workoutMode = workoutMode,
        verticalGain = verticalGain.published,
        strokeCount = strokeCount,
        strokeRate = strokeRate,
    )

    fun reset() {
        power = null
        cadence = null
        speed = null
        resistance = null
        incline = null
        heartRate = null
        distance.reset()
        calories.reset()
        accumulatedSeconds = 0L
        runningStartTime = 0L
        paused = false
        targetSpeed = null
        targetIncline = null
        targetPower = null
        targetResistance = null
        workoutMode = null
        verticalGain.reset()
        strokeCount = null
        strokeRate = null
        preserveCounterContinuity = false
    }
}

/**
 * One console-owned cumulative counter (distance, calories, vertical gain), republished so it
 * follows the ride clock instead of the console's own bookkeeping:
 *
 *  - **Frozen while paused.** Field treadmills keep integrating the *commanded* speed while the
 *    belt is paused — distance and calories climb at the pre-pause pace for as long as the pause
 *    lasts. Whatever the console adds while the ride clock is stopped is absorbed into [offset],
 *    so the published value holds and picks up from the same number on resume.
 *  - **Continuous across a console reset** when [update] is told to preserve (a paused belt
 *    controller zeroes its counters when its own pause timer expires). Without that policy a
 *    reset is followed as-is: it is the console starting a new workout.
 */
private class WorkoutCounter {
    var published: Float? = null
        private set
    private var raw = 0f
    private var offset = 0f

    fun update(value: Float, frozen: Boolean, preserve: Boolean) {
        val previous = published
        val consoleReset = value < raw - RESET_EPSILON
        raw = value
        when {
            // First reading, or a reset nobody asked us to bridge: follow the console.
            previous == null || (consoleReset && !preserve) -> {
                offset = 0f
                published = value
            }
            frozen -> {
                offset = previous - value
                published = previous
            }
            else -> {
                if (consoleReset) offset = previous
                // A preserved counter never steps backwards on sub-epsilon console jitter.
                published = if (preserve) maxOf(previous, offset + value) else offset + value
            }
        }
    }

    fun reset() {
        published = null
        raw = 0f
        offset = 0f
    }

    private companion object {
        const val RESET_EPSILON = .001f
    }
}
