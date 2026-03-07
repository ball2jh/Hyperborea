package com.nettarion.hyperborea.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nettarion.hyperborea.ui.admin.AdminDrawer
import com.nettarion.hyperborea.ui.admin.AdminViewModel
import com.nettarion.hyperborea.ui.admin.FullScreenLogViewer
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel(),
    adminViewModel: AdminViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var drawerOpen by remember { mutableStateOf(false) }
    var logsExpanded by remember { mutableStateOf(false) }
    val colors = LocalHyperboreaColors.current

    // Export result snackbar
    val exportResult by adminViewModel.exportResult.collectAsStateWithLifecycle()
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(exportResult) {
        val result = exportResult ?: return@LaunchedEffect
        snackbarMessage = if (result.error != null) {
            result.error
        } else {
            "Saved to ${result.filePath}"
        }
        adminViewModel.dismissExportResult()
        delay(4000)
        snackbarMessage = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            StatusBar(
                orchestratorState = uiState.orchestratorState,
                broadcasts = uiState.broadcasts,
                exerciseData = uiState.exerciseData,
                onStart = viewModel::startBroadcasting,
                onStop = viewModel::stopBroadcasting,
            )
            HorizontalDivider(thickness = 1.dp, color = colors.divider)
            MetricGrid(
                exerciseData = uiState.exerciseData,
                supportedMetrics = uiState.deviceInfo?.supportedMetrics,
                modifier = Modifier.weight(1f),
            )
            HorizontalDivider(thickness = 1.dp, color = colors.divider)
            BottomBar(onSettingsClick = { drawerOpen = true })
        }

        AdminDrawer(
            isOpen = drawerOpen,
            onClose = { drawerOpen = false },
            onExpandLogs = {
                drawerOpen = false
                logsExpanded = true
            },
            viewModel = adminViewModel,
        )

        FullScreenLogViewer(
            isOpen = logsExpanded,
            onClose = { logsExpanded = false },
            viewModel = adminViewModel,
        )

        // Snackbar for export results
        snackbarMessage?.let { message ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                containerColor = colors.divider,
                contentColor = colors.textHigh,
            ) {
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
