package com.nettarion.hyperborea.ui.admin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Switch
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
import com.nettarion.hyperborea.core.adapter.BroadcastId
import com.nettarion.hyperborea.core.model.FanMode
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors

@Composable
fun AdminDrawer(
    isOpen: Boolean,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit = {},
    viewModel: AdminViewModel = hiltViewModel(),
) {
    val colors = LocalHyperboreaColors.current

    // Scrim
    AnimatedVisibility(
        visible = isOpen,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onClose,
                ),
        )
    }

    // Drawer panel
    AnimatedVisibility(
        visible = isOpen,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it }),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(480.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Quick Settings",
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.textHigh,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, "Close", tint = colors.textMedium)
                    }
                }
                HorizontalDivider(color = colors.divider)

                // Broadcasts section
                BroadcastsSection(viewModel)

                Spacer(Modifier.weight(1f))

                // Open Settings button
                HorizontalDivider(color = colors.divider)
                OutlinedButton(
                    onClick = {
                        onClose()
                        onOpenSettings()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text("Open Settings")
                }
            }
        }
    }
}

@Composable
private fun BroadcastsSection(viewModel: AdminViewModel) {
    val colors = LocalHyperboreaColors.current
    val enabledBroadcasts by viewModel.enabledBroadcasts.collectAsStateWithLifecycle()
    val overlayEnabled by viewModel.overlayEnabled.collectAsStateWithLifecycle()
    val fanMode by viewModel.fanMode.collectAsStateWithLifecycle()
    val snapshot by viewModel.systemSnapshot.collectAsStateWithLifecycle()

    BroadcastId.entries.forEach { id ->
        val enabled = id in enabledBroadcasts
        val readiness = when (id) {
            BroadcastId.FTMS -> if (snapshot.status.isBluetoothLeAdvertisingSupported) "BLE available" else "BLE unavailable"
            BroadcastId.WIFI -> if (snapshot.status.isWifiEnabled) "WiFi available" else "WiFi off"
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = id.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textHigh,
                )
                Text(
                    text = readiness,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMedium,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { viewModel.toggleBroadcast(id, it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                ),
            )
        }
    }

    HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 4.dp))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "System Overlay",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textHigh,
            )
            Text(
                text = "Show exercise data over other apps",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMedium,
            )
        }
        Switch(
            checked = overlayEnabled,
            onCheckedChange = { viewModel.toggleOverlay(it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
            ),
        )
    }

    HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 4.dp))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Fan",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textHigh,
            )
            Text(
                text = "Controls the built-in fan",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMedium,
            )
        }
        Row(
            modifier = Modifier.wrapContentWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FanModeChip("Off", fanMode == FanMode.OFF) { viewModel.setFanMode(FanMode.OFF) }
            FanModeChip("Auto", fanMode == FanMode.AUTO) { viewModel.setFanMode(FanMode.AUTO) }
            FanModeChip("Wind", fanMode == FanMode.WIND_SIMULATION) { viewModel.setFanMode(FanMode.WIND_SIMULATION) }
        }
    }
}

@Composable
private fun FanModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

@Composable
internal fun GetHelpButton(state: SupportUploadState, viewModel: AdminViewModel) {
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showResultDialog by remember { mutableStateOf(false) }

    // Show result dialog when upload succeeds or fails
    LaunchedEffect(state) {
        when (state) {
            is SupportUploadState.Success, is SupportUploadState.Error -> showResultDialog = true
            else -> {}
        }
    }

    OutlinedButton(
        onClick = { showConfirmDialog = true },
        enabled = state !is SupportUploadState.Uploading,
    ) {
        if (state is SupportUploadState.Uploading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Text("Get Help")
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Send diagnostics?") },
            text = { Text("This will upload your device info and recent logs to support. You\u2019ll receive a code to reference when contacting us.") },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmDialog = false
                    viewModel.uploadSupport()
                }) { Text("Send") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showResultDialog) {
        when (state) {
            is SupportUploadState.Success -> {
                val colors = LocalHyperboreaColors.current
                AlertDialog(
                    onDismissRequest = {
                        showResultDialog = false
                        viewModel.dismissSupportUpload()
                    },
                    title = { Text("Support code") },
                    text = {
                        Text(
                            text = state.code,
                            style = MaterialTheme.typography.headlineLarge,
                            color = colors.statusActive,
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showResultDialog = false
                            viewModel.dismissSupportUpload()
                        }) { Text("Done") }
                    },
                )
            }
            is SupportUploadState.Error -> {
                AlertDialog(
                    onDismissRequest = {
                        showResultDialog = false
                        viewModel.dismissSupportUpload()
                    },
                    title = { Text("Upload failed") },
                    text = { Text(state.message) },
                    confirmButton = {
                        TextButton(onClick = {
                            showResultDialog = false
                            viewModel.uploadSupport()
                        }) { Text("Retry") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showResultDialog = false
                            viewModel.dismissSupportUpload()
                        }) { Text("Cancel") }
                    },
                )
            }
            else -> showResultDialog = false
        }
    }
}
