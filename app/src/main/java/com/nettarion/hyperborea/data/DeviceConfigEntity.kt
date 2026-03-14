package com.nettarion.hyperborea.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "device_configs")
data class DeviceConfigEntity(
    @PrimaryKey val modelNumber: Int,
    val name: String,
    val type: String,
    val supportedMetrics: String,
    val maxResistance: Int,
    val minResistance: Int,
    val minIncline: Float,
    val maxIncline: Float,
    val maxPower: Int,
    val minPower: Int,
    val powerStep: Int,
    val resistanceStep: Float,
    val inclineStep: Float,
    val speedStep: Float,
    val maxSpeed: Float,
)
