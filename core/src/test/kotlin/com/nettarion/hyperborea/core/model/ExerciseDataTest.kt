package com.nettarion.hyperborea.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExerciseDataTest {

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
