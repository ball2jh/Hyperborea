package com.nettarion.hyperborea.core

sealed interface LicenseState {
    data class Licensed(val expiresAt: Long) : LicenseState  // epoch millis
    data object Unlicensed : LicenseState
    data object Checking : LicenseState
}
