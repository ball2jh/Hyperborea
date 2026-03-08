package com.nettarion.hyperborea.core.model

data class DeviceInfo(
    val name: String,
    val type: DeviceType,
    val supportedMetrics: Set<Metric>,
    val maxResistance: Int,
    val minResistance: Int,
    val minIncline: Float,
    val maxIncline: Float,
    val maxPower: Int,
    val inclineStep: Float,
    val speedStep: Float,
    val maxSpeed: Float,
)

enum class DeviceType { BIKE, TREADMILL, ROWER, ELLIPTICAL }

enum class Metric { POWER, CADENCE, SPEED, RESISTANCE, INCLINE, HEART_RATE, DISTANCE, CALORIES }
