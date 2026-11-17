package com.nettarion.hyperborea.ui.program

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProgramDurationTest {
    @Test fun `minutes and mixed durations produce the intended program time`() {
        assertThat(parseProgramDuration("10", "0")).isEqualTo(600)
        assertThat(parseProgramDuration("1", "30")).isEqualTo(90)
        assertThat(parseProgramDuration("10", "")).isEqualTo(600)
    }
    @Test fun `short blocks and maximum duration retain their exact boundaries`() {
        assertThat(parseProgramDuration("", "10")).isEqualTo(10)
        assertThat(parseProgramDuration("60", "0")).isEqualTo(3600)
    }
    @Test fun `invalid durations cannot silently run the previously valid duration`() {
        for ((minutes, seconds) in listOf("0" to "9", "0" to "60", "60" to "1", "61" to "0", "-1" to "30", "x" to "0", "" to "")) {
            assertThat(parseProgramDuration(minutes, seconds)).isNull()
        }
    }
}
