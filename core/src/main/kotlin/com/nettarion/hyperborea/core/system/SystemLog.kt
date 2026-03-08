package com.nettarion.hyperborea.core.system

import com.nettarion.hyperborea.core.LogLevel

import kotlinx.coroutines.flow.StateFlow

enum class SystemLogSource { LOGCAT, DMESG }

data class SystemLogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val pid: Int,
    val tid: Int,
    val source: SystemLogSource,
)

data class CaptureConfig(
    val logcat: Boolean = true,
    val dmesg: Boolean = false,
    val logcatFilterSpecs: List<String> = emptyList(),
    val logcatBuffers: Set<String> = setOf("main", "system"),
)

sealed interface CaptureState {
    data object Inactive : CaptureState
    data object Starting : CaptureState
    data object Active : CaptureState
    data class Error(val message: String, val cause: Throwable? = null) : CaptureState
}

interface SystemLogCapture {
    val state: StateFlow<CaptureState>
    suspend fun start(config: CaptureConfig = CaptureConfig())
    suspend fun stop()
}

interface SystemLogStore {
    val entries: StateFlow<List<SystemLogEntry>>
    val size: StateFlow<Int>
    fun clear()
    fun export(): String
}
