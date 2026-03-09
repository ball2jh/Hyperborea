package com.nettarion.hyperborea.ui.admin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
                GetHelpButton(supportState, viewModel)
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
                LogTabRow(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    containerColor = MaterialTheme.colorScheme.background,
                    modifier = Modifier.width(200.dp),
                )

                Spacer(Modifier.width(24.dp))

                LogLevelFilterChips(
                    selectedLevels = selectedLevels,
                    onToggleLevel = { level ->
                        selectedLevels = if (level in selectedLevels) selectedLevels - level
                        else selectedLevels + level
                    },
                )
            }

            HorizontalDivider(color = colors.divider)

            // Log entries — full remaining space
            LogList(
                selectedTab = selectedTab,
                appLogs = appLogs,
                systemLogs = systemLogs,
                selectedLevels = selectedLevels,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
