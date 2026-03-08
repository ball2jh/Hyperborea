package com.nettarion.hyperborea.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.nettarion.hyperborea.core.system.ComponentType
import com.nettarion.hyperborea.core.LogEntry
import com.nettarion.hyperborea.core.LogStore
import com.nettarion.hyperborea.core.system.SystemLogEntry
import com.nettarion.hyperborea.core.system.SystemLogStore
import com.nettarion.hyperborea.core.system.SystemSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LogExporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val logStore: LogStore,
    private val systemLogStore: SystemLogStore,
) {
    fun shareLog(activity: Activity) {
        val text = logStore.export()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Hyperborea Diagnostic Log")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        activity.startActivity(Intent.createChooser(intent, "Share diagnostic log"))
    }

    fun exportToFile(): File {
        val text = logStore.export()
        val dir = logsDir()
        val timestamp = formatFileTimestamp()
        val file = File(dir, "hyperborea_$timestamp.log")
        file.writeText(text)
        return file
    }

    fun exportSystemLog(): File {
        val text = systemLogStore.export()
        val dir = logsDir()
        val timestamp = formatFileTimestamp()
        val file = File(dir, "system_$timestamp.log")
        file.writeText(text)
        return file
    }

    fun exportCombined(): File {
        val appEntries = logStore.entries.value
        val sysEntries = systemLogStore.entries.value
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

        val merged = buildString {
            appendLine("=== Hyperborea Combined Log ===")
            appendLine("Exported: ${sdf.format(Date())}")
            appendLine("App entries: ${appEntries.size}, System entries: ${sysEntries.size}")
            appendLine()

            data class TimestampedLine(val timestamp: Long, val line: String)

            val appLines = appEntries.map { entry ->
                val throwableSuffix = entry.throwable?.let { "\n$it" } ?: ""
                TimestampedLine(
                    entry.timestamp,
                    "${sdf.format(Date(entry.timestamp))} APP ${entry.level.name.first()}/${entry.tag}: ${entry.message}$throwableSuffix",
                )
            }
            val sysLines = sysEntries.map { entry ->
                TimestampedLine(
                    entry.timestamp,
                    "${sdf.format(Date(entry.timestamp))} ${entry.source.name} ${entry.level.name.first()}/${entry.tag}[${entry.pid}]: ${entry.message}",
                )
            }

            (appLines + sysLines)
                .sortedBy { it.timestamp }
                .forEach { appendLine(it.line) }
        }

        val dir = logsDir()
        val timestamp = formatFileTimestamp()
        val file = File(dir, "combined_$timestamp.log")
        file.writeText(merged)
        return file
    }

    fun exportComponents(snapshot: SystemSnapshot): File {
        val byType = snapshot.components
            .sortedWith(compareBy({ it.type }, { it.packageName }, { it.className }))
            .groupBy { it.type }

        val text = buildString {
            appendLine("=== Hyperborea Component Dump ===")
            appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(snapshot.timestamp))}")
            appendLine("Total: ${snapshot.components.size}")
            appendLine()

            for (type in ComponentType.entries) {
                val components = byType[type] ?: continue
                appendLine("--- ${type.name} (${components.size}) ---")
                for (comp in components) {
                    appendLine("  ${comp.packageName}/${comp.className} [${comp.state}]")
                }
                appendLine()
            }
        }

        val dir = logsDir()
        val file = File(dir, "components_${formatFileTimestamp()}.txt")
        file.writeText(text)
        return file
    }

    private fun logsDir(): File {
        val dir = File(context.getExternalFilesDir(null), "logs")
        dir.mkdirs()
        return dir
    }

    private fun formatFileTimestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}
