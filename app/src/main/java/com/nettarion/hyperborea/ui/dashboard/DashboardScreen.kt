package com.nettarion.hyperborea.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    onProfileClick: (profileId: Long) -> Unit,
    onViewRide: (rideId: Long) -> Unit,
    onUnlinkDevice: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
    adminViewModel: AdminViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var drawerOpen by remember { mutableStateOf(false) }
    var logsExpanded by remember { mutableStateOf(false) }
    var showStopDialog by remember { mutableStateOf(false) }
    val colors = LocalHyperboreaColors.current

    // Post-save navigation (Save & View)
    val postSaveEvent by viewModel.postSaveEvent.collectAsStateWithLifecycle()
    LaunchedEffect(postSaveEvent) {
        when (val event = postSaveEvent) {
            is PostSaveEvent.ViewRide -> {
                viewModel.consumePostSaveEvent()
                onViewRide(event.rideId)
            }
            null -> {}
        }
    }

    // Export result snackbar (admin logs + ride FIT export)
    val adminExportResult by adminViewModel.exportResult.collectAsStateWithLifecycle()
    val rideExportResult by viewModel.rideExportResult.collectAsStateWithLifecycle()
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(adminExportResult) {
        val result = adminExportResult ?: return@LaunchedEffect
        snackbarMessage = if (result.error != null) result.error else "Saved to ${result.filePath}"
        adminViewModel.dismissExportResult()
        delay(4000)
        snackbarMessage = null
    }

    LaunchedEffect(rideExportResult) {
        val result = rideExportResult ?: return@LaunchedEffect
        snackbarMessage = if (result.error != null) result.error else "Exported to ${result.filePath}"
        viewModel.dismissExportResult()
        delay(4000)
        snackbarMessage = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            StatusBar(
                orchestratorState = uiState.orchestratorState,
                broadcasts = uiState.broadcasts,
                exerciseData = uiState.exerciseData,
                profileName = uiState.profileName,
                deviceName = uiState.deviceInfo?.name,
                onStart = viewModel::startBroadcasting,
                onStop = {
                    if (viewModel.currentElapsedSeconds >= 60) {
                        showStopDialog = true
                    } else {
                        viewModel.stopBroadcasting(save = false)
                    }
                },
                onPause = viewModel::pauseBroadcasting,
                onResume = viewModel::resumeBroadcasting,
                onSettingsClick = { drawerOpen = true },
                onProfileClick = {
                    val profileId = viewModel.activeProfileId
                    if (profileId != null) onProfileClick(profileId)
                },
            )
            HorizontalDivider(thickness = 1.dp, color = colors.divider)
            MetricGrid(
                exerciseData = uiState.exerciseData,
                supportedMetrics = uiState.deviceInfo?.supportedMetrics,
                modifier = Modifier.weight(1f),
            )
        }

        AdminDrawer(
            isOpen = drawerOpen,
            onClose = { drawerOpen = false },
            onExpandLogs = {
                drawerOpen = false
                logsExpanded = true
            },
            onUnlinkDevice = {
                drawerOpen = false
                onUnlinkDevice()
            },
            viewModel = adminViewModel,
        )

        FullScreenLogViewer(
            isOpen = logsExpanded,
            onClose = { logsExpanded = false },
            viewModel = adminViewModel,
        )

        if (showStopDialog) {
            AlertDialog(
                onDismissRequest = { showStopDialog = false },
                title = { Text("Stop workout?") },
                text = {
                    Column {
                        Text("What would you like to do with this ride?")
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(onClick = {
                                showStopDialog = false
                                viewModel.stopBroadcasting(save = true)
                            }) { Text("Save") }
                            TextButton(onClick = {
                                showStopDialog = false
                                viewModel.stopAndView()
                            }) { Text("Save & View") }
                            TextButton(onClick = {
                                showStopDialog = false
                                viewModel.stopAndExport()
                            }) { Text("Save & Export") }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = {
                        showStopDialog = false
                        viewModel.stopBroadcasting(save = false)
                    }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                },
            )
        }

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
