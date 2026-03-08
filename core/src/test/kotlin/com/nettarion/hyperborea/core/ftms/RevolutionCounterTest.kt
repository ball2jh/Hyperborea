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
    fun `wheel event time uses 2048 resolution`() {
        counter.update(exerciseData(speed = 10.0f), 1000L)
        counter.update(exerciseData(speed = 10.0f), 2000L)
        // 2000 * 2048 / 1000 = 4096
        assertThat(counter.lastWheelEventTime).isEqualTo(4096)
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
