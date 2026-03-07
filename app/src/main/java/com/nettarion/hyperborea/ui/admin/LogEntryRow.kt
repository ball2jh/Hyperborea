package com.nettarion.hyperborea.ui.admin

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nettarion.hyperborea.core.LogEntry
import com.nettarion.hyperborea.core.LogLevel
import com.nettarion.hyperborea.core.SystemLogEntry
import com.nettarion.hyperborea.ui.theme.HyperboreaColors
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors
import com.nettarion.hyperborea.ui.theme.LogTextStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

@Composable
fun LogEntryRow(entry: LogEntry, modifier: Modifier = Modifier) {
    val colors = LocalHyperboreaColors.current
    val levelColor = entry.level.color(colors)
    val levelChar = entry.level.char()
    val time = timeFormat.format(Date(entry.timestamp))

    Text(
        text = "$time  $levelChar  ${entry.tag}  ${entry.message}",
        style = LogTextStyle,
        color = levelColor,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        maxLines = 3,
    )
}

@Composable
fun SystemLogEntryRow(entry: SystemLogEntry, modifier: Modifier = Modifier) {
    val colors = LocalHyperboreaColors.current
    val levelColor = entry.level.color(colors)
    val levelChar = entry.level.char()
    val time = timeFormat.format(Date(entry.timestamp))

    Text(
        text = "$time  $levelChar  ${entry.tag}  ${entry.message}",
        style = LogTextStyle,
        color = levelColor,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        maxLines = 3,
    )
}

private fun LogLevel.char(): String = when (this) {
    LogLevel.DEBUG -> "D"
    LogLevel.INFO -> "I"
    LogLevel.WARN -> "W"
    LogLevel.ERROR -> "E"
}

private fun LogLevel.color(colors: HyperboreaColors): Color = when (this) {
    LogLevel.DEBUG -> colors.textLow
    LogLevel.INFO -> colors.textMedium
    LogLevel.WARN -> colors.accentWarm
    LogLevel.ERROR -> colors.statusError
}
