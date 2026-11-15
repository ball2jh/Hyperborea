package com.nettarion.hyperborea.program

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.program.*
import org.junit.Test

class ProgramPresentationTest {
    @Test fun `loop status tracks the current repetition without presenting a stop warning`() {
        val run = ProgramRunState(WorkoutProgram(loop = true), status = ProgramStatus.RUNNING, completedLoops = 2, countdown = 3)
        val text = programStatusText(run, false)
        assertThat(text).contains("Loop 3")
        assertThat(text).contains("left in loop")
        assertThat(text).doesNotContain("BELT STOPS")
    }
    @Test fun `background status carries current and upcoming targets in chosen units`() {
        val run = ProgramRunState(WorkoutProgram(), status=ProgramStatus.RUNNING, target=ProgramTarget(8f,1f), nextTarget=ProgramTarget(10f,5f), nextChangeSeconds=3, countdown=3)
        assertThat(programStatusText(run,false)).contains("8.0 km/h → 10.0 km/h")
        assertThat(programStatusText(run,true)).contains("5.0 mph → 6.2 mph")
        assertThat(programStatusText(run,true)).contains("1.0% → 5.0%")
        assertThat(programStatusText(run,true)).contains("CHANGE IN 3")
        assertThat(programStatusText(run,true)).doesNotContain("audible countdown")
        assertThat(programStatusText(run.copy(status = ProgramStatus.COUNTDOWN),true)).doesNotContain("audible countdown")
    }
    @Test fun `the final countdown clearly announces that the belt will stop`() {
        val run = ProgramRunState(WorkoutProgram(), status=ProgramStatus.RUNNING, countdown=3)
        assertThat(programStatusText(run,false)).contains("BELT STOPS IN 3")
        assertThat(programStatusText(run,false)).doesNotContain("audible countdown")
    }
    @Test fun `paused status never implies that an automatic change is counting down`() {
        val run = ProgramRunState(WorkoutProgram(),status=ProgramStatus.MANUAL,nextTarget=ProgramTarget(10f,5f),nextChangeSeconds=3,message="Manual control")
        assertThat(programStatusText(run,false)).doesNotContain("Next in")
        assertThat(programStatusText(run,false)).contains("Manual control")
    }
    @Test fun `finished status does not present stopped belt as an active target`() {
        assertThat(programStatusText(ProgramRunState(WorkoutProgram(),status=ProgramStatus.COMPLETE,message="Session complete"),false)).contains("Session complete")
        assertThat(programStatusText(ProgramRunState(WorkoutProgram(),status=ProgramStatus.COMPLETE),false)).doesNotContain("Targets:")
    }
}
