package com.nettarion.hyperborea.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import com.nettarion.hyperborea.core.LicenseState
import com.nettarion.hyperborea.ui.admin.AdminDrawer
import com.nettarion.hyperborea.ui.admin.AdminViewModel
import com.nettarion.hyperborea.ui.admin.FullScreenLogViewer
import com.nettarion.hyperborea.ui.license.PairingScreen
import com.nettarion.hyperborea.ui.license.UnlicensedScreen
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(
    onProfileClick: (profileId: Long) -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
    adminViewModel: AdminViewModel = hiltViewModel(),
) {
    val licenseState by viewModel.licenseState.collectAsStateWithLifecycle()

    when (val state = licenseState) {
        is LicenseState.Checking -> {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        is LicenseState.Unlicensed -> {
            UnlicensedScreen(onLinkDevice = viewModel::requestPairing)
        }
        is LicenseState.Pairing -> {
            PairingScreen(
                pairingToken = state.pairingToken,
                pairingCode = state.pairingCode,
                expiresAt = state.expiresAt,
                onCancel = viewModel::cancelPairing,
            )
        }
        is LicenseState.Licensed -> {
            DashboardContent(
                onProfileClick = onProfileClick,
                viewModel = viewModel,
                adminViewModel = adminViewModel,
            )
        }
    }
}

@Composable
private fun DashboardContent(
    onProfileClick: (profileId: Long) -> Unit,
    viewModel: DashboardViewModel,
    adminViewModel: AdminViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var drawerOpen by remember { mutableStateOf(false) }
    var logsExpanded by remember { mutableStateOf(false) }
    var showStopDialog by remember { mutableStateOf(false) }
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
            .navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            StatusBar(
                orchestratorState = uiState.orchestratorState,
                broadcasts = uiState.broadcasts,
                exerciseData = uiState.exerciseData,
                profileName = uiState.profileName,
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
                viewModel.unlinkDevice()
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
                text = { Text("Do you want to save this ride?") },
                confirmButton = {
                    TextButton(onClick = {
                        showStopDialog = false
                        viewModel.stopBroadcasting(save = true)
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showStopDialog = false
                        viewModel.stopBroadcasting(save = false)
                    }) { Text("Discard") }
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
