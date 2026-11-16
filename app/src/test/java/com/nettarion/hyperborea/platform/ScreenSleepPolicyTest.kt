package com.nettarion.hyperborea.platform

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.orchestration.OrchestratorState
import org.junit.Test

class ScreenSleepPolicyTest {
    @Test fun `disabled screen sleep overrides firmware idle timeout in every state`() {
        for (state in listOf(OrchestratorState.Idle, OrchestratorState.Paused, OrchestratorState.Stopping,
            OrchestratorState.Running(), OrchestratorState.Error("error"))) {
            assertThat(shouldKeepScreenOn(false, state)).isTrue()
        }
    }
    @Test fun `enabled screen sleep allows only idle stopped and error screens to sleep`() {
        for (state in listOf(OrchestratorState.Idle, OrchestratorState.Stopping, OrchestratorState.Error("error"))) {
            assertThat(shouldKeepScreenOn(true, state)).isFalse()
        }
        for (state in listOf(OrchestratorState.Paused, OrchestratorState.Running(),
            OrchestratorState.AwaitingConsoleStart("Start"))) {
            assertThat(shouldKeepScreenOn(true, state)).isTrue()
        }
    }
}
