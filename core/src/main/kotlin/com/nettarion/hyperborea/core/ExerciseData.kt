package com.nettarion.hyperborea.core

data class ExerciseData(
    val power: Int?,
    val cadence: Int?,
    val speed: Float?,
    val resistance: Int?,
    val incline: Float?,
    val heartRate: Int?,
    val distance: Float?,
    val calories: Int?,
    val elapsedTime: Long,
    val targetSpeed: Float? = null,
    val targetIncline: Float? = null,
    val targetPower: Int? = null,
    val targetResistance: Int? = null,
    val workoutMode: Int? = null,
    val lifetimeRunningTime: Long? = null,
    val lifetimeDistance: Float? = null,
    val lifetimeCalories: Int? = null,
)
