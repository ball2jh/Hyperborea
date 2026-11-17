package com.nettarion.hyperborea.program

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.program.*
import org.junit.Test

class FinalStopCountdownTest {
    private val blocks = listOf(
        ProgramBlock(name = "A", durationSeconds = 10, speedKph = 5f),
        ProgramBlock(name = "B", durationSeconds = 10, speedKph = 8f),
    )

    /** Runs [program] second by second, recording whether each 3-2-1 cue is a final-stop warning. */
    private fun cueKinds(program: WorkoutProgram): List<Pair<Int, Boolean>> {
        val engine = ProgramEngine(program)
        val cues = mutableListOf<Pair<Int, Boolean>>()
        engine.begin()
        repeat(30) {
            engine.advance(1000).filterIsInstance<ProgramEvent.Cue>().filter { it.count > 0 }
                .forEach { cues += it.count to isFinalStopCountdown(engine.state) }
        }
        return cues
    }

    @Test fun `only the countdown to a single pass program's end is a stop warning`() {
        val cues = cueKinds(WorkoutProgram(blocks = blocks))
        // Cues 3-2-1 come before the lead-in ends, before the A→B change and before the stop.
        assertThat(cues.filter { it.second }.map { it.first }).containsExactly(3, 2, 1).inOrder()
        assertThat(cues.count { !it.second }).isAtLeast(3) // lead-in and block change stay strict
    }

    @Test fun `looping programs never treat a countdown as a stop warning`() {
        assertThat(cueKinds(WorkoutProgram(blocks = blocks, loop = true)).none { it.second }).isTrue()
    }
}
