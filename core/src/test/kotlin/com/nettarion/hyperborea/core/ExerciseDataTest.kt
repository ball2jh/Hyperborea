package com.nettarion.hyperborea.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExerciseDataTest {

    @Test
    fun `default exercise data has all nullable fields null`() {
        val data = ExerciseData(
            power = null,
            cadence = null,
            speed = null,
            resistance = null,
            incline = null,
            heartRate = null,
            distance = null,
            calories = null,
            elapsedTime = 0L,
        )
        assertThat(data.power).isNull()
        assertThat(data.cadence).isNull()
        assertThat(data.speed).isNull()
        assertThat(data.resistance).isNull()
        assertThat(data.incline).isNull()
        assertThat(data.heartRate).isNull()
        assertThat(data.distance).isNull()
        assertThat(data.calories).isNull()
        assertThat(data.elapsedTime).isEqualTo(0L)
    }

    @Test
    fun `exercise data with all fields populated`() {
        val data = ExerciseData(
            power = 200,
            cadence = 90,
            speed = 25.5f,
            resistance = 10,
            incline = 3.0f,
            heartRate = 150,
            distance = 5.2f,
            calories = 300,
            elapsedTime = 1800L,
        )
        assertThat(data.power).isEqualTo(200)
        assertThat(data.cadence).isEqualTo(90)
        assertThat(data.speed).isEqualTo(25.5f)
        assertThat(data.resistance).isEqualTo(10)
        assertThat(data.incline).isEqualTo(3.0f)
        assertThat(data.heartRate).isEqualTo(150)
        assertThat(data.distance).isEqualTo(5.2f)
        assertThat(data.calories).isEqualTo(300)
        assertThat(data.elapsedTime).isEqualTo(1800L)
    }

    @Test
    fun `data class equality`() {
        val a = ExerciseData(100, 80, 20.0f, 5, 1.0f, 120, 2.0f, 100, 600L)
        val b = ExerciseData(100, 80, 20.0f, 5, 1.0f, 120, 2.0f, 100, 600L)
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `data class inequality on different fields`() {
        val base = ExerciseData(100, 80, 20.0f, 5, 1.0f, 120, 2.0f, 100, 600L)
        assertThat(base).isNotEqualTo(base.copy(power = 200))
        assertThat(base).isNotEqualTo(base.copy(elapsedTime = 999L))
    }

    @Test
    fun `copy preserves unchanged fields`() {
        val original = ExerciseData(100, 80, 20.0f, 5, 1.0f, 120, 2.0f, 100, 600L)
        val copied = original.copy(power = 250)
        assertThat(copied.power).isEqualTo(250)
        assertThat(copied.cadence).isEqualTo(original.cadence)
        assertThat(copied.speed).isEqualTo(original.speed)
        assertThat(copied.elapsedTime).isEqualTo(original.elapsedTime)
    }

    @Test
    fun `destructuring declaration`() {
        val data = ExerciseData(200, 90, 25.0f, 10, 3.0f, 150, 5.0f, 300, 1800L)
        val (power, cadence, speed, resistance, incline, heartRate, distance, calories, elapsed) = data
        assertThat(power).isEqualTo(200)
        assertThat(cadence).isEqualTo(90)
        assertThat(speed).isEqualTo(25.0f)
        assertThat(resistance).isEqualTo(10)
        assertThat(incline).isEqualTo(3.0f)
        assertThat(heartRate).isEqualTo(150)
        assertThat(distance).isEqualTo(5.0f)
        assertThat(calories).isEqualTo(300)
        assertThat(elapsed).isEqualTo(1800L)
    }
}
