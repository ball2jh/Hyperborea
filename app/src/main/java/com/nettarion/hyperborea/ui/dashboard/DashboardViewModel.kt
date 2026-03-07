package com.nettarion.hyperborea.ui.dashboard

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nettarion.hyperborea.HyperboreaService
import com.nettarion.hyperborea.core.AdapterState
import com.nettarion.hyperborea.core.BroadcastAdapter
import com.nettarion.hyperborea.core.BroadcastId
import com.nettarion.hyperborea.core.ClientInfo
import com.nettarion.hyperborea.core.HardwareAdapter
import com.nettarion.hyperborea.core.Orchestrator
import com.nettarion.hyperborea.core.SystemMonitor
import com.nettarion.hyperborea.core.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val orchestrator: Orchestrator,
    private val hardwareAdapter: HardwareAdapter,
    private val broadcastAdapters: Set<@JvmSuppressWildcards BroadcastAdapter>,
    private val systemMonitor: SystemMonitor,
    private val userPreferences: UserPreferences,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val broadcastsFlow: Flow<List<BroadcastUiState>> = combine(
        buildList<Flow<*>> {
            for (adapter in broadcastAdapters.sortedBy { it.id.ordinal }) {
                add(adapter.state)
                add(adapter.connectedClients)
            }
            add(userPreferences.enabledBroadcasts)
        },
    ) { values ->
        val enabledIds = values.last() as Set<*>
        val sorted = broadcastAdapters.sortedBy { it.id.ordinal }
        sorted.mapIndexed { i, adapter ->
            val state = values[i * 2] as AdapterState
            @Suppress("UNCHECKED_CAST")
            val clients = values[i * 2 + 1] as Set<ClientInfo>
            BroadcastUiState(
                id = adapter.id,
                state = state,
                clientCount = clients.size,
                enabled = adapter.id in enabledIds,
            )
        }
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        orchestrator.state,
        hardwareAdapter.exerciseData,
        hardwareAdapter.state,
        hardwareAdapter.deviceInfo,
        combine(systemMonitor.snapshot.map { it.status }, broadcastsFlow, ::Pair),
    ) { orchState, exercise, hwState, deviceInfo, (status, broadcasts) ->
        DashboardUiState(
            orchestratorState = orchState,
            exerciseData = exercise,
            hardwareState = hwState,
            deviceInfo = deviceInfo,
            broadcasts = broadcasts,
            systemStatus = status,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        DashboardUiState(
            orchestratorState = orchestrator.state.value,
            exerciseData = null,
            hardwareState = AdapterState.Inactive,
            deviceInfo = null,
            broadcasts = emptyList(),
            systemStatus = systemMonitor.snapshot.value.status,
        ),
    )

    fun startBroadcasting() {
        val intent = Intent(context, HyperboreaService::class.java).apply {
            action = HyperboreaService.ACTION_ACTIVATE
        }
        context.startService(intent)
    }

    fun stopBroadcasting() {
        val intent = Intent(context, HyperboreaService::class.java).apply {
            action = HyperboreaService.ACTION_DEACTIVATE
        }
        context.startService(intent)
    }

    fun toggleBroadcast(id: BroadcastId, enabled: Boolean) {
        userPreferences.setBroadcastEnabled(id, enabled)
    }
}
