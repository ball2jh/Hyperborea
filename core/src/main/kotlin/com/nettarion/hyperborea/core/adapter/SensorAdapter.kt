package com.nettarion.hyperborea.core.adapter

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface SensorAdapter : Adapter {
    val id: SensorId
    val reading: StateFlow<SensorReading?>
    suspend fun startScan(): Flow<DiscoveredSensor>
    suspend fun connect(address: String)
    suspend fun disconnect()
}
