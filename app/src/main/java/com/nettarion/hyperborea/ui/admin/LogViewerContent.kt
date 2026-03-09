package com.nettarion.hyperborea.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nettarion.hyperborea.core.LogEntry
import com.nettarion.hyperborea.core.LogLevel
import com.nettarion.hyperborea.core.system.SystemLogEntry
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors

@Composable
fun LogTabRow(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
) {
    val colors = LocalHyperboreaColors.current
    PrimaryTabRow(
        selectedTabIndex = selectedTab,
        containerColor = containerColor,
        contentColor = colors.textHigh,
        divider = {},
        modifier = modifier,
    ) {
        Tab(selected = selectedTab == 0, onClick = { onTabSelected(0) }) {
            Text("App", modifier = Modifier.padding(vertical = 8.dp))
        }
        Tab(selected = selectedTab == 1, onClick = { onTabSelected(1) }) {
            Text("System", modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}

@Composable
fun LogLevelFilterChips(
    selectedLevels: Set<LogLevel>,
    onToggleLevel: (LogLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LogLevel.entries.forEach { level ->
            val selected = level in selectedLevels
            FilterChip(
                selected = selected,
                onClick = { onToggleLevel(level) },
                label = { Text(level.name.first().toString()) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}

@Composable
fun LogList(
    selectedTab: Int,
    appLogs: List<LogEntry>,
    systemLogs: List<SystemLogEntry>,
    selectedLevels: Set<LogLevel>,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        if (selectedTab == 0) {
            val filtered = appLogs.filter { it.level in selectedLevels }.asReversed()
            LazyColumn(reverseLayout = true) {
                items(filtered, key = { "${it.timestamp}-${it.tag}-${it.message.hashCode()}" }) { entry ->
                    LogEntryRow(entry)
                }
            }
        } else {
            val filtered = systemLogs.filter { it.level in selectedLevels }.asReversed()
            LazyColumn(reverseLayout = true) {
                items(filtered, key = { "${it.timestamp}-${it.tag}-${it.message.hashCode()}" }) { entry ->
                    SystemLogEntryRow(entry)
                }
            }
        }
    }
}
