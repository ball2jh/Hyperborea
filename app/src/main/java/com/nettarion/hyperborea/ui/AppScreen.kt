package com.nettarion.hyperborea.ui

sealed interface AppScreen {
    data object Activation : AppScreen
    data object ProfilePicker : AppScreen
    data object Dashboard : AppScreen
    data class ProfileStats(val profileId: Long) : AppScreen
    data class ProfileEdit(val profileId: Long?) : AppScreen
}
