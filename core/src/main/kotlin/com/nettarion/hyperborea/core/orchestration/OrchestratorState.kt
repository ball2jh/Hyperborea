package com.nettarion.hyperborea.core.orchestration

sealed interface OrchestratorState {
    data object Idle : OrchestratorState
    data class Preparing(val step: String) : OrchestratorState

    /**
     * Equipment is connected, the console is armed (WORKOUT_MODE = WARM_UP), and broadcasts are
     * live — but the workout has not started yet because the MCU gates belt motion on the
     * physical Start key (treadmill / incline-trainer safety convention; the read-only
     * `START_REQUESTED` telemetry field rises on the key press, and only then does the MCU
     * complete the WARM_UP → RUNNING transition). The workout-mode monitor promotes this state
     * to [Running] when it observes `WORKOUT_MODE = RUNNING` on the wire.
     */
    data class AwaitingConsoleStart(val message: String) : OrchestratorState

    data class Running(val degraded: String? = null) : OrchestratorState
    data class Error(val message: String, val cause: Throwable? = null) : OrchestratorState
    data object Paused : OrchestratorState
    data object Stopping : OrchestratorState
}
