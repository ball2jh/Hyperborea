package com.nettarion.hyperborea.core.adapter

sealed interface SensorReading {
    data class HeartRate(val bpm: Int) : SensorReading
}
