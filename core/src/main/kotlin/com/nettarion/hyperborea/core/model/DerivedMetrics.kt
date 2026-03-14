package com.nettarion.hyperborea.core.model

data class DerivedMetrics(
    val workKj: Float?,
    val variabilityIndex: Float?,
    val efficiencyFactor: Float?,
    val avgPowerPerKg: Float?,
    val maxPowerPerKg: Float?,
    val caloriesPerHour: Float?,
    val powerZones: ZoneDistribution?,
    val hrZones: ZoneDistribution?,
)

data class ZoneDistribution(
    val referenceValue: Int,
    val referenceLabel: String,
    val zones: List<ZoneBucket>,
)

data class ZoneBucket(
    val name: String,
    val seconds: Int,
    val percentage: Float,
)
