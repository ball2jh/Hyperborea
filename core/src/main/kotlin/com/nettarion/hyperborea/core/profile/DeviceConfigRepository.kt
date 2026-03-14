package com.nettarion.hyperborea.core.profile

import com.nettarion.hyperborea.core.model.DeviceInfo

interface DeviceConfigRepository {
    suspend fun getConfig(modelNumber: Int): DeviceInfo?
    suspend fun saveConfig(modelNumber: Int, config: DeviceInfo)
    suspend fun deleteConfig(modelNumber: Int)
    suspend fun hasConfig(modelNumber: Int): Boolean
}
