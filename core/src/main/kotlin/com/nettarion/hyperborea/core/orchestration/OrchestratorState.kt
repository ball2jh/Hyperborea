package com.nettarion.hyperborea.core.orchestration

sealed interface OrchestratorState {
    data object Idle : OrchestratorState
    data class Preparing(val step: String) : OrchestratorState

    /**
     * Equipment is connected, the treadmill console is safely parked before the run, and
     * broadcasts are live. The physical Start key is the safety gate: after that explicit press,
     * the protocol session and MCU complete the transition to `WORKOUT_MODE = RUNNING`. The
     * workout-mode monitor then promotes this state to [Running].
     */
    data class AwaitingConsoleStart(val message: String) : OrchestratorState

    data class Running(val degraded: String? = null) : OrchestratorState
    data class Error(val message: String, val cause: Throwable? = null) : OrchestratorState
    data object Paused : OrchestratorState
    data object Stopping : OrchestratorState
}
