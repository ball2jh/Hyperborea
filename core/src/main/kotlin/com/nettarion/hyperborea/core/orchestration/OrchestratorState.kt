package com.nettarion.hyperborea.core.orchestration

sealed interface OrchestratorState {
    data object Idle : OrchestratorState
    data class Preparing(val step: String) : OrchestratorState
    data class Running(val degraded: String? = null) : OrchestratorState
    data class Error(val message: String, val cause: Throwable? = null) : OrchestratorState
    data object Paused : OrchestratorState
    data object Stopping : OrchestratorState
}
