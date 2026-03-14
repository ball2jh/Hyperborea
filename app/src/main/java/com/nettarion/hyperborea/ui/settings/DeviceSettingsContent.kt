package com.nettarion.hyperborea.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nettarion.hyperborea.ui.admin.AdminViewModel
import com.nettarion.hyperborea.ui.admin.CalibrationState
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors

@Composable
fun DeviceSettingsContent(
    adminViewModel: AdminViewModel,
    onConfigureDevice: (Int?) -> Unit,
    onUnlinkDevice: () -> Unit,
) {
    val colors = LocalHyperboreaColors.current
    val identity by adminViewModel.deviceIdentity.collectAsStateWithLifecycle()
    val calibrationState by adminViewModel.calibrationState.collectAsStateWithLifecycle()
    var showCalibrateDialog by remember { mutableStateOf(false) }
    var showCalibrationResult by remember { mutableStateOf(false) }
    var showUnlinkDialog by remember { mutableStateOf(false) }

    LaunchedEffect(calibrationState) {
        when (calibrationState) {
            is CalibrationState.Done, is CalibrationState.Failed -> showCalibrationResult = true
            else -> {}
        }
    }

    Text(
        text = "Device",
        style = MaterialTheme.typography.headlineMedium,
        color = colors.textHigh,
    )
    Spacer(Modifier.height(16.dp))

    // Device identity
    identity?.let { id ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.weight(1f)) {
                DiagRow("Serial", id.serialNumber ?: "\u2014")
                DiagRow("Firmware", id.firmwareVersion ?: "\u2014")
                id.equipmentHours?.let { seconds ->
                    Spacer(Modifier.height(4.dp))
                    val hours = seconds / 3600
                    val mins = (seconds % 3600) / 60
                    DiagRow("Equipment Hours", "${hours}h ${mins}m")
                }
            }
            Column(Modifier.weight(1f)) {
                DiagRow("Hardware", id.hardwareVersion ?: "\u2014")
                DiagRow("Model", id.model ?: "\u2014")
                id.equipmentDistance?.let { meters ->
                    Spacer(Modifier.height(4.dp))
                    val km = meters / 1000f
                    DiagRow("Odometer", "%,.0f km".format(km))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    } ?: run {
        Text(
            text = "No device connected",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textMedium,
        )
        Spacer(Modifier.height(8.dp))
    }

    HorizontalDivider(color = colors.divider)
    Spacer(Modifier.height(16.dp))

    // Action buttons side-by-side
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // Configure Device
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Device Configuration",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textHigh,
                )
                Text(
                    text = "Edit device specs and ranges",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMedium,
                )
            }
            OutlinedButton(onClick = {
                val modelNumber = identity?.model?.toIntOrNull()
                onConfigureDevice(modelNumber)
            }) {
                Text("Configure")
            }
        }

        // Calibrate Incline
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Calibrate Incline",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textHigh,
                )
                Text(
                    text = "Moves incline through full range",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMedium,
                )
            }
            OutlinedButton(
                onClick = { showCalibrateDialog = true },
                enabled = calibrationState !is CalibrationState.InProgress,
            ) {
                if (calibrationState is CalibrationState.InProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Calibrate")
                }
            }
        }
    }

    // Calibrate confirmation dialog
    if (showCalibrateDialog) {
        AlertDialog(
            onDismissRequest = { showCalibrateDialog = false },
            title = { Text("Calibrate incline?") },
            text = { Text("The incline will move through its full range. Make sure nothing is blocking it.") },
            confirmButton = {
                TextButton(onClick = {
                    showCalibrateDialog = false
                    adminViewModel.calibrateIncline()
                }) { Text("Calibrate") }
            },
            dismissButton = {
                TextButton(onClick = { showCalibrateDialog = false }) { Text("Cancel") }
            },
        )
    }

    // Calibration result dialogs
    if (showCalibrationResult) {
        when (calibrationState) {
            is CalibrationState.Done -> {
                AlertDialog(
                    onDismissRequest = {
                        showCalibrationResult = false
                        adminViewModel.dismissCalibration()
                    },
                    title = { Text("Calibration complete") },
                    text = { Text("Incline calibration finished successfully.") },
                    confirmButton = {
                        TextButton(onClick = {
                            showCalibrationResult = false
                            adminViewModel.dismissCalibration()
                        }) { Text("OK") }
                    },
                )
            }
            is CalibrationState.Failed -> {
                AlertDialog(
                    onDismissRequest = {
                        showCalibrationResult = false
                        adminViewModel.dismissCalibration()
                    },
                    title = { Text("Calibration failed") },
                    text = { Text((calibrationState as CalibrationState.Failed).message) },
                    confirmButton = {
                        TextButton(onClick = {
                            showCalibrationResult = false
                            adminViewModel.dismissCalibration()
                        }) { Text("OK") }
                    },
                )
            }
            else -> showCalibrationResult = false
        }
    }

    Spacer(Modifier.height(16.dp))
    HorizontalDivider(color = colors.divider)
    Spacer(Modifier.height(16.dp))

    // Unlink Device
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Unlink Device",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textHigh,
            )
            Text(
                text = "Remove this device from your account",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMedium,
            )
        }
        OutlinedButton(onClick = { showUnlinkDialog = true }) {
            Text("Unlink")
        }
    }

    if (showUnlinkDialog) {
        AlertDialog(
            onDismissRequest = { showUnlinkDialog = false },
            title = { Text("Unlink device?") },
            text = { Text("This will disconnect this device from your account. You can link it again later.") },
            confirmButton = {
                TextButton(onClick = {
                    showUnlinkDialog = false
                    onUnlinkDevice()
                }) { Text("Unlink") }
            },
            dismissButton = {
                TextButton(onClick = { showUnlinkDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    val colors = LocalHyperboreaColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textMedium,
            modifier = Modifier.width(100.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textHigh,
        )
    }
}
