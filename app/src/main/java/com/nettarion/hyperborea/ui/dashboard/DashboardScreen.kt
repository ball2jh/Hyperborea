package com.nettarion.hyperborea.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nettarion.hyperborea.core.orchestration.OrchestratorState
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
    pendingStopDialog: androidx.compose.runtime.MutableState<Boolean> = remember { mutableStateOf(false) },
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var drawerOpen by remember { mutableStateOf(false) }
    var showStopDialog by remember { mutableStateOf(false) }
    val colors = LocalHyperboreaColors.current

    // Auto-show stop dialog when triggered from overlay
    LaunchedEffect(pendingStopDialog.value) {
        if (pendingStopDialog.value) {
            showStopDialog = true
            pendingStopDialog.value = false
        }
    }

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
                useImperial = uiState.useImperial,
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
                useImperial = uiState.useImperial,
                modifier = Modifier.weight(1f),
            )
        }

        // Treadmill safety overlay: equipment is armed (broadcasts live), MCU is parked in
        // WARM_UP waiting for the physical Start key. The compact StatusBar status text is too
        // subtle at 1920×1080 arm's-length; this is the visible cue.
        val awaiting = uiState.orchestratorState as? OrchestratorState.AwaitingConsoleStart
        if (awaiting != null) {
            ConsoleStartPrompt(
                message = awaiting.message,
                modifier = Modifier.align(Alignment.Center),
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

/**
 * Centered overlay shown while the orchestrator is in
 * [OrchestratorState.AwaitingConsoleStart] (treadmill armed in WARM_UP, MCU gating belt motion
 * on the physical Start key). The StatusBar's 48 dp status indicator is too small to register
 * at the console's 1920×1080 arm's-length viewing distance; this card is the visible cue.
 */
@Composable
private fun ConsoleStartPrompt(message: String, modifier: Modifier = Modifier) {
    val colors = LocalHyperboreaColors.current
    Card(
        modifier = modifier
            .padding(32.dp)
            .widthIn(max = 720.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "▶",
                fontSize = 96.sp,
                color = colors.accentWarm,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                message,
                style = MaterialTheme.typography.headlineMedium,
                color = colors.textHigh,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "The belt will start when you press the physical Start key. Broadcasts are already live — pair Zwift now if you haven't.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
