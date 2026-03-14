package com.nettarion.hyperborea.data

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.model.Metric
import com.nettarion.hyperborea.core.profile.DeviceConfigRepository

class RoomDeviceConfigRepository(
    private val dao: DeviceConfigDao,
    private val logger: AppLogger,
) : DeviceConfigRepository {

    override suspend fun getConfig(modelNumber: Int): DeviceInfo? {
        val entity = dao.getConfig(modelNumber) ?: return null
        return entity.toDomain()
    }

    override suspend fun saveConfig(modelNumber: Int, config: DeviceInfo) {
        dao.upsert(config.toEntity(modelNumber))
        logger.i(TAG, "Saved device config for model $modelNumber: ${config.name}")
    }

    override suspend fun deleteConfig(modelNumber: Int) {
        dao.delete(modelNumber)
        logger.i(TAG, "Deleted device config for model $modelNumber")
    }

    override suspend fun hasConfig(modelNumber: Int): Boolean = dao.exists(modelNumber)

    private companion object {
        const val TAG = "DeviceConfigRepository"
    }
}

private fun DeviceConfigEntity.toDomain() = DeviceInfo(
    name = name,
    type = try { DeviceType.valueOf(type) } catch (_: IllegalArgumentException) { DeviceType.BIKE },
    supportedMetrics = supportedMetrics.split(",")
        .filter { it.isNotBlank() }
        .mapNotNull { name ->
            try { Metric.valueOf(name) } catch (_: IllegalArgumentException) { null }
        }
        .toSet(),
    maxResistance = maxResistance,
    minResistance = minResistance,
    minIncline = minIncline,
    maxIncline = maxIncline,
    maxPower = maxPower,
    minPower = minPower,
    powerStep = powerStep,
    resistanceStep = resistanceStep,
    inclineStep = inclineStep,
    speedStep = speedStep,
    maxSpeed = maxSpeed,
)

private fun DeviceInfo.toEntity(modelNumber: Int) = DeviceConfigEntity(
    modelNumber = modelNumber,
    name = name,
    type = type.name,
    supportedMetrics = supportedMetrics.joinToString(",") { it.name },
    maxResistance = maxResistance,
    minResistance = minResistance,
    minIncline = minIncline,
    maxIncline = maxIncline,
    maxPower = maxPower,
    minPower = minPower,
    powerStep = powerStep,
    resistanceStep = resistanceStep,
    inclineStep = inclineStep,
    speedStep = speedStep,
    maxSpeed = maxSpeed,
)
