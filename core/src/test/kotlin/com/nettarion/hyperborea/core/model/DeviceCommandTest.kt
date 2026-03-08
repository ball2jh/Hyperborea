package com.nettarion.hyperborea.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceCommandTest {

    @Test
    fun `SetResistance is a DeviceCommand`() {
        val cmd: DeviceCommand = DeviceCommand.SetResistance(level = 10)
        assertThat(cmd).isInstanceOf(DeviceCommand.SetResistance::class.java)
    }

    @Test
    fun `SetIncline is a DeviceCommand`() {
        val cmd: DeviceCommand = DeviceCommand.SetIncline(percent = 5.0f)
        assertThat(cmd).isInstanceOf(DeviceCommand.SetIncline::class.java)
    }

    @Test
    fun `SetResistance data class equality`() {
        val a = DeviceCommand.SetResistance(level = 10)
        val b = DeviceCommand.SetResistance(level = 10)
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `SetResistance inequality on different level`() {
        val a = DeviceCommand.SetResistance(level = 10)
        val b = DeviceCommand.SetResistance(level = 20)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `SetIncline data class equality`() {
        val a = DeviceCommand.SetIncline(percent = 3.5f)
        val b = DeviceCommand.SetIncline(percent = 3.5f)
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `SetIncline inequality on different percent`() {
        val a = DeviceCommand.SetIncline(percent = 3.5f)
        val b = DeviceCommand.SetIncline(percent = 7.0f)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `when expression is exhaustive over sealed variants`() {
        val commands: List<DeviceCommand> = listOf(
            DeviceCommand.SetResistance(level = 5),
            DeviceCommand.SetIncline(percent = 2.0f),
            DeviceCommand.SetTargetSpeed(kph = 25.0f),
            DeviceCommand.SetTargetPower(watts = 200),
            DeviceCommand.AdjustIncline(increase = true),
            DeviceCommand.AdjustSpeed(increase = false),
            DeviceCommand.PauseWorkout,
            DeviceCommand.ResumeWorkout,
        )
        val results = commands.map { cmd ->
            when (cmd) {
                is DeviceCommand.SetResistance -> "resistance:${cmd.level}"
                is DeviceCommand.SetIncline -> "incline:${cmd.percent}"
                is DeviceCommand.SetTargetSpeed -> "speed:${cmd.kph}"
                is DeviceCommand.SetTargetPower -> "power:${cmd.watts}"
                is DeviceCommand.AdjustIncline -> "adjustIncline:${cmd.increase}"
                is DeviceCommand.AdjustSpeed -> "adjustSpeed:${cmd.increase}"
                DeviceCommand.PauseWorkout -> "pause"
                DeviceCommand.ResumeWorkout -> "resume"
            }
        }
        assertThat(results).containsExactly(
            "resistance:5", "incline:2.0", "speed:25.0", "power:200",
            "adjustIncline:true", "adjustSpeed:false", "pause", "resume",
        ).inOrder()
    }
}
