package com.nettarion.hyperborea.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nettarion.hyperborea.ui.admin.AdminDrawer
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors
import com.nettarion.hyperborea.ui.util.ExportResultSnackbar
import com.nettarion.hyperborea.ui.util.rememberExportSnackbarState

@Composable
fun DashboardScreen(
    onProfileClick: (profileId: Long) -> Unit,
    onSwitchProfile: () -> Unit,
    onViewRide: (rideId: Long) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var drawerOpen by remember { mutableStateOf(false) }
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

    // Ride FIT export snackbar
    val rideExportResult by viewModel.rideExportResult.collectAsStateWithLifecycle()
    val exportSnackbar = rememberExportSnackbarState(rideExportResult, viewModel::dismissExportResult)

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
                    else onSwitchProfile()
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
            onOpenSettings = {
                drawerOpen = false
                onOpenSettings()
            },
        )

        if (showStopDialog) {
            Dialog(onDismissRequest = { showStopDialog = false }) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text(
                            "Stop workout?",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textHigh,
                        )

                        val buttonShape = RoundedCornerShape(12.dp)
                        val buttonBorder = BorderStroke(1.dp, colors.textLow)
                        val buttonColors = ButtonDefaults.outlinedButtonColors(
                            contentColor = colors.textHigh,
                        )

                        Column(
                            modifier = Modifier.padding(top = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    showStopDialog = false
                                    viewModel.stopBroadcasting(save = true)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = buttonShape,
                                border = buttonBorder,
                                colors = buttonColors,
                            ) {
                                Text("Save ride")
                            }
                            OutlinedButton(
                                onClick = {
                                    showStopDialog = false
                                    viewModel.stopAndView()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = buttonShape,
                                border = buttonBorder,
                                colors = buttonColors,
                            ) {
                                Text("Save & view details")
                            }
                            OutlinedButton(
                                onClick = {
                                    showStopDialog = false
                                    viewModel.stopAndExport()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = buttonShape,
                                border = buttonBorder,
                                colors = buttonColors,
                            ) {
                                Text("Save & export FIT")
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = colors.divider,
                        )

                        TextButton(
                            onClick = {
                                showStopDialog = false
                                viewModel.stopBroadcasting(save = false)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Discard ride", color = MaterialTheme.colorScheme.error)
                        }

                        TextButton(
                            onClick = { showStopDialog = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Cancel", color = colors.textMedium)
                        }
                    }
                }
            }
        }

        // Snackbar for export results
        ExportResultSnackbar(
            state = exportSnackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
