package com.nettarion.hyperborea.program

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.orchestration.OrchestratorState
import org.junit.Test

class ProgramStartModeTest {
    @Test fun `healthy manual walk can continue into a program`() {
        assertThat(programStartMode(OrchestratorState.Running(), false)).isEqualTo(ProgramStartMode.AFTER_WARMUP)
    }

    @Test fun `program takeover cannot replace an active program or bypass a degraded connection`() {
        assertThat(programStartMode(OrchestratorState.Running(), true)).isNull()
        assertThat(programStartMode(OrchestratorState.Running("Connection lost"), false)).isNull()
    }

    @Test fun `paused preparing armed and stopping workouts cannot be taken over`() {
        listOf(OrchestratorState.Paused, OrchestratorState.Preparing("Connecting"),
            OrchestratorState.AwaitingConsoleStart("Start"), OrchestratorState.Stopping).forEach {
            assertThat(programStartMode(it, false)).isNull()
        }
    }

    @Test fun `idle and error still start a new workout`() {
        assertThat(programStartMode(OrchestratorState.Idle, false)).isEqualTo(ProgramStartMode.NEW_WORKOUT)
        assertThat(programStartMode(OrchestratorState.Error("Stopped"), false)).isEqualTo(ProgramStartMode.NEW_WORKOUT)
    }
}
