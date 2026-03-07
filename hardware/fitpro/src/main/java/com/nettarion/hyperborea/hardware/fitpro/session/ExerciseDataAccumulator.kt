package com.nettarion.hyperborea.hardware.fitpro.session

import com.nettarion.hyperborea.core.ExerciseData

class ExerciseDataAccumulator(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var power: Int? = null
    private var cadence: Int? = null
    private var speed: Float? = null
    private var resistance: Int? = null
    private var incline: Float? = null
    private var heartRate: Int? = null
    private var distance: Float? = null
    private var calories: Int? = null
    private var targetSpeed: Float? = null
    private var targetIncline: Float? = null
    private var targetPower: Int? = null
    private var targetResistance: Int? = null
    private var workoutMode: Int? = null
    private var lifetimeRunningTime: Long? = null
    private var lifetimeDistance: Float? = null
    private var lifetimeCalories: Int? = null

    // Elapsed time tracking — own clock, pausable
    private var accumulatedSeconds: Long = 0L
    private var runningStartTime: Long = 0L
    private var paused: Boolean = false

    fun start() {
        // Clock starts lazily on first non-zero cadence
    }

    fun pause() {
        if (!paused && runningStartTime > 0L) {
            accumulatedSeconds += (clock() - runningStartTime) / 1000L
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
    fun updateHeartRate(value: Int) { heartRate = value }
    fun updateDistance(value: Float) { distance = value }
    fun updateCalories(value: Int) { calories = value }
    fun updateElapsedTime(seconds: Long) { /* ignored — we track our own clock */ }
    fun updateTargetSpeed(value: Float) { targetSpeed = value }
    fun updateTargetIncline(value: Float) { targetIncline = value }
    fun updateTargetPower(value: Int) { targetPower = value }
    fun updateTargetResistance(value: Int) { targetResistance = value }
    fun updateWorkoutMode(value: Int) { workoutMode = value }
    fun updateLifetimeRunningTime(seconds: Long) { lifetimeRunningTime = seconds }
    fun updateLifetimeDistance(value: Float) { lifetimeDistance = value }
    fun updateLifetimeCalories(value: Int) { lifetimeCalories = value }

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
        distance = distance,
        calories = calories,
        elapsedTime = elapsedSeconds(),
        targetSpeed = targetSpeed,
        targetIncline = targetIncline,
        targetPower = targetPower,
        targetResistance = targetResistance,
        workoutMode = workoutMode,
        lifetimeRunningTime = lifetimeRunningTime,
        lifetimeDistance = lifetimeDistance,
        lifetimeCalories = lifetimeCalories,
    )

    fun reset() {
        power = null
        cadence = null
        speed = null
        resistance = null
        incline = null
        heartRate = null
        distance = null
        calories = null
        accumulatedSeconds = 0L
        runningStartTime = 0L
        paused = false
        targetSpeed = null
        targetIncline = null
        targetPower = null
        targetResistance = null
        workoutMode = null
        lifetimeRunningTime = null
        lifetimeDistance = null
        lifetimeCalories = null
    }
}
