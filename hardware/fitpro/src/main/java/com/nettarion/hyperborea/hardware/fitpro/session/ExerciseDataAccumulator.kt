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
    private var elapsedTime: Long = 0L
    private var startTime: Long = 0L
    private var targetSpeed: Float? = null
    private var targetIncline: Float? = null
    private var targetPower: Int? = null
    private var targetResistance: Int? = null
    private var workoutMode: Int? = null
    private var lifetimeRunningTime: Long? = null
    private var lifetimeDistance: Float? = null
    private var lifetimeCalories: Int? = null

    fun start() {
        startTime = clock()
    }

    fun updatePower(value: Int) { power = value }
    fun updateCadence(value: Int) { cadence = value }
    fun updateSpeed(value: Float) { speed = value }
    fun updateResistance(value: Int) { resistance = value }
    fun updateIncline(value: Float) { incline = value }
    fun updateHeartRate(value: Int) { heartRate = value }
    fun updateDistance(value: Float) { distance = value }
    fun updateCalories(value: Int) { calories = value }
    fun updateElapsedTime(seconds: Long) { elapsedTime = seconds }
    fun updateTargetSpeed(value: Float) { targetSpeed = value }
    fun updateTargetIncline(value: Float) { targetIncline = value }
    fun updateTargetPower(value: Int) { targetPower = value }
    fun updateTargetResistance(value: Int) { targetResistance = value }
    fun updateWorkoutMode(value: Int) { workoutMode = value }
    fun updateLifetimeRunningTime(seconds: Long) { lifetimeRunningTime = seconds }
    fun updateLifetimeDistance(value: Float) { lifetimeDistance = value }
    fun updateLifetimeCalories(value: Int) { lifetimeCalories = value }

    fun snapshot(): ExerciseData = ExerciseData(
        power = power,
        cadence = cadence,
        speed = speed,
        resistance = resistance,
        incline = incline,
        heartRate = heartRate,
        distance = distance,
        calories = calories,
        elapsedTime = if (elapsedTime > 0) elapsedTime else (clock() - startTime) / 1000L,
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
        elapsedTime = 0L
        startTime = 0L
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
