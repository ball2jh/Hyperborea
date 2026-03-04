package com.nettarion.hyperborea.core

data class DeviceInfo(
    val name: String,
    val type: DeviceType,
    val supportedMetrics: Set<Metric>,
)

enum class DeviceType { BIKE, TREADMILL, ROWER, ELLIPTICAL }

enum class Metric { POWER, CADENCE, SPEED, RESISTANCE, INCLINE, HEART_RATE, DISTANCE, CALORIES }
