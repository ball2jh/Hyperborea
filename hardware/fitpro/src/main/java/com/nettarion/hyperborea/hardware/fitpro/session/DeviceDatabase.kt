package com.nettarion.hyperborea.hardware.fitpro.session

import com.nettarion.hyperborea.core.DeviceInfo
import com.nettarion.hyperborea.core.DeviceType
import com.nettarion.hyperborea.core.Metric

object DeviceDatabase {

    private data class DeviceRecord(
        val name: String,
        val type: DeviceType,
        val supportedMetrics: Set<Metric>,
    )

    private val STANDARD_BIKE_METRICS = setOf(
        Metric.POWER, Metric.CADENCE, Metric.SPEED,
        Metric.RESISTANCE, Metric.INCLINE,
        Metric.DISTANCE, Metric.CALORIES,
    )

    // Known model numbers from V1 handshake SystemInfoResponse.
    // Add entries as devices are encountered and verified.
    private val knownModels: Map<Int, DeviceRecord> = mapOf(
        2117 to DeviceRecord("NordicTrack S22i", DeviceType.BIKE, STANDARD_BIKE_METRICS),
    )

    fun fromModel(modelNumber: Int): DeviceInfo {
        val record = knownModels[modelNumber] ?: FALLBACK
        return DeviceInfo(record.name, record.type, record.supportedMetrics)
    }

    fun fallback(): DeviceInfo =
        DeviceInfo(FALLBACK.name, FALLBACK.type, FALLBACK.supportedMetrics)

    private val FALLBACK = DeviceRecord(
        name = "FitPro Device",
        type = DeviceType.BIKE,
        supportedMetrics = STANDARD_BIKE_METRICS,
    )
}
