package com.nettarion.hyperborea.broadcast.dircon

import com.nettarion.hyperborea.core.AdapterState
import com.nettarion.hyperborea.core.BroadcastAdapter
import com.nettarion.hyperborea.core.ClientInfo
import com.nettarion.hyperborea.core.DeviceCommand
import com.nettarion.hyperborea.core.ExerciseData
import com.nettarion.hyperborea.core.Prerequisite
import com.nettarion.hyperborea.core.SystemSnapshot
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

@Singleton
class DirconAdapter @Inject constructor() : BroadcastAdapter {

    override val prerequisites: List<Prerequisite> = emptyList()

    override fun canOperate(snapshot: SystemSnapshot): Boolean =
        snapshot.status.isWifiEnabled

    private val _state = MutableStateFlow<AdapterState>(AdapterState.Inactive)
    override val state: StateFlow<AdapterState> = _state.asStateFlow()

    private val _connectedClients = MutableStateFlow<Set<ClientInfo>>(emptySet())
    override val connectedClients: StateFlow<Set<ClientInfo>> = _connectedClients.asStateFlow()

    override val incomingCommands: Flow<DeviceCommand> = emptyFlow()

    override suspend fun start(dataSource: Flow<ExerciseData>) {
        if (_state.value is AdapterState.Active || _state.value is AdapterState.Activating) return
        _state.value = AdapterState.Activating
        // TODO: Start TCP server for Wahoo DIRCON protocol
        _state.value = AdapterState.Active
    }

    override suspend fun stop() {
        if (_state.value is AdapterState.Inactive) return
        _state.value = AdapterState.Inactive
        _connectedClients.value = emptySet()
    }
}
