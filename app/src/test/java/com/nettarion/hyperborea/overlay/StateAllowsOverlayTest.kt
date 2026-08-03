package com.nettarion.hyperborea.overlay

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.orchestration.OrchestratorState
import com.nettarion.hyperborea.core.profile.OverlayStyle
import org.junit.Test

class StateAllowsOverlayTest {

    private val idle = OrchestratorState.Idle
    private val preparing = OrchestratorState.Preparing("step")
    private val awaiting = OrchestratorState.AwaitingConsoleStart("press start")
    private val running = OrchestratorState.Running()
    private val paused = OrchestratorState.Paused
    private val error = OrchestratorState.Error("boom")
    private val stopping = OrchestratorState.Stopping

    @Test
    fun `metrics bar only shows mid-workout`() {
        assertThat(stateAllowsOverlay(OverlayStyle.METRICS, running)).isTrue()
        assertThat(stateAllowsOverlay(OverlayStyle.METRICS, paused)).isTrue()

        assertThat(stateAllowsOverlay(OverlayStyle.METRICS, idle)).isFalse()
        assertThat(stateAllowsOverlay(OverlayStyle.METRICS, preparing)).isFalse()
        assertThat(stateAllowsOverlay(OverlayStyle.METRICS, awaiting)).isFalse()
        assertThat(stateAllowsOverlay(OverlayStyle.METRICS, error)).isFalse()
        assertThat(stateAllowsOverlay(OverlayStyle.METRICS, stopping)).isFalse()
    }

    @Test
    fun `control bar persists through idle and armed states`() {
        assertThat(stateAllowsOverlay(OverlayStyle.CONTROLS, idle)).isTrue()
        assertThat(stateAllowsOverlay(OverlayStyle.CONTROLS, preparing)).isTrue()
        assertThat(stateAllowsOverlay(OverlayStyle.CONTROLS, awaiting)).isTrue()
        assertThat(stateAllowsOverlay(OverlayStyle.CONTROLS, running)).isTrue()
        assertThat(stateAllowsOverlay(OverlayStyle.CONTROLS, paused)).isTrue()
    }

    @Test
    fun `error and stopping hide both styles`() {
        assertThat(stateAllowsOverlay(OverlayStyle.CONTROLS, error)).isFalse()
        assertThat(stateAllowsOverlay(OverlayStyle.CONTROLS, stopping)).isFalse()
    }
}
