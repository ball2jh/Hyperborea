package com.nettarion.hyperborea.core.model

data class WorkoutSample(
    val timestampSeconds: Long,
    val power: Int?,
    val cadence: Int?,
    val speedKph: Float?,
    val heartRate: Int?,
    val resistance: Int?,
    val incline: Float?,
    val calories: Int?,
    val distanceKm: Float?,
)
