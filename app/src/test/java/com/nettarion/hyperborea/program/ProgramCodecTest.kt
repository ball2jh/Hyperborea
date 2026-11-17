package com.nettarion.hyperborea.program

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.program.WorkoutProgram
import org.junit.Test

class ProgramCodecTest {
    @Test fun `loop choice survives save and old programs remain single pass`() {
        val p = WorkoutProgram(loop = true)
        assertThat(ProgramCodec.decode(ProgramCodec.encode(p))).isEqualTo(p)
        val old = org.json.JSONObject(ProgramCodec.encode(p)).apply { remove("loop") }.toString()
        assertThat(ProgramCodec.decode(old).loop).isFalse()
    }
    @Test fun `saved program retains reordered blocks ramps and precision`() {
        val original = WorkoutProgram().let { it.copy(name = "Hills & intervals", blocks = it.blocks.mapIndexed { i, b -> b.copy(speedKph = 7.3f + i, inclinePercent = -.5f + i, ramp = i > 0) }) }.moveBlock(2, 0)
        assertThat(ProgramCodec.decode(ProgramCodec.encode(original))).isEqualTo(original)
    }
    @Test(expected = IllegalArgumentException::class) fun `unknown format is rejected`() { ProgramCodec.decode(ProgramCodec.encode(WorkoutProgram()).replace("\"version\":1", "\"version\":99")) }
    @Test(expected = IllegalArgumentException::class) fun `invalid duration cannot be loaded and run`() { ProgramCodec.decode(ProgramCodec.encode(WorkoutProgram()).replace("\"seconds\":300", "\"seconds\":0")) }
}
