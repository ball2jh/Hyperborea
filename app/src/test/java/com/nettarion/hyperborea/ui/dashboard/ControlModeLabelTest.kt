package com.nettarion.hyperborea.ui.dashboard

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.model.ExerciseData
import org.junit.Test

class ControlModeLabelTest {

    private fun exerciseData(
        targetPower: Int? = null,
        targetIncline: Float? = null,
        targetResistance: Int? = null,
        targetSpeed: Float? = null,
    ) = ExerciseData(
        power = null,
        cadence = null,
        speed = null,
        resistance = null,
        incline = null,
        heartRate = null,
        distance = null,
        calories = null,
        elapsedTime = 0L,
        targetPower = targetPower,
        targetIncline = targetIncline,
        targetResistance = targetResistance,
        targetSpeed = targetSpeed,
    )

    @Test
    fun `returns null when no targets set`() {
        assertThat(exerciseData().controlModeLabel()).isNull()
    }

    @Test
    fun `returns ERG label when targetPower is set`() {
        assertThat(exerciseData(targetPower = 200).controlModeLabel()).isEqualTo("ERG 200W")
    }

    @Test
    fun `returns SIM label when targetIncline is set`() {
        assertThat(exerciseData(targetIncline = 5.0f).controlModeLabel()).isEqualTo("SIM 5.0%")
    }

    @Test
    fun `returns RES label when targetResistance is set`() {
        assertThat(exerciseData(targetResistance = 15).controlModeLabel()).isEqualTo("RES 15")
    }

    @Test
    fun `returns SPD label when targetSpeed is set`() {
        assertThat(exerciseData(targetSpeed = 28.5f).controlModeLabel()).isEqualTo("SPD 28.5 km/h")
    }

    @Test
    fun `ERG takes priority over SIM`() {
        val label = exerciseData(targetPower = 250, targetIncline = 3.0f).controlModeLabel()
        assertThat(label).isEqualTo("ERG 250W")
    }

    @Test
    fun `SIM takes priority over RES`() {
        val label = exerciseData(targetIncline = 7.5f, targetResistance = 10).controlModeLabel()
        assertThat(label).isEqualTo("SIM 7.5%")
    }
}
