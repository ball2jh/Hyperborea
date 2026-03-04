package com.nettarion.hyperborea.platform.systemlog

import com.nettarion.hyperborea.core.LogLevel
import com.nettarion.hyperborea.core.SystemLogEntry
import com.nettarion.hyperborea.core.SystemLogSource

/**
 * Parses kernel `dmesg` output lines into [SystemLogEntry].
 *
 * Expected format: `[seconds.microseconds] message`
 * Example: `[ 1234.567890] usb 1-1: new high-speed USB device`
 *
 * Converts uptime offset to wall-clock time using [bootTimeMillis].
 */
object DmesgLineParser {

    private val LINE_REGEX = Regex("""\[\s*(\d+\.\d+)\]\s+(.+)""")

    private val ERROR_KEYWORDS = listOf("error", "fail", "panic", "oops", "bug", "fatal")
    private val WARN_KEYWORDS = listOf("warn", "warning", "deprecated")

    fun parse(line: String, bootTimeMillis: Long): SystemLogEntry? {
        val match = LINE_REGEX.matchEntire(line) ?: return null
        val (uptimeStr, message) = match.destructured

        val uptimeSeconds = uptimeStr.toDoubleOrNull() ?: return null
        val timestamp = bootTimeMillis + (uptimeSeconds * 1000).toLong()

        return SystemLogEntry(
            timestamp = timestamp,
            level = inferLevel(message),
            tag = "kernel",
            message = message,
            pid = 0,
            tid = 0,
            source = SystemLogSource.DMESG,
        )
    }

    private fun inferLevel(message: String): LogLevel {
        val lower = message.lowercase()
        return when {
            ERROR_KEYWORDS.any { it in lower } -> LogLevel.ERROR
            WARN_KEYWORDS.any { it in lower } -> LogLevel.WARN
            else -> LogLevel.INFO
        }
    }
}
