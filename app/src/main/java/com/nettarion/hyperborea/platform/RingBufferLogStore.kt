package com.nettarion.hyperborea.platform

import android.util.Log
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.LogEntry
import com.nettarion.hyperborea.core.LogLevel
import com.nettarion.hyperborea.core.LogStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RingBufferLogStore @Inject constructor() : AppLogger, LogStore {

    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    private val _size = MutableStateFlow(0)

    override val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()
    override val size: StateFlow<Int> = _size.asStateFlow()

    // --- AppLogger ---

    override fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    override fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    override fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    override fun e(tag: String, message: String, throwable: Throwable?) =
        log(LogLevel.ERROR, tag, message, throwable)

    private fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        val logcatTag = "Hyperborea.$tag"
        when (level) {
            LogLevel.DEBUG -> Log.d(logcatTag, message, throwable)
            LogLevel.INFO -> Log.i(logcatTag, message, throwable)
            LogLevel.WARN -> Log.w(logcatTag, message, throwable)
            LogLevel.ERROR -> Log.e(logcatTag, message, throwable)
        }

        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            throwable = throwable?.stackTraceToString(),
        )
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(entry)
            _entries.value = buffer.toList()
            _size.value = buffer.size
        }
    }

    // --- LogStore ---

    override fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
            _size.value = 0
        }
    }

    override fun export(): String {
        val snapshot = synchronized(lock) { buffer.toList() }
        return buildString {
            appendLine("=== Hyperborea Diagnostic Log ===")
            appendLine("Exported: ${formatTimestamp(System.currentTimeMillis())}")
            appendLine("Entries: ${snapshot.size}")
            appendLine()
            for (entry in snapshot) {
                appendLine("${formatTimestamp(entry.timestamp)} ${entry.level.name.first()}/${entry.tag}: ${entry.message}")
                entry.throwable?.let { appendLine(it) }
            }
        }
    }

    private fun formatTimestamp(millis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return sdf.format(Date(millis))
    }

    private companion object {
        const val MAX_ENTRIES = 5_000
    }
}
