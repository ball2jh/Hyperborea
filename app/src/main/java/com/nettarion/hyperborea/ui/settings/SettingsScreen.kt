package com.nettarion.hyperborea.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nettarion.hyperborea.ui.admin.AdminViewModel
import com.nettarion.hyperborea.ui.admin.FullScreenLogViewer
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors
import com.nettarion.hyperborea.ui.util.ExportResultSnackbar
import com.nettarion.hyperborea.ui.util.rememberExportSnackbarState

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onUnlinkDevice: () -> Unit,
    onConfigureDevice: (Int?) -> Unit,
    adminViewModel: AdminViewModel = hiltViewModel(),
) {
    val colors = LocalHyperboreaColors.current
    var selectedSection by remember { mutableStateOf(SettingsSection.Sensors) }
    var logsExpanded by remember { mutableStateOf(false) }

    // Export result snackbar
    val adminExportResult by adminViewModel.exportResult.collectAsStateWithLifecycle()
    val exportSnackbar = rememberExportSnackbarState(adminExportResult, adminViewModel::dismissExportResult)

    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding(),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Sidebar
            Column(
                modifier = Modifier
                    .width(220.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(top = 16.dp),
            ) {
                // Back button
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Text(
                        text = "\u2190 Settings",
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.textHigh,
                    )
                }
                Spacer(Modifier.height(16.dp))

                SettingsSection.entries.forEach { section ->
                    val selected = section == selectedSection
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedSection = section }
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else androidx.compose.ui.graphics.Color.Transparent,
                            )
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = section.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) MaterialTheme.colorScheme.primary else colors.textHigh,
                        )
                    }
                }
            }

            VerticalDivider(color = colors.divider)

            // Content — System uses weight for logs to fill space; others scroll
            when (selectedSection) {
                SettingsSection.System -> Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(24.dp),
                ) {
                    SystemSettingsContent(
                        adminViewModel = adminViewModel,
                        onExpandLogs = { logsExpanded = true },
                    )
                }
                else -> Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                ) {
                    when (selectedSection) {
                        SettingsSection.Sensors -> SensorSettingsContent()
                        SettingsSection.Device -> DeviceSettingsContent(
                            adminViewModel = adminViewModel,
                            onConfigureDevice = onConfigureDevice,
                            onUnlinkDevice = onUnlinkDevice,
                        )
                        SettingsSection.About -> AboutSettingsContent(
                            adminViewModel = adminViewModel,
                        )
                        else -> {}
                    }
                }
            }
        }

        FullScreenLogViewer(
            isOpen = logsExpanded,
            onClose = { logsExpanded = false },
            viewModel = adminViewModel,
        )

        // Snackbar for export results
        ExportResultSnackbar(
            state = exportSnackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
