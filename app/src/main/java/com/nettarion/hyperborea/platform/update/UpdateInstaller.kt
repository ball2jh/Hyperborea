package com.nettarion.hyperborea.platform.update

interface UpdateInstaller {
    suspend fun install(path: String): InstallResult
    suspend fun finalize(path: String)
}

sealed interface InstallResult {
    data object Success : InstallResult
    data class Failed(val reason: String, val cause: Throwable? = null) : InstallResult
}
