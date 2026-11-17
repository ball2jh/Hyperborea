package com.nettarion.hyperborea.program

import com.nettarion.hyperborea.core.orchestration.OrchestratorState

internal enum class ProgramStartMode { NEW_WORKOUT, AFTER_WARMUP }

/** A program can take over a healthy manual walk without reconnecting or resetting the recording. */
internal fun programStartMode(state: OrchestratorState, activeProgram: Boolean): ProgramStartMode? = when {
    state is OrchestratorState.Idle || state is OrchestratorState.Error -> ProgramStartMode.NEW_WORKOUT
    state is OrchestratorState.Running && state.degraded == null && !activeProgram -> ProgramStartMode.AFTER_WARMUP
    else -> null
}
