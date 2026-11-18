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
        // Long enough for the two blocks plus the automatic taper a single-pass program appends.
        repeat(60) {
            engine.advance(1000).filterIsInstance<ProgramEvent.Cue>().filter { it.count > 0 }
                .forEach { cues += it.count to isFinalStopCountdown(engine.state) }
        }
        return cues
    }

    @Test fun `only the countdown to a single pass program's end is a stop warning`() {
        val cues = cueKinds(WorkoutProgram(blocks = blocks))
        // Cues 3-2-1 come before the lead-in ends, before the A→B change, before the belt starts
        // easing down and before the stop. Only the last set is a stop warning.
        assertThat(cues.filter { it.second }.map { it.first }).containsExactly(3, 2, 1).inOrder()
        assertThat(cues.takeLast(3).all { it.second }).isTrue()
        assertThat(cues.count { !it.second }).isEqualTo(8) // lead-in (2-1 after begin), block change and taper start stay strict
    }

    @Test fun `without the taper the stop warning comes at the end of the last block`() {
        val cues = cueKinds(WorkoutProgram(blocks = blocks, easeToStop = false))
        assertThat(cues.filter { it.second }.map { it.first }).containsExactly(3, 2, 1).inOrder()
        assertThat(cues.count { !it.second }).isEqualTo(5)
    }

    @Test fun `looping programs never treat a countdown as a stop warning`() {
        assertThat(cueKinds(WorkoutProgram(blocks = blocks, loop = true)).none { it.second }).isTrue()
    }
}
