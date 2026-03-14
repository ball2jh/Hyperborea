package com.nettarion.hyperborea.ui

sealed interface AppScreen {
    data class ProfilePicker(val autoSelect: Boolean = true) : AppScreen
    data object Dashboard : AppScreen
    data class ProfileStats(val profileId: Long) : AppScreen
    data class ProfileEdit(val profileId: Long?) : AppScreen
    data class RideDetail(val rideId: Long) : AppScreen
    data class DeviceConfig(val modelNumber: Int?) : AppScreen
    data object Settings : AppScreen
}
