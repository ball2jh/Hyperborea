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
    @Test fun `ease to a stop survives save and programs saved before it existed get it`() {
        val off = WorkoutProgram(easeToStop = false)
        assertThat(ProgramCodec.decode(ProgramCodec.encode(off))).isEqualTo(off)
        val old = org.json.JSONObject(ProgramCodec.encode(off)).apply { remove("easeToStop") }.toString()
        assertThat(ProgramCodec.decode(old).easeToStop).isTrue()
    }

    @Test fun `a hand written file needs only blocks with seconds and speed`() {
        val p = ProgramCodec.decode(
            """{ "speedUnit": "mph", "blocks": [
                 { "seconds": 300, "speed": 4 },
                 { "name": "Ramp", "seconds": 30, "speed": 6.5, "incline": 1.5, "ramp": true } ] }""",
            fallbackId = "from-file", fallbackName = "ramps",
        )
        assertThat(p.id).isEqualTo("from-file")
        assertThat(p.name).isEqualTo("ramps")
        assertThat(p.loop).isFalse()
        assertThat(p.easeToStop).isTrue()
        assertThat(p.blocks.map { it.name }).containsExactly("Block 1", "Ramp").inOrder()
        assertThat(p.blocks.map { it.durationSeconds }).containsExactly(300, 30).inOrder()
        // mph in the file, km/h in the program.
        assertThat(p.blocks[0].speedKph).isWithin(1e-3f).of(6.437f)
        assertThat(p.blocks[1].speedKph).isWithin(1e-3f).of(10.461f)
        assertThat(p.blocks.map { it.inclinePercent }).containsExactly(0f, 1.5f).inOrder()
        assertThat(p.blocks.map { it.ramp }).containsExactly(false, true).inOrder()
        assertThat(p.blocks.map { it.id }.distinct()).hasSize(2)
    }

    @Test fun `an exported file reads back as the same program in either unit`() {
        val original = WorkoutProgram(name = "Ramps", loop = true, easeToStop = false, blocks = listOf(
            com.nettarion.hyperborea.core.program.ProgramBlock(name = "Warm-up", durationSeconds = 300, speedKph = 6.5f / .621371f),
            com.nettarion.hyperborea.core.program.ProgramBlock(name = "Ramp", durationSeconds = 30, speedKph = 8.5f / .621371f, inclinePercent = 1.5f, ramp = true),
        ))
        for (imperial in listOf(true, false)) {
            val file = ProgramCodec.encodeFile(original, imperial)
            val back = ProgramCodec.decode(file)
            assertThat(back.copy(blocks = emptyList())).isEqualTo(original.copy(blocks = emptyList()))
            assertThat(back.blocks.map { it.name to it.durationSeconds }).isEqualTo(original.blocks.map { it.name to it.durationSeconds })
            assertThat(back.blocks.map { it.ramp to it.inclinePercent }).isEqualTo(original.blocks.map { it.ramp to it.inclinePercent })
            back.blocks.zip(original.blocks).forEach { (a, b) -> assertThat(a.speedKph).isWithin(1e-3f).of(b.speedKph) }
        }
        // The rider's own numbers are what the file shows.
        val blocks = org.json.JSONObject(ProgramCodec.encodeFile(original, imperial = true)).getJSONArray("blocks")
        assertThat(blocks.getJSONObject(0).getDouble("speed")).isEqualTo(6.5)
        assertThat(blocks.getJSONObject(1).getDouble("speed")).isEqualTo(8.5)
        assertThat(blocks.getJSONObject(0).has("id")).isFalse()
    }

    @Test fun `a broken file is refused with a reason that names the problem`() {
        fun reason(json: String) = runCatching { ProgramCodec.decode(json, fallbackName = "x") }.exceptionOrNull()
            .also { assertThat(it).isInstanceOf(IllegalArgumentException::class.java) }!!.message!!
        assertThat(reason("not json")).contains("Not valid JSON")
        assertThat(reason("""{ "blocks": [ { "speed": 5 } ] }""")).contains("seconds")
        assertThat(reason("""{ "speedUnit": "knots", "blocks": [ { "seconds": 60, "speed": 5 } ] }""")).contains("speedUnit")
        assertThat(reason("""{ "blocks": [ { "seconds": 60, "speed": 99 } ] }""")).contains("outside the supported range")
        assertThat(reason("""{ "blocks": [] }""")).contains("between 1 and 100 blocks")
    }

    @Test fun `program files get shell safe names that only carry an id when they would collide`() {
        val a = WorkoutProgram(id = "11111111-aaaa", name = "30s Ramps: 6.5→8.5 mph")
        val b = WorkoutProgram(id = "22222222-bbbb", name = "Hills")
        val c = WorkoutProgram(id = "33333333-cccc", name = "hills")
        val names = programFileNames(listOf(a, b, c))
        assertThat(names[a]).isEqualTo("30s-Ramps-6.5-8.5-mph.json")
        assertThat(names[b]).isEqualTo("Hills-22222222.json")
        assertThat(names[c]).isEqualTo("hills-33333333.json")
        assertThat(programFileNames(listOf(WorkoutProgram(name = "→→"))).values).containsExactly("program.json")
        assertThat(programFileId("ramps.json")).isEqualTo(programFileId("ramps.json"))
        assertThat(programFileId("ramps.json")).isNotEqualTo(programFileId("hills.json"))
    }

    @Test(expected = IllegalArgumentException::class) fun `unknown format is rejected`() { ProgramCodec.decode(ProgramCodec.encode(WorkoutProgram()).replace("\"version\":1", "\"version\":99")) }
    @Test(expected = IllegalArgumentException::class) fun `invalid duration cannot be loaded and run`() { ProgramCodec.decode(ProgramCodec.encode(WorkoutProgram()).replace("\"seconds\":300", "\"seconds\":0")) }
}
