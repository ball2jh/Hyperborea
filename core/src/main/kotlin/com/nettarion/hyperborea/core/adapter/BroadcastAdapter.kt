package com.nettarion.hyperborea.core.adapter

import com.nettarion.hyperborea.core.model.ClientInfo
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.ExerciseData

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface BroadcastAdapter : Adapter {
    val id: BroadcastId
    val connectedClients: StateFlow<Set<ClientInfo>>

    /**
     * Begin broadcasting exercise data from [dataSource].
     *
     * - If the adapter is already [AdapterState.Active] or [AdapterState.Activating], this is a no-op.
     * - On success, transitions state to [AdapterState.Active].
     * - If [dataSource] completes normally, transitions to [AdapterState.Inactive].
     * - If [dataSource] throws, transitions to [AdapterState.Error] with the exception detail.
     * - A stopped adapter can be restarted by calling [start] again.
     */
    suspend fun start(dataSource: Flow<ExerciseData>, deviceInfo: DeviceInfo)

    /**
     * Stop broadcasting and release resources.
     *
     * - If the adapter is already [AdapterState.Inactive], this is a no-op.
     * - Resets [connectedClients] to an empty set.
     * - Transitions state to [AdapterState.Inactive].
     */
    suspend fun stop()

    val incomingCommands: Flow<DeviceCommand>
}
