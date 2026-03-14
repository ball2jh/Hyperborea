package com.nettarion.hyperborea.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
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
import com.nettarion.hyperborea.core.adapter.AdapterState
import com.nettarion.hyperborea.ui.admin.AdminViewModel
import com.nettarion.hyperborea.ui.admin.GetHelpButton
import com.nettarion.hyperborea.ui.admin.LogLevelFilterChips
import com.nettarion.hyperborea.ui.admin.LogList
import com.nettarion.hyperborea.ui.admin.LogTabRow
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColumnScope.SystemSettingsContent(
    adminViewModel: AdminViewModel,
    onExpandLogs: () -> Unit,
) {
    val colors = LocalHyperboreaColors.current
    val snapshot by adminViewModel.systemSnapshot.collectAsStateWithLifecycle()
    val broadcastDiags by adminViewModel.broadcastDiagnostics.collectAsStateWithLifecycle(emptyList())
    val supportState by adminViewModel.supportUploadState.collectAsStateWithLifecycle()

    Text(
        text = "System",
        style = MaterialTheme.typography.headlineMedium,
        color = colors.textHigh,
    )
    Spacer(Modifier.height(16.dp))

    // System status
    val status = snapshot.status
    FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        StatusRow("BLE Advertising", status.isBluetoothLeAdvertisingSupported)
        StatusRow("WiFi", status.isWifiEnabled)
        StatusRow("USB Host", status.isUsbHostAvailable)
        StatusRow("ADB", status.isAdbEnabled)
        StatusRow("Root", status.isRootAvailable)
    }

    // USB Devices & Broadcast Clients side-by-side
    if (snapshot.usbDevices.isNotEmpty() || broadcastDiags.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // USB devices
            Column(Modifier.weight(1f)) {
                if (snapshot.usbDevices.isNotEmpty()) {
                    Text(
                        text = "USB Devices",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textHigh,
                    )
                    snapshot.usbDevices.forEach { device ->
                        Text(
                            text = "${device.productName ?: "Unknown"} (${"%04X".format(device.vendorId)}:${"%04X".format(device.productId)})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMedium,
                            modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                        )
                    }
                }
            }

            // Broadcast clients
            Column(Modifier.weight(1f)) {
                if (broadcastDiags.isNotEmpty()) {
                    Text(
                        text = "Broadcast Clients",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textHigh,
                    )
                    broadcastDiags.forEach { diag ->
                        val stateLabel = when (diag.state) {
                            is AdapterState.Active -> "active"
                            is AdapterState.Activating -> "activating"
                            is AdapterState.Error -> "error"
                            is AdapterState.Inactive -> "inactive"
                        }
                        val clientLabel = if (diag.clients.isEmpty()) "no clients" else "${diag.clients.size} connected"
                        Row(
                            modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                        ) {
                            Text(
                                text = diag.id.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textMedium,
                                modifier = Modifier.width(100.dp),
                            )
                            Text(
                                text = "$stateLabel \u2014 $clientLabel",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textHigh,
                            )
                        }
                        diag.clients.forEach { client ->
                            Text(
                                text = client.id,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textLow,
                                modifier = Modifier.padding(start = 24.dp, top = 1.dp, bottom = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    HorizontalDivider(color = colors.divider)
    Spacer(Modifier.height(16.dp))

    // Support
    Text(
        text = "Support",
        style = MaterialTheme.typography.bodyLarge,
        color = colors.textHigh,
    )
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Upload diagnostics to support",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textMedium,
            modifier = Modifier.weight(1f),
        )
        GetHelpButton(supportState, adminViewModel)
    }

    Spacer(Modifier.height(16.dp))
    HorizontalDivider(color = colors.divider)
    Spacer(Modifier.height(16.dp))

    // Logs
    Text(
        text = "Logs",
        style = MaterialTheme.typography.bodyLarge,
        color = colors.textHigh,
    )
    Spacer(Modifier.height(8.dp))

    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedLevels by remember { mutableStateOf(setOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR)) }

    val appLogs by adminViewModel.logEntries.collectAsStateWithLifecycle()
    val systemLogs by adminViewModel.systemLogEntries.collectAsStateWithLifecycle()

    LogTabRow(selectedTab = selectedTab, onTabSelected = { selectedTab = it })

    LogLevelFilterChips(
        selectedLevels = selectedLevels,
        onToggleLevel = { level ->
            selectedLevels = if (level in selectedLevels) selectedLevels - level else selectedLevels + level
        },
        modifier = Modifier.padding(vertical = 8.dp),
    )

    LogList(
        selectedTab = selectedTab,
        appLogs = appLogs,
        systemLogs = systemLogs,
        selectedLevels = selectedLevels,
        modifier = Modifier.weight(1f),
    )

    // Log action buttons
    Row(
        modifier = Modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = {
            if (selectedTab == 0) adminViewModel.clearLogs() else adminViewModel.clearSystemLogs()
        }) {
            Text("Clear")
        }
        OutlinedButton(onClick = {
            if (selectedTab == 0) adminViewModel.exportLogs() else adminViewModel.exportSystemLogs()
        }) {
            Text("Export")
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onExpandLogs) {
            Text("Full Screen")
        }
    }
}

@Composable
private fun StatusRow(label: String, available: Boolean) {
    val colors = LocalHyperboreaColors.current
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    if (available) colors.statusActive else colors.statusError,
                    CircleShape,
                ),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textMedium,
        )
    }
}
