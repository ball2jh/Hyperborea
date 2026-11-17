package com.nettarion.hyperborea.program

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.program.*
import org.junit.Test

class ProgramOverlayPresentationTest {
    @Test fun `manual final ramp never suggests returning speed to its overridden endpoint`() {
        val p = WorkoutProgram()
        val ui = programOverlayPresentation(ProgramRunState(p, status = ProgramStatus.RUNNING,
            blockIndex = 2, manualSpeed = true, manualIncline = true, nextChangeSeconds = 8),
            ExerciseData.ZERO.copy(speed = 6f, incline = 2f), false)
        assertThat(ui.targetSpeed).isEqualTo("0.0")
        assertThat(ui.targetIncline).isEqualTo("2.0")
        assertThat(ui.caption).isEqualTo("seconds to stop")
    }
    @Test fun `overridden ramp axis shows live value and next block target`() {
        val p = WorkoutProgram()
        val state = ProgramRunState(status = ProgramStatus.RUNNING, blockIndex = 1,
            program = p.copy(blocks = p.blocks.mapIndexed { i, b -> if (i == 1) b.copy(ramp = true) else b }),
            target = ProgramTarget(6f, .5f), nextTarget = ProgramTarget(4f, 0f), manualSpeed = true)
        val ui = programOverlayPresentation(state, ExerciseData.ZERO.copy(speed = 7f, incline = .5f), false)
        assertThat(ui.currentSpeed).isEqualTo("7.0")
        assertThat(ui.targetSpeed).isEqualTo("4.0")
        assertThat(ui.targetIncline).isEqualTo("1.0")
        assertThat(ui.manualSpeed).isTrue()
        assertThat(ui.manualIncline).isFalse()
    }
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
