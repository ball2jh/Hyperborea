package com.nettarion.hyperborea.hardware.fitpro.session

import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.model.Metric

object DeviceDatabase {

    private data class DeviceRecord(
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

    private val STANDARD_BIKE_METRICS = setOf(
        Metric.POWER, Metric.CADENCE, Metric.SPEED,
        Metric.RESISTANCE, Metric.INCLINE,
        Metric.DISTANCE, Metric.CALORIES,
    )

    // Known model numbers from V1 handshake SystemInfoResponse.
    // Add entries as devices are encountered and verified.
    private val knownModels: Map<Int, DeviceRecord> = mapOf(
        2117 to DeviceRecord(
            name = "NordicTrack S22i",
            type = DeviceType.BIKE,
            supportedMetrics = STANDARD_BIKE_METRICS,
            maxResistance = 24,
            minResistance = 1,
            minIncline = -6f,
            maxIncline = 40f,
            maxPower = 2000,
            inclineStep = 0.5f,
            speedStep = 0.5f,
            maxSpeed = 60f,
        ),
    )

    fun fromModel(modelNumber: Int): DeviceInfo {
        val record = knownModels[modelNumber] ?: FALLBACK
        return record.toDeviceInfo()
    }

    fun fallback(): DeviceInfo = FALLBACK.toDeviceInfo()

    private val FALLBACK = DeviceRecord(
        name = "FitPro Device",
        type = DeviceType.BIKE,
        supportedMetrics = STANDARD_BIKE_METRICS,
        maxResistance = 24,
        minResistance = 1,
        minIncline = -6f,
        maxIncline = 40f,
        maxPower = 2000,
        inclineStep = 0.5f,
        speedStep = 0.5f,
        maxSpeed = 60f,
    )

    private val productIdToModel: Map<Int, Int> = mapOf(
        2 to 2117, 3 to 2117, 4 to 2117,
    )

    fun fromProductId(productId: Int): DeviceInfo? {
        val modelNumber = productIdToModel[productId] ?: return null
        return fromModel(modelNumber)
    }

    private fun DeviceRecord.toDeviceInfo() = DeviceInfo(
        name = name,
        type = type,
        supportedMetrics = supportedMetrics,
        maxResistance = maxResistance,
        minResistance = minResistance,
        minIncline = minIncline,
        maxIncline = maxIncline,
        maxPower = maxPower,
        inclineStep = inclineStep,
        speedStep = speedStep,
        maxSpeed = maxSpeed,
    )
}
