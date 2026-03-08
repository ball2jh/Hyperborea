package com.nettarion.hyperborea.core.model

sealed interface DeviceCommand {
    data class SetResistance(val level: Int) : DeviceCommand
    data class SetIncline(val percent: Float) : DeviceCommand
    data class SetTargetSpeed(val kph: Float) : DeviceCommand
    data class SetTargetPower(val watts: Int) : DeviceCommand
    data class AdjustIncline(val increase: Boolean) : DeviceCommand
    data class AdjustSpeed(val increase: Boolean) : DeviceCommand
    data object PauseWorkout : DeviceCommand
    data object ResumeWorkout : DeviceCommand
}
