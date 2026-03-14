package com.nettarion.hyperborea.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.nettarion.hyperborea.core.adapter.AdapterState
import com.nettarion.hyperborea.ui.sensor.SensorViewModel
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors

@Composable
fun SensorSettingsContent(
    viewModel: SensorViewModel = hiltViewModel(),
) {
    val colors = LocalHyperboreaColors.current
    val sensorState by viewModel.sensorState.collectAsStateWithLifecycle()
    val savedAddress by viewModel.savedAddress.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val discovered by viewModel.discoveredDevices.collectAsStateWithLifecycle()
    val heartRate by viewModel.heartRate.collectAsStateWithLifecycle()
    var scanActive by remember { mutableStateOf(false) }

    Text(
        text = "Sensors",
        style = MaterialTheme.typography.headlineMedium,
        color = colors.textHigh,
    )
    Spacer(Modifier.height(16.dp))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Heart Rate Monitor",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textHigh,
            )
            val statusText = when {
                sensorState is AdapterState.Active && heartRate != null -> "${heartRate} bpm"
                sensorState is AdapterState.Active -> "Connected"
                sensorState is AdapterState.Activating -> "Connecting\u2026"
                sensorState is AdapterState.Error -> "Disconnected"
                savedAddress != null -> "Saved (not connected)"
                else -> "None"
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (sensorState is AdapterState.Active) colors.statusActive else colors.textMedium,
            )
        }
        if (savedAddress != null) {
            OutlinedButton(onClick = { viewModel.forgetDevice() }) {
                Text("Forget")
            }
            Spacer(Modifier.width(8.dp))
        }
        OutlinedButton(onClick = {
            if (scanActive) {
                viewModel.stopScan()
                scanActive = false
            } else {
                scanActive = true
            }
        }) {
            Text(if (scanActive) "Stop" else "Scan")
        }
    }

    if (scanActive) {
        LaunchedEffect(Unit) { viewModel.startScan() }
        DisposableEffect(Unit) { onDispose { viewModel.stopScan() } }

        Spacer(Modifier.height(8.dp))

        if (scanning && discovered.isEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Scanning\u2026", color = colors.textMedium)
            }
        }
        discovered.forEach { sensor ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        viewModel.connectDevice(sensor.address)
                        scanActive = false
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = sensor.name ?: "Unknown",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textHigh,
                    )
                    Text(
                        text = sensor.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textLow,
                    )
                }
                Text(
                    text = "${sensor.rssi} dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textLow,
                )
            }
        }
        if (scanning && discovered.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                Text("Still scanning\u2026", style = MaterialTheme.typography.bodySmall, color = colors.textLow)
            }
        }
    }
}
