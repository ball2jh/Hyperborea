package com.nettarion.hyperborea.core

sealed interface FulfillResult {
    data object Success : FulfillResult
    data class Failed(val reason: String, val cause: Throwable? = null) : FulfillResult
}
