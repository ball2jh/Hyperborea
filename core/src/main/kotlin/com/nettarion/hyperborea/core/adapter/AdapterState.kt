package com.nettarion.hyperborea.core.adapter

sealed interface AdapterState {
    data object Inactive : AdapterState
    data object Activating : AdapterState
    data object Active : AdapterState
    data class Error(val message: String, val cause: Throwable? = null) : AdapterState
}

/** Stable human-readable label for logs and diagnostics — never derived from class names. */
fun AdapterState.describe(): String = when (this) {
    AdapterState.Inactive -> "Inactive"
    AdapterState.Activating -> "Activating"
    AdapterState.Active -> "Active"
    is AdapterState.Error -> "Error: $message"
}
