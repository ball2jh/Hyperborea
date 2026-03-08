package com.nettarion.hyperborea.core

import kotlinx.coroutines.flow.StateFlow

interface LicenseChecker {
    val state: StateFlow<LicenseState>

    /** Check license status with the server. Updates [state]. */
    suspend fun check()

    /** Link this device using a 6-digit code. Returns auth token on success. */
    suspend fun linkWithCode(code: String): LinkResult

    /** Link this device using a QR token. Returns auth token on success. */
    suspend fun linkWithQrToken(qrToken: String): LinkResult
}

sealed interface LinkResult {
    data class Success(val authToken: String) : LinkResult
    data class Error(val message: String) : LinkResult
}
