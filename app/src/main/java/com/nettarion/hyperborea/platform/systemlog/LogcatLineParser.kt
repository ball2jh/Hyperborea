package com.nettarion.hyperborea.platform.systemlog

import com.nettarion.hyperborea.core.LogLevel
import com.nettarion.hyperborea.core.system.SystemLogEntry
import com.nettarion.hyperborea.core.system.SystemLogSource
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Parses `logcat -v threadtime` output lines into [SystemLogEntry].
 *
 * Expected format: `MM-DD HH:MM:SS.mmm  PID  TID PRIORITY TAG: MESSAGE`
 * Example: `01-15 12:34:56.789  1234  5678 D MyTag  : Hello world`
 */
object LogcatLineParser {

    // MM-DD HH:MM:SS.mmm  PID  TID PRIORITY/TAG: MESSAGE
    private val LINE_REGEX = Regex(
        """(\d{2}-\d{2})\s+(\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s+(.+?)\s*:\s(.*)"""
    )

    private val dateFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    fun parse(line: String): SystemLogEntry? {
        val match = LINE_REGEX.matchEntire(line) ?: return null
        val (monthDay, time, pidStr, tidStr, priority, tag, message) = match.destructured

        val year = Calendar.getInstance().get(Calendar.YEAR)
        val timestamp = try {
            dateFormat.get()!!.parse("$year-$monthDay $time")?.time ?: return null
        } catch (_: Exception) {
            return null
        }

        return SystemLogEntry(
            timestamp = timestamp,
            level = mapPriority(priority[0]),
            tag = tag.trim(),
            message = message,
            pid = pidStr.toIntOrNull() ?: 0,
            tid = tidStr.toIntOrNull() ?: 0,
            source = SystemLogSource.LOGCAT,
        )
    }

    private fun mapPriority(char: Char): LogLevel = when (char) {
        'V', 'D' -> LogLevel.DEBUG
        'I' -> LogLevel.INFO
        'W' -> LogLevel.WARN
        'E', 'F', 'A' -> LogLevel.ERROR
        else -> LogLevel.DEBUG
    }
}
