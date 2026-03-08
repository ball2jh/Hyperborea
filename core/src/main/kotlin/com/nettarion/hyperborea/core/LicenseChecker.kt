package com.nettarion.hyperborea.core

import kotlinx.coroutines.flow.StateFlow

interface LicenseChecker {
    val state: StateFlow<LicenseState>

    /** Check license status with the server. Updates [state]. When [silent] is true, skips emitting [LicenseState.Checking]. */
    suspend fun check(silent: Boolean = false)

    /** Request a new pairing session. Updates [state] to Pairing on success. */
    suspend fun requestPairing(): PairingSession

    /** Poll the server for pairing status. If linked, saves auth token and calls check(). */
    suspend fun pollPairing(pairingToken: String): PairingStatus

    /** Unlink this device from the account. Clears local credentials and notifies the server. */
    suspend fun unlink()
}

sealed interface PairingSession {
    data class Created(val pairingToken: String, val pairingCode: String, val expiresAt: Long) : PairingSession
    data class Error(val message: String) : PairingSession
}

sealed interface PairingStatus {
    data object Pending : PairingStatus
    data class Linked(val authToken: String) : PairingStatus
    data object Expired : PairingStatus
    data class Error(val message: String) : PairingStatus
}
