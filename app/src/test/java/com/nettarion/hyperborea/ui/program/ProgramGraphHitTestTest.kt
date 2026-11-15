package com.nettarion.hyperborea.ui.program

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.program.ProgramBlock
import com.nettarion.hyperborea.core.program.WorkoutProgram
import org.junit.Test

class ProgramGraphHitTestTest {
    private val program = WorkoutProgram(blocks = listOf(
        ProgramBlock(durationSeconds = 10, speedKph = 5f),
        ProgramBlock(durationSeconds = 10, speedKph = 8f, inclinePercent = 5f, ramp = true),
        ProgramBlock(durationSeconds = 10, speedKph = 4f),
    ))
    @Test fun `ramp endpoint edits its own block on either side of a rounded time boundary`() {
        for (time in listOf(19.999f, 20f, 20.001f)) {
            assertThat(programGraphBlockAt(program,time,8f,true,48f,12f,24f)).isEqualTo(1)
            assertThat(programGraphBlockAt(program,time,5f,false,48f,12f,24f)).isEqualTo(1)
        }
    }
    @Test fun `hold markers and empty graph regions still select the intended block`() {
        assertThat(programGraphBlockAt(program,25f,4f,true,48f,12f,24f)).isEqualTo(2)
        assertThat(programGraphBlockAt(program,11f,20f,true,48f,12f,24f)).isEqualTo(1)
        assertThat(programGraphBlockAt(program,30f,20f,true,48f,12f,24f)).isEqualTo(2)
    }
}
