package com.nettarion.hyperborea.core

sealed interface OrchestratorState {
    data object Idle : OrchestratorState
    data class Preparing(val step: String) : OrchestratorState
    data object Running : OrchestratorState
    data class Error(val message: String, val cause: Throwable? = null) : OrchestratorState
    data object Stopping : OrchestratorState
}
