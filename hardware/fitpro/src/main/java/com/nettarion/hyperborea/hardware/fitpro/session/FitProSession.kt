package com.nettarion.hyperborea.hardware.fitpro.session

import com.nettarion.hyperborea.core.DeviceCommand
import com.nettarion.hyperborea.core.DeviceIdentity
import com.nettarion.hyperborea.core.ExerciseData
import kotlinx.coroutines.flow.StateFlow

interface FitProSession {
    val exerciseData: StateFlow<ExerciseData?>
    val deviceIdentity: StateFlow<DeviceIdentity?>
    val sessionState: StateFlow<SessionState>
    suspend fun start()
    suspend fun stop()
    suspend fun writeFeature(command: DeviceCommand)
}

sealed interface SessionState {
    data object Connecting : SessionState
    data object Handshaking : SessionState
    data object Streaming : SessionState
    data object Disconnected : SessionState
    data class Error(val message: String, val cause: Throwable? = null) : SessionState
}
