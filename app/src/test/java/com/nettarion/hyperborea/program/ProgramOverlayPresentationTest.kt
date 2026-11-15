package com.nettarion.hyperborea.program

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.program.*
import org.junit.Test

class ProgramOverlayPresentationTest {
    @Test fun `loop boundary shows repeat and the first target instead of a belt stop`() {
        val p = WorkoutProgram(loop = true)
        val state = ProgramRunState(p, status = ProgramStatus.RUNNING, blockIndex = 2, nextTarget = ProgramTarget(5f, 0f), nextChangeSeconds = 3, countdown = 3)
        val ui = programOverlayPresentation(state, null, false)
        assertThat(ui.caption).isEqualTo("seconds to repeat")
        assertThat(ui.targetSpeed).isEqualTo("5.0")
        assertThat(ui.warning).isTrue()
    }
    private val run = ProgramRunState(WorkoutProgram(), status = ProgramStatus.RUNNING, target = ProgramTarget(8f, 1f), nextTarget = ProgramTarget(10f, 5f), nextChangeSeconds = 12)

    @Test fun `current values come from live equipment rather than the commanded target`() {
        val ui = programOverlayPresentation(run, ExerciseData.ZERO.copy(speed = 5f, incline = .5f), true)
        assertThat(ui.currentSpeed).isEqualTo("3.1")
        assertThat(ui.targetSpeed).isEqualTo("6.2")
        assertThat(ui.currentIncline).isEqualTo("0.5")
        assertThat(ui.targetIncline).isEqualTo("5.0")
        assertThat(ui.speedUnit).isEqualTo("mph")
        assertThat(ui.value).isEqualTo("12")
    }

    @Test fun `missing measurements are not replaced with an assumed current speed`() {
        val ui = programOverlayPresentation(run, null, false)
        assertThat(ui.currentSpeed).isEqualTo("—")
        assertThat(ui.currentIncline).isEqualTo("—")
        assertThat(ui.targetSpeed).isEqualTo("10.0")
        assertThat(ui.speedUnit).isEqualTo("km/h")
    }

    @Test fun `initial count-in points to first target rather than the next block`() {
        val ui = programOverlayPresentation(run.copy(status = ProgramStatus.COUNTDOWN, countdown = 3), null, false)
        assertThat(ui.value).isEqualTo("3")
        assertThat(ui.targetSpeed).isEqualTo("8.0")
        assertThat(ui.warning).isTrue()
    }

    @Test fun `paused and manual states replace timing with an explicit resume state`() {
        val paused = programOverlayPresentation(run.copy(status = ProgramStatus.PAUSED, countdown = 3), null, false)
        assertThat(paused.value).isEqualTo("Paused")
        assertThat(paused.targetSpeed).isEqualTo("8.0")
        assertThat(paused.warning).isFalse()
        val manual = programOverlayPresentation(run.copy(status = ProgramStatus.MANUAL), null, false)
        assertThat(manual.value).isEqualTo("Manual")
        assertThat(manual.caption).isEqualTo("Resume program in app")
        assertThat(programOverlayPresentation(run.copy(status = ProgramStatus.MANUAL, message = "Countdown sound unavailable"), null, false).caption).contains("Sound unavailable")
        assertThat(programOverlayPresentation(run.copy(status = ProgramStatus.MANUAL, message = "Connection or timing interrupted"), null, false).caption).contains("Connection interrupted")
    }

    @Test fun `a ramp shows the endpoint it is moving toward`() {
        val ramp = run.copy(program = WorkoutProgram(blocks = listOf(ProgramBlock(durationSeconds = 10, speedKph = 5f), ProgramBlock(durationSeconds = 10, speedKph = 8f, inclinePercent = 3f, ramp = true))), blockIndex = 1, elapsedMillis = 14000)
        val ui = programOverlayPresentation(ramp, null, false)
        assertThat(ui.targetSpeed).isEqualTo("8.0")
        assertThat(ui.targetIncline).isEqualTo("3.0")
        assertThat(ui.caption).isEqualTo("seconds to target")
        assertThat(ui.value).isEqualTo("6")
    }

    @Test fun `stop warning predicts zero speed without suggesting an incline reset`() {
        val ui = programOverlayPresentation(run.copy(blockIndex = 2, countdown = 3, nextTarget = null), null, false)
        assertThat(ui.value).isEqualTo("3")
        assertThat(ui.caption).isEqualTo("seconds to stop")
        assertThat(ui.targetSpeed).isEqualTo("0.0")
        assertThat(ui.targetIncline).isEqualTo("1.0")
    }
}
