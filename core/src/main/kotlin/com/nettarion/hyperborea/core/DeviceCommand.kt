package com.nettarion.hyperborea.core

sealed interface DeviceCommand {
    data class SetResistance(val level: Int) : DeviceCommand
    data class SetIncline(val percent: Float) : DeviceCommand
}
