package com.nettarion.hyperborea.core.program

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProgramEngineTest {
    @Test fun `speed override keeps clock and incline ramp running until the next block`() {
        val p = program(ramp = true).copy(blocks = program(ramp = true).blocks + ProgramBlock(name = "Recover", durationSeconds = 10, speedKph = 5f))
        val e = loopingEngine(p)
        repeat(12) { e.advance(1000) }
        e.overrideForBlock(speed = true)
        val events = (1..7).flatMap { e.advance(1000) }.filterIsInstance<ProgramEvent.Target>()
        assertThat(e.state.elapsedMillis).isEqualTo(19000)
        assertThat(e.state.status).isEqualTo(ProgramStatus.RUNNING)
        assertThat(events).isNotEmpty()
        assertThat(events.all { !it.controlSpeed && it.controlIncline }).isTrue()
        assertThat(events.last().target.inclinePercent).isGreaterThan(events.first().target.inclinePercent)
        val boundary = e.advance(1000).filterIsInstance<ProgramEvent.Target>().single()
        assertThat(boundary.controlSpeed).isTrue()
        assertThat(e.state.manualSpeed).isFalse()
    }

    @Test fun `incline override leaves speed automated and both overrides survive pause`() {
        val e = loopingEngine(program(ramp = true))
        repeat(12) { e.advance(1000) }; e.overrideForBlock(incline = true)
        val event = e.advance(1000).filterIsInstance<ProgramEvent.Target>().single()
        assertThat(event.controlSpeed).isTrue(); assertThat(event.controlIncline).isFalse()
        e.overrideForBlock(speed = true); e.hold(); e.begin()
        assertThat(e.state.manualSpeed && e.state.manualIncline).isTrue()
        val targets = (1..3).flatMap { e.advance(1000) }.filterIsInstance<ProgramEvent.Target>()
        assertThat(targets).isEmpty()
    }

    @Test fun `late manual override restarts three full warnings before same target block reclaims axis`() {
        val p = WorkoutProgram(blocks = listOf(ProgramBlock(durationSeconds = 10), ProgramBlock(durationSeconds = 10)))
        val e = loopingEngine(p)
        repeat(9) { e.advance(1000) }; e.advance(500)
        e.overrideForBlock(speed = true)
        val events = (1..30).flatMap { e.advance(100) }
        assertThat(events.filterIsInstance<ProgramEvent.Cue>()).containsExactly(ProgramEvent.Cue(3), ProgramEvent.Cue(2), ProgramEvent.Cue(1), ProgramEvent.Cue(0)).inOrder()
        assertThat(e.state.blockIndex).isEqualTo(1)
        assertThat(e.state.elapsedMillis).isEqualTo(10000)
        assertThat(events.filterIsInstance<ProgramEvent.Target>().last().controlSpeed).isTrue()
    }

    @Test fun `single block loop reclaims both manual axes with warnings`() {
        val e = loopingEngine(WorkoutProgram(loop = true, blocks = listOf(ProgramBlock(durationSeconds = 10))))
        repeat(8) { e.advance(1000) }; e.overrideForBlock(speed = true, incline = true)
        val events = (1..30).flatMap { e.advance(100) }
        assertThat(events.filterIsInstance<ProgramEvent.Cue>()).containsExactly(ProgramEvent.Cue(3), ProgramEvent.Cue(2), ProgramEvent.Cue(1), ProgramEvent.Cue(0)).inOrder()
        assertThat(e.state.completedLoops).isEqualTo(1)
        assertThat(e.state.manualSpeed || e.state.manualIncline).isFalse()
        assertThat(events.filterIsInstance<ProgramEvent.Target>().single()).isEqualTo(ProgramEvent.Target(ProgramTarget(8f, 0f)))
    }

    @Test fun `program validation rejects every non treadmill equipment type`() {
        for (type in com.nettarion.hyperborea.core.model.DeviceType.entries) {
            val device = com.nettarion.hyperborea.core.model.DeviceInfo.DEFAULT_INDOOR_BIKE.copy(type = type)
            assertThat(WorkoutProgram().validationError(device) == null).isEqualTo(type == com.nettarion.hyperborea.core.model.DeviceType.TREADMILL)
        }
    }
    private fun loopingEngine(p: WorkoutProgram = program().copy(loop = true)) = ProgramEngine(p).apply { begin(); repeat(3) { advance(1000) } }

    @Test fun `loop repeats from the first block without completing or adding another startup delay`() {
        val e = loopingEngine()
        val events = (1..20).flatMap { e.advance(1000) }
        assertThat(e.state.completedLoops).isEqualTo(1)
        assertThat(e.state.elapsedMillis).isEqualTo(0)
        assertThat(e.state.blockIndex).isEqualTo(0)
        assertThat(e.state.target).isEqualTo(ProgramTarget(5f, 0f))
        assertThat(e.state.status).isEqualTo(ProgramStatus.RUNNING)
        assertThat(events.filterIsInstance<ProgramEvent.Finished>()).isEmpty()
        assertThat(events.filterIsInstance<ProgramEvent.Cue>()).containsExactly(
            ProgramEvent.Cue(3), ProgramEvent.Cue(2), ProgramEvent.Cue(1), ProgramEvent.Cue(0),
            ProgramEvent.Cue(3), ProgramEvent.Cue(2), ProgramEvent.Cue(1), ProgramEvent.Cue(0),
        ).inOrder()
        repeat(40) { e.advance(1000) }
        assertThat(e.state.completedLoops).isEqualTo(3)
        e.end(ProgramStatus.CANCELLED, "Stopped")
        assertThat(e.advance(1000)).isEmpty()
    }

    @Test fun `loop wrap preserves fractional tick time for the graph cursor`() {
        val e = loopingEngine()
        repeat(19) { e.advance(1000) }; e.advance(950); e.advance(100)
        assertThat(e.state.elapsedMillis).isEqualTo(50)
        assertThat(e.state.completedLoops).isEqualTo(1)
    }

    @Test fun `a single repeated hold does not beep or rewrite identical targets each loop`() {
        val e = loopingEngine(WorkoutProgram(loop = true, blocks = listOf(ProgramBlock(durationSeconds = 10))))
        assertThat((1..30).flatMap { e.advance(1000) }).isEmpty()
        assertThat(e.state.completedLoops).isEqualTo(3)
        assertThat(e.state.active).isTrue()
    }

    @Test fun `resume near loop boundary still warns fully before returning to the first block`() {
        val e = loopingEngine(); repeat(19) { e.advance(1000) }; e.hold()
        e.begin(); repeat(3) { e.advance(1000) }
        val events = (1..30).flatMap { e.advance(100) }
        assertThat(events.filterIsInstance<ProgramEvent.Cue>()).containsExactly(ProgramEvent.Cue(3), ProgramEvent.Cue(2), ProgramEvent.Cue(1), ProgramEvent.Cue(0)).inOrder()
        assertThat(e.state.completedLoops).isEqualTo(1)
        assertThat(e.state.blockIndex).isEqualTo(0)
        assertThat(e.state.active).isTrue()
    }
    private fun program(ramp: Boolean = false) = WorkoutProgram(name = "Test", blocks = listOf(
        ProgramBlock(name = "Easy", durationSeconds = 10, speedKph = 5f),
        ProgramBlock(name = "Effort", durationSeconds = 10, speedKph = 10f, inclinePercent = 5f, ramp = ramp),
    ))
    private fun started(ramp: Boolean = false) = ProgramEngine(program(ramp)).apply { begin(); repeat(3) { advance(1000) } }

    @Test fun `arming never commands the belt before the physical start`() {
        val e = ProgramEngine(program())
        assertThat(e.advance(1000)).isEmpty()
        assertThat(e.state.elapsedMillis).isEqualTo(0)
        assertThat(e.begin()).containsExactly(ProgramEvent.Cue(3))
        assertThat(e.advance(1000)).containsExactly(ProgramEvent.Cue(2))
        assertThat(e.advance(1000)).containsExactly(ProgramEvent.Cue(1))
        assertThat(e.advance(1000)).containsExactly(ProgramEvent.Cue(0), ProgramEvent.Target(ProgramTarget(5f, 0f))).inOrder()
    }
    @Test fun `countdown is emitted once per second before the block changes`() {
        val e = started()
        val events = (1..100).flatMap { e.advance(100) }
        assertThat(events.filterIsInstance<ProgramEvent.Cue>()).containsExactly(ProgramEvent.Cue(3), ProgramEvent.Cue(2), ProgramEvent.Cue(1), ProgramEvent.Cue(0)).inOrder()
        assertThat(events.filterIsInstance<ProgramEvent.Target>()).containsExactly(ProgramEvent.Target(ProgramTarget(10f, 5f)))
        assertThat(e.state.blockIndex).isEqualTo(1)
    }
    @Test fun `pausing and manual control freeze the program clock and commands`() {
        for (manual in listOf(false, true)) {
            val e = started();e.advance(1000);e.hold(manual)
            repeat(20) { assertThat(e.advance(1000)).isEmpty() }
            assertThat(e.state.elapsedMillis).isEqualTo(1000)
            assertThat(e.begin()).containsExactly(ProgramEvent.Cue(3))
            assertThat(e.advance(1000)).containsExactly(ProgramEvent.Cue(2))
            assertThat(e.state.elapsedMillis).isEqualTo(1000)
        }
    }
    @Test fun `ramp starts at previous target and interpolates over block time`() {
        val e = started(ramp = true)
        repeat(10) { e.advance(1000) }
        assertThat(e.state.target).isEqualTo(ProgramTarget(5f, 0f))
        repeat(5) { e.advance(1000) }
        assertThat(e.state.target).isEqualTo(ProgramTarget(7.5f, 2.5f))
    }
    @Test fun `incline ramp advances between seconds without increasing speed update frequency`() {
        val e = started(ramp = true)
        repeat(10) { e.advance(1000) }
        e.advance(100)
        assertThat(e.state.target.inclinePercent).isWithin(.0001f).of(.05f)
        assertThat(e.state.target.speedKph).isEqualTo(5f)
        e.advance(100)
        assertThat(e.state.target.inclinePercent).isWithin(.0001f).of(.1f)
        assertThat(e.state.target.speedKph).isEqualTo(5f)
    }
    @Test fun `resuming just before a boundary replays a full countdown for the next change`() {
        val e = started(); repeat(8) { e.advance(1000) }; e.hold()
        e.begin(); repeat(3) { e.advance(1000) }
        val events = (1..30).flatMap { e.advance(100) }
        assertThat(events.filterIsInstance<ProgramEvent.Cue>()).containsExactly(ProgramEvent.Cue(3), ProgramEvent.Cue(2), ProgramEvent.Cue(1), ProgramEvent.Cue(0)).inOrder()
        assertThat(e.state.blockIndex).isEqualTo(1)
    }
    @Test fun `the automatic final stop has all three warnings and an acknowledgement`() {
        val engine = started()
        val events = (1..20).flatMap { engine.advance(1000) }
        assertThat(events.filterIsInstance<ProgramEvent.Cue>()).containsExactly(
            ProgramEvent.Cue(3), ProgramEvent.Cue(2), ProgramEvent.Cue(1), ProgramEvent.Cue(0),
            ProgramEvent.Cue(3), ProgramEvent.Cue(2), ProgramEvent.Cue(1), ProgramEvent.Cue(0),
        ).inOrder()
        assertThat(events.takeLast(2)).containsExactly(ProgramEvent.Cue(0), ProgramEvent.Finished).inOrder()
    }
    @Test fun `resuming near the final stop still gives all three stop warnings`() {
        val engine = started(); repeat(18) { engine.advance(1000) }; engine.hold()
        engine.begin(); repeat(3) { engine.advance(1000) }
        val events = (1..30).flatMap { engine.advance(100) }
        assertThat(events.filterIsInstance<ProgramEvent.Cue>()).containsExactly(ProgramEvent.Cue(3), ProgramEvent.Cue(2), ProgramEvent.Cue(1), ProgramEvent.Cue(0)).inOrder()
        assertThat(engine.state.status).isEqualTo(ProgramStatus.COMPLETE)
    }
    @Test fun `completion stops target generation and fires once`() {
        val e = started();val events=(1..20).flatMap { e.advance(1000) }
        assertThat(events.filterIsInstance<ProgramEvent.Finished>()).hasSize(1)
        assertThat(e.state.status).isEqualTo(ProgramStatus.COMPLETE)
        assertThat(e.advance(1000)).isEmpty()
        assertThat(e.begin()).isEmpty()
    }
    @Test(expected = IllegalArgumentException::class)
    fun `a delayed frame that could skip a countdown number is rejected`() {
        val engine = ProgramEngine(program())
        engine.begin()
        engine.advance(900)
        // Previously accepted: jumps directly from 3 to 1, skipping the second warning beep.
        engine.advance(1500)
    }
    @Test(expected = IllegalArgumentException::class) fun `a stalled scheduler cannot fast forward past safety cues`() { started().advance(4000) }
    @Test fun `reordering preserves block settings and identity`() {
        val p=program();val reordered=p.moveBlock(0,1)
        assertThat(reordered.blocks).containsExactly(p.blocks[1],p.blocks[0]).inOrder()
        assertThat(reordered.totalSeconds).isEqualTo(p.totalSeconds)
    }
    @Test fun `invalid or non finite targets cannot be run`() {
        assertThat(program().copy(blocks=listOf(ProgramBlock(speedKph=Float.NaN))).validationError()).isNotNull()
        assertThat(program().copy(blocks=listOf(ProgramBlock(durationSeconds=1))).validationError()).isNotNull()
        val b=ProgramBlock()
        assertThat(program().copy(blocks=listOf(b,b)).validationError()).isNotNull()
    }
    @Test fun `unchanged targets do not produce unnecessary change cues`() {
        val p=program().let { it.copy(blocks=it.blocks.map { b -> b.copy(speedKph=5f,inclinePercent=0f) }) }
        val e=ProgramEngine(p);e.begin();repeat(3){e.advance(1000)}
        assertThat((1..10).flatMap{e.advance(1000)}).isEmpty()
    }
}
