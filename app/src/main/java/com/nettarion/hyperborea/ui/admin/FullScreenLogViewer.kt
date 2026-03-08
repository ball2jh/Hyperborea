package com.nettarion.hyperborea.ui.admin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nettarion.hyperborea.core.LogLevel
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors

@Composable
fun FullScreenLogViewer(
    isOpen: Boolean,
    onClose: () -> Unit,
    viewModel: AdminViewModel,
) {
    AnimatedVisibility(
        visible = isOpen,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 4 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 4 }),
    ) {
        val colors = LocalHyperboreaColors.current
        var selectedTab by remember { mutableIntStateOf(0) }
        var selectedLevels by remember {
            mutableStateOf(setOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR))
        }

        val appLogs by viewModel.logEntries.collectAsStateWithLifecycle()
        val systemLogs by viewModel.systemLogEntries.collectAsStateWithLifecycle()
        val supportState by viewModel.supportUploadState.collectAsStateWithLifecycle()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .systemBarsPadding(),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Logs",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.textHigh,
                )
                Spacer(Modifier.weight(1f))

                // Action buttons
                OutlinedButton(onClick = {
                    if (selectedTab == 0) viewModel.clearLogs() else viewModel.clearSystemLogs()
                }) {
                    Text("Clear")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    if (selectedTab == 0) viewModel.exportLogs() else viewModel.exportSystemLogs()
                }) {
                    Text("Export")
                }
                Spacer(Modifier.width(8.dp))
                FullScreenGetHelpButton(supportState, viewModel)
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, "Close", tint = colors.textMedium)
                }
            }

            // Tabs + filters
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Tab row (constrained width so it doesn't push chips off)
                PrimaryTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = colors.textHigh,
                    divider = {},
                    modifier = Modifier.width(200.dp),
                ) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                        Text("App", modifier = Modifier.padding(vertical = 8.dp))
                    }
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                        Text("System", modifier = Modifier.padding(vertical = 8.dp))
                    }
                }

                Spacer(Modifier.width(24.dp))

                // Level filter chips
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LogLevel.entries.forEach { level ->
                        val selected = level in selectedLevels
                        FilterChip(
                            selected = selected,
                            onClick = {
                                selectedLevels = if (selected) selectedLevels - level
                                else selectedLevels + level
                            },
                            label = { Text(level.name.first().toString()) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            }

            HorizontalDivider(color = colors.divider)

            // Log entries — full remaining space
            if (selectedTab == 0) {
                val filtered = appLogs.filter { it.level in selectedLevels }.asReversed()
                LazyColumn(
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        filtered,
                        key = { "${it.timestamp}-${it.tag}-${it.message.hashCode()}" },
                    ) { entry ->
                        LogEntryRow(entry)
                    }
                }
            } else {
                val filtered = systemLogs.filter { it.level in selectedLevels }.asReversed()
                LazyColumn(
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        filtered,
                        key = { "${it.timestamp}-${it.tag}-${it.message.hashCode()}" },
                    ) { entry ->
                        SystemLogEntryRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun FullScreenGetHelpButton(state: SupportUploadState, viewModel: AdminViewModel) {
    val colors = LocalHyperboreaColors.current
    when (state) {
        is SupportUploadState.Idle -> {
            OutlinedButton(onClick = { viewModel.uploadSupport() }) {
                Text("Get Help")
            }
        }
        is SupportUploadState.Uploading -> {
            OutlinedButton(onClick = {}, enabled = false) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
        is SupportUploadState.Success -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = state.code,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.statusActive,
                )
                OutlinedButton(onClick = { viewModel.dismissSupportUpload() }) {
                    Text("Done")
                }
            }
        }
        is SupportUploadState.Error -> {
            OutlinedButton(onClick = { viewModel.uploadSupport() }) {
                Text("Retry")
            }
        }
    }
}
