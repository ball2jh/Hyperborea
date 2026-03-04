package com.nettarion.hyperborea.platform.systemlog

import android.os.SystemClock
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.CaptureConfig
import com.nettarion.hyperborea.core.CaptureState
import com.nettarion.hyperborea.core.SystemLogCapture
import com.nettarion.hyperborea.core.SystemLogEntry
import com.nettarion.hyperborea.core.SystemLogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * System log capture with a 20,000-entry ring buffer.
 *
 * Implements both [SystemLogCapture] (lifecycle) and [SystemLogStore] (read-side),
 * mirroring the dual-interface pattern of `RingBufferLogStore`.
 *
 * StateFlow updates are throttled to 10Hz (every 100ms) to avoid UI jank
 * from high-volume system log streams.
 */
class RingBufferSystemLogStore(
    private val logger: AppLogger,
    private val scope: CoroutineScope,
    private val processRunner: ProcessRunner = ProcessRunner(),
) : SystemLogCapture, SystemLogStore {

    private val lock = Any()
    private val buffer = ArrayDeque<SystemLogEntry>(MAX_ENTRIES)

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Inactive)
    private val _entries = MutableStateFlow<List<SystemLogEntry>>(emptyList())
    private val _size = MutableStateFlow(0)

    private var captureJob: Job? = null
    private var dirty = false

    override val state: StateFlow<CaptureState> = _state.asStateFlow()
    override val entries: StateFlow<List<SystemLogEntry>> = _entries.asStateFlow()
    override val size: StateFlow<Int> = _size.asStateFlow()

    override suspend fun start(config: CaptureConfig) {
        if (_state.value is CaptureState.Active || _state.value is CaptureState.Starting) return

        _state.value = CaptureState.Starting
        logger.i(TAG, "System log capture starting")

        val bootTimeMillis = System.currentTimeMillis() - SystemClock.elapsedRealtime()

        val flows = buildList {
            if (config.logcat) {
                val cmd = buildLogcatCommand(config)
                add(processRunner.run(cmd).toSystemLogFlow { LogcatLineParser.parse(it) })
            }
            if (config.dmesg) {
                add(processRunner.run("dmesg -w").toSystemLogFlow { DmesgLineParser.parse(it, bootTimeMillis) })
            }
        }

        if (flows.isEmpty()) {
            _state.value = CaptureState.Error("No capture sources enabled")
            logger.w(TAG, "No capture sources enabled in config")
            return
        }

        captureJob = scope.launch {
            // Throttle StateFlow updates to 10Hz
            val updateJob = launch {
                while (isActive) {
                    delay(THROTTLE_INTERVAL_MS)
                    flushIfDirty()
                }
            }

            try {
                _state.value = CaptureState.Active
                logger.i(TAG, "System log capture active")

                flows.merge().collect { entry ->
                    if (entry != null) {
                        addEntry(entry)
                    }
                }

                // All flows completed (process deaths exceeded max restarts)
                _state.value = CaptureState.Error("All capture processes terminated")
                logger.e(TAG, "All capture processes terminated")
            } catch (e: Exception) {
                _state.value = CaptureState.Error(e.message ?: "Unknown error", e)
                logger.e(TAG, "System log capture error: ${e.message}", e)
            } finally {
                updateJob.cancel()
                flushIfDirty()
            }
        }
    }

    override suspend fun stop() {
        if (_state.value is CaptureState.Inactive) return

        captureJob?.cancel()
        captureJob = null
        _state.value = CaptureState.Inactive
        flushIfDirty()
        logger.i(TAG, "System log capture stopped")
    }

    override fun clear() {
        synchronized(lock) {
            buffer.clear()
            dirty = false
            _entries.value = emptyList()
            _size.value = 0
        }
    }

    override fun export(): String {
        val snapshot = synchronized(lock) { buffer.toList() }
        return buildString {
            appendLine("=== Hyperborea System Log ===")
            appendLine("Exported: ${formatTimestamp(System.currentTimeMillis())}")
            appendLine("Entries: ${snapshot.size}")
            appendLine()
            for (entry in snapshot) {
                appendLine("${formatTimestamp(entry.timestamp)} ${entry.source.name} ${entry.level.name.first()}/${entry.tag}[${entry.pid}]: ${entry.message}")
            }
        }
    }

    private fun addEntry(entry: SystemLogEntry) {
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(entry)
            dirty = true
        }
    }

    private fun flushIfDirty() {
        synchronized(lock) {
            if (!dirty) return
            dirty = false
            _entries.value = buffer.toList()
            _size.value = buffer.size
        }
    }

    private fun buildLogcatCommand(config: CaptureConfig): String = buildString {
        append("logcat -v threadtime -T 1")
        for (buf in config.logcatBuffers) {
            append(" -b ").append(buf)
        }
        for (spec in config.logcatFilterSpecs) {
            append(" ").append(spec)
        }
    }

    private fun kotlinx.coroutines.flow.Flow<String>.toSystemLogFlow(
        parser: (String) -> SystemLogEntry?,
    ): kotlinx.coroutines.flow.Flow<SystemLogEntry?> = kotlinx.coroutines.flow.flow {
        collect { line -> emit(parser(line)) }
    }

    private fun formatTimestamp(millis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return sdf.format(Date(millis))
    }

    private companion object {
        const val TAG = "SystemLog"
        const val MAX_ENTRIES = 20_000
        const val THROTTLE_INTERVAL_MS = 100L
    }
}
