package com.nettarion.hyperborea.core.adapter

sealed interface AdapterState {
    data object Inactive : AdapterState
    data object Activating : AdapterState
    data object Active : AdapterState
    data class Error(val message: String, val cause: Throwable? = null) : AdapterState
}
