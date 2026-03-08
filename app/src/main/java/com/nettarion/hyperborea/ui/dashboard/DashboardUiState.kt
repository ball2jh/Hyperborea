package com.nettarion.hyperborea.ui.dashboard

import com.nettarion.hyperborea.core.adapter.AdapterState
import com.nettarion.hyperborea.core.adapter.BroadcastId
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.orchestration.OrchestratorState
import com.nettarion.hyperborea.core.system.SystemStatus

data class DashboardUiState(
    val orchestratorState: OrchestratorState,
    val exerciseData: ExerciseData?,
    val hardwareState: AdapterState,
    val deviceInfo: DeviceInfo?,
    val broadcasts: List<BroadcastUiState>,
    val systemStatus: SystemStatus,
    val profileName: String? = null,
)

data class BroadcastUiState(
    val id: BroadcastId,
    val state: AdapterState,
    val clientCount: Int,
    val enabled: Boolean,
)
