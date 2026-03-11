package com.nettarion.hyperborea.core

sealed interface LicenseState {
    data object Licensed : LicenseState
    data object Unlicensed : LicenseState
    data object Checking : LicenseState
    data class Pairing(val pairingToken: String, val pairingCode: String, val expiresAt: Long) : LicenseState
}
