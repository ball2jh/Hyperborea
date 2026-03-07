package com.nettarion.hyperborea.ui.dashboard

import com.nettarion.hyperborea.core.AdapterState
import com.nettarion.hyperborea.core.BroadcastId
import com.nettarion.hyperborea.core.DeviceInfo
import com.nettarion.hyperborea.core.ExerciseData
import com.nettarion.hyperborea.core.OrchestratorState
import com.nettarion.hyperborea.core.SystemStatus

data class DashboardUiState(
    val orchestratorState: OrchestratorState,
    val exerciseData: ExerciseData?,
    val hardwareState: AdapterState,
    val deviceInfo: DeviceInfo?,
    val broadcasts: List<BroadcastUiState>,
    val systemStatus: SystemStatus,
)

data class BroadcastUiState(
    val id: BroadcastId,
    val state: AdapterState,
    val clientCount: Int,
    val enabled: Boolean,
)
