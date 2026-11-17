package com.nettarion.hyperborea.core.ftms

import com.nettarion.hyperborea.core.model.ExerciseData

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class RevolutionCounterTest {

    private lateinit var counter: RevolutionCounter

    private fun exerciseData(
        speed: Float? = null,
        cadence: Int? = null,
    ) = ExerciseData(
        power = null,
        cadence = cadence,
        speed = speed,
        resistance = null,
        incline = null,
        heartRate = null,
        distance = null,
        calories = null,
        elapsedTime = 0L,
    )

    @Before
    fun setUp() {
        counter = RevolutionCounter()
    }

    @Test
    fun `first update records timestamp but does not accumulate`() {
        counter.update(exerciseData(speed = 30.0f, cadence = 90), 1000L)
        assertThat(counter.cumulativeWheelRevs).isEqualTo(0)
        assertThat(counter.cumulativeCrankRevs).isEqualTo(0)
    }

    @Test
    fun `second update accumulates wheel revolutions from speed`() {
        counter.update(exerciseData(speed = 30.0f), 1000L)
        // 30 km/h = 8.333 m/s, 1 second delta, 8.333 / 2.096 = ~3.97 revs → 3 (truncated)
        counter.update(exerciseData(speed = 30.0f), 2000L)
        assertThat(counter.cumulativeWheelRevs).isEqualTo(3)
    }

    @Test
    fun `second update accumulates crank revolutions from cadence`() {
        counter.update(exerciseData(cadence = 60), 1000L)
        // 60 rpm, 1 second = 1.0 rev → 1
        counter.update(exerciseData(cadence = 60), 2000L)
        assertThat(counter.cumulativeCrankRevs).isEqualTo(1)
    }

    @Test
    fun `null speed and cadence do not accumulate`() {
        counter.update(exerciseData(), 1000L)
        counter.update(exerciseData(), 2000L)
        assertThat(counter.cumulativeWheelRevs).isEqualTo(0)
        assertThat(counter.cumulativeCrankRevs).isEqualTo(0)
    }

    @Test
    fun `wheel event time uses 2048 resolution at the last whole revolution`() {
        counter.update(exerciseData(speed = 10.0f), 1000L)
        counter.update(exerciseData(speed = 10.0f), 2000L)
        // 10 km/h over 1 s = 1.325 revs → the 1st whole rev completed 0.325/1.325 s before 2000 ms
        val revs = (10.0 / 3.6) / 2.096
        val expected = Math.round((2000 - ((revs - 1) / revs) * 1000) * 2048 / 1000).toInt()
        assertThat(counter.cumulativeWheelRevs).isEqualTo(1)
        assertThat(counter.lastWheelEventTime).isEqualTo(expected)
    }

    @Test
    fun `event time does not advance when no whole revolution completed`() {
        counter.update(exerciseData(speed = 10.0f, cadence = 30), 1000L)
        counter.update(exerciseData(speed = 10.0f, cadence = 30), 1100L) // 0.05 crank rev, 0.13 wheel rev
        assertThat(counter.lastCrankEventTime).isEqualTo(0)
        assertThat(counter.lastWheelEventTime).isEqualTo(0)
    }

    @Test
    fun `crank event time marks the moment the revolution completed, not the sample time`() {
        counter.update(exerciseData(cadence = 60), 1000L)
        counter.update(exerciseData(cadence = 60), 1500L) // 0.5 rev — no event
        assertThat(counter.lastCrankEventTime).isEqualTo(0)
        counter.update(exerciseData(cadence = 60), 2250L) // 1.25 revs total — rev 1 completed at 2000 ms
        assertThat(counter.cumulativeCrankRevs).isEqualTo(1)
        assertThat(counter.lastCrankEventTime).isEqualTo(2048)
    }

    @Test
    fun `crank event time uses 1024 resolution`() {
        counter.update(exerciseData(cadence = 60), 1000L)
        counter.update(exerciseData(cadence = 60), 2000L)
        // 2000 * 1024 / 1000 = 2048
        assertThat(counter.lastCrankEventTime).isEqualTo(2048)
    }

    @Test
    fun `reset clears all counters`() {
        counter.update(exerciseData(speed = 30.0f, cadence = 90), 1000L)
        counter.update(exerciseData(speed = 30.0f, cadence = 90), 2000L)
        counter.reset()
        assertThat(counter.cumulativeWheelRevs).isEqualTo(0)
        assertThat(counter.cumulativeCrankRevs).isEqualTo(0)
        assertThat(counter.lastWheelEventTime).isEqualTo(0)
        assertThat(counter.lastCrankEventTime).isEqualTo(0)
    }

    @Test
    fun `revolutions accumulate across multiple updates`() {
        counter.update(exerciseData(cadence = 120), 0L)
        // 120 rpm = 2 rev/s, over 3 seconds = 6 revs
        counter.update(exerciseData(cadence = 120), 1000L)
        counter.update(exerciseData(cadence = 120), 2000L)
        counter.update(exerciseData(cadence = 120), 3000L)
        assertThat(counter.cumulativeCrankRevs).isEqualTo(6)
    }
}
