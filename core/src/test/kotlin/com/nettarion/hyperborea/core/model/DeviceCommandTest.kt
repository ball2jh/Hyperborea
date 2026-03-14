package com.nettarion.hyperborea.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceCommandTest {

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
            DeviceCommand.CalibrateIncline,
            DeviceCommand.SetFanSpeed(level = 2),
            DeviceCommand.SetVolume(level = 5),
            DeviceCommand.SetGear(gear = 3),
            DeviceCommand.SetDistanceGoal(meters = 5000),
            DeviceCommand.SetWarmupTimeout(seconds = 300),
            DeviceCommand.SetCooldownTimeout(seconds = 180),
            DeviceCommand.SetPauseTimeout(seconds = 60),
            DeviceCommand.SetWarmUpMode(enable = true),
            DeviceCommand.SetCoolDownMode(enable = true),
            DeviceCommand.SetErgMode(enable = true),
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
                DeviceCommand.CalibrateIncline -> "calibrateIncline"
                is DeviceCommand.SetFanSpeed -> "fanSpeed:${cmd.level}"
                is DeviceCommand.SetVolume -> "volume:${cmd.level}"
                is DeviceCommand.SetGear -> "gear:${cmd.gear}"
                is DeviceCommand.SetDistanceGoal -> "distanceGoal:${cmd.meters}"
                is DeviceCommand.SetWarmupTimeout -> "warmupTimeout:${cmd.seconds}"
                is DeviceCommand.SetCooldownTimeout -> "cooldownTimeout:${cmd.seconds}"
                is DeviceCommand.SetPauseTimeout -> "pauseTimeout:${cmd.seconds}"
                is DeviceCommand.SetWarmUpMode -> "warmUpMode:${cmd.enable}"
                is DeviceCommand.SetCoolDownMode -> "coolDownMode:${cmd.enable}"
                is DeviceCommand.SetErgMode -> "ergMode:${cmd.enable}"
            }
        }
        assertThat(results).containsExactly(
            "resistance:5", "incline:2.0", "speed:25.0", "power:200",
            "adjustIncline:true", "adjustSpeed:false", "pause", "resume",
            "calibrateIncline", "fanSpeed:2",
            "volume:5", "gear:3", "distanceGoal:5000",
            "warmupTimeout:300", "cooldownTimeout:180", "pauseTimeout:60",
            "warmUpMode:true", "coolDownMode:true", "ergMode:true",
        ).inOrder()
    }
}
