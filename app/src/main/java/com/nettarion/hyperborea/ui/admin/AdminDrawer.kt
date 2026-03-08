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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nettarion.hyperborea.core.adapter.AdapterState
import com.nettarion.hyperborea.core.adapter.BroadcastId
import com.nettarion.hyperborea.core.LogLevel
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors

@Composable
fun AdminDrawer(
    isOpen: Boolean,
    onClose: () -> Unit,
    onExpandLogs: () -> Unit = {},
    onUnlinkDevice: () -> Unit = {},
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
                        text = "Settings",
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.textHigh,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, "Close", tint = colors.textMedium)
                    }
                }
                HorizontalDivider(color = colors.divider)

                // Scrollable content with sections
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    BroadcastsSection(viewModel)
                    HorizontalDivider(color = colors.divider)
                    LogsSection(viewModel, onExpandLogs)
                    HorizontalDivider(color = colors.divider)
                    DiagnosticsSection(viewModel)
                    HorizontalDivider(color = colors.divider)
                    DeviceSection(onUnlinkDevice)
                }
            }
        }
    }
}

@Composable
private fun CollapsibleSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalHyperboreaColors.current
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = colors.textHigh,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = colors.textMedium,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(bottom = 8.dp), content = content)
        }
    }
}

@Composable
private fun BroadcastsSection(viewModel: AdminViewModel) {
    val colors = LocalHyperboreaColors.current
    val enabledBroadcasts by viewModel.enabledBroadcasts.collectAsStateWithLifecycle()
    val snapshot by viewModel.systemSnapshot.collectAsStateWithLifecycle()

    CollapsibleSection("Broadcasts", initiallyExpanded = true) {
        BroadcastId.entries.forEach { id ->
            val enabled = id in enabledBroadcasts
            val readiness = when (id) {
                BroadcastId.FTMS -> if (snapshot.status.isBluetoothLeAdvertisingSupported) "BLE available" else "BLE unavailable"
                BroadcastId.WFTNP -> if (snapshot.status.isWifiEnabled) "WiFi available" else "WiFi off"
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
    }
}

@Composable
private fun LogsSection(viewModel: AdminViewModel, onExpand: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedLevels by remember { mutableStateOf(setOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR)) }

    val appLogs by viewModel.logEntries.collectAsStateWithLifecycle()
    val systemLogs by viewModel.systemLogEntries.collectAsStateWithLifecycle()

    CollapsibleSection("Logs") {
        // Tab row
        PrimaryTabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = LocalHyperboreaColors.current.textHigh,
            divider = {},
        ) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                Text("App", modifier = Modifier.padding(vertical = 8.dp))
            }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                Text("System", modifier = Modifier.padding(vertical = 8.dp))
            }
        }

        // Level filter chips
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LogLevel.entries.forEach { level ->
                val selected = level in selectedLevels
                FilterChip(
                    selected = selected,
                    onClick = {
                        selectedLevels = if (selected) selectedLevels - level else selectedLevels + level
                    },
                    label = { Text(level.name.first().toString()) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }

        // Log entries (fixed height, newest first)
        Box(modifier = Modifier.height(300.dp)) {
            if (selectedTab == 0) {
                val filtered = appLogs.filter { it.level in selectedLevels }.asReversed()
                LazyColumn(reverseLayout = true) {
                    items(filtered, key = { "${it.timestamp}-${it.tag}-${it.message.hashCode()}" }) { entry ->
                        LogEntryRow(entry)
                    }
                }
            } else {
                val filtered = systemLogs.filter { it.level in selectedLevels }.asReversed()
                LazyColumn(reverseLayout = true) {
                    items(filtered, key = { "${it.timestamp}-${it.tag}-${it.message.hashCode()}" }) { entry ->
                        SystemLogEntryRow(entry)
                    }
                }
            }
        }

        // Action buttons
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = {
                if (selectedTab == 0) viewModel.clearLogs() else viewModel.clearSystemLogs()
            }) {
                Text("Clear")
            }
            OutlinedButton(onClick = {
                if (selectedTab == 0) viewModel.exportLogs() else viewModel.exportSystemLogs()
            }) {
                Text("Export")
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onExpand) {
                Text("Full Screen")
            }
        }
    }
}

@Composable
private fun DiagnosticsSection(viewModel: AdminViewModel) {
    val colors = LocalHyperboreaColors.current
    val snapshot by viewModel.systemSnapshot.collectAsStateWithLifecycle()
    val identity by viewModel.deviceIdentity.collectAsStateWithLifecycle()
    val exerciseData by viewModel.exerciseData.collectAsStateWithLifecycle()
    val trackState by viewModel.appTrackState.collectAsStateWithLifecycle()
    val checking by viewModel.checking.collectAsStateWithLifecycle()
    val broadcastDiags by viewModel.broadcastDiagnostics.collectAsStateWithLifecycle(emptyList())

    CollapsibleSection("Diagnostics") {
        // Device identity
        identity?.let { id ->
            Text("Device", style = MaterialTheme.typography.bodyLarge, color = colors.textHigh,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            DiagRow("Serial", id.serialNumber ?: "\u2014")
            DiagRow("Firmware", id.firmwareVersion ?: "\u2014")
            DiagRow("Hardware", id.hardwareVersion ?: "\u2014")
            DiagRow("Model", id.model ?: "\u2014")

            // Lifetime stats from exercise data
            exerciseData?.let { data ->
                Spacer(Modifier.height(4.dp))
                data.lifetimeRunningTime?.let { seconds ->
                    val hours = seconds / 3600
                    val mins = (seconds % 3600) / 60
                    DiagRow("Lifetime", "${hours}h ${mins}m")
                }
                data.lifetimeDistance?.let { km ->
                    DiagRow("Odometer", "%.1f km".format(km))
                }
                data.lifetimeCalories?.let { kcal ->
                    DiagRow("Total kcal", "%,d".format(kcal))
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // System status
        Text("System", style = MaterialTheme.typography.bodyLarge, color = colors.textHigh,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        val status = snapshot.status
        StatusRow("BLE Advertising", status.isBluetoothLeAdvertisingSupported)
        StatusRow("WiFi", status.isWifiEnabled)
        StatusRow("USB Host", status.isUsbHostAvailable)
        StatusRow("ADB", status.isAdbEnabled)
        StatusRow("Root", status.isRootAvailable)

        // USB devices
        if (snapshot.usbDevices.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("USB Devices", style = MaterialTheme.typography.bodyLarge, color = colors.textHigh,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            snapshot.usbDevices.forEach { device ->
                Text(
                    text = "${device.productName ?: "Unknown"} (${"%04X".format(device.vendorId)}:${"%04X".format(device.productId)})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                )
            }
        }

        // Broadcast connections
        if (broadcastDiags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Broadcast Clients", style = MaterialTheme.typography.bodyLarge, color = colors.textHigh,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            broadcastDiags.forEach { diag ->
                val stateLabel = when (diag.state) {
                    is AdapterState.Active -> "active"
                    is AdapterState.Activating -> "activating"
                    is AdapterState.Error -> "error"
                    is AdapterState.Inactive -> "inactive"
                }
                val clientLabel = if (diag.clients.isEmpty()) "no clients" else "${diag.clients.size} connected"
                DiagRow(diag.id.displayName, "$stateLabel \u2014 $clientLabel")
                diag.clients.forEach { client ->
                    Text(
                        text = client.id,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textLow,
                        modifier = Modifier.padding(start = 40.dp, top = 1.dp, bottom = 1.dp),
                    )
                }
            }
        }

        // Update panel
        Spacer(Modifier.height(8.dp))
        Text("Updates", style = MaterialTheme.typography.bodyLarge, color = colors.textHigh,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        UpdatePanel(
            trackState = trackState,
            checking = checking,
            onCheck = viewModel::checkForUpdates,
            onDownload = viewModel::downloadUpdate,
            onInstall = viewModel::installUpdate,
            onFinalize = viewModel::finalizeUpdate,
            onDismiss = viewModel::dismissUpdate,
        )
    }
}

@Composable
private fun DeviceSection(onUnlinkDevice: () -> Unit) {
    val colors = LocalHyperboreaColors.current
    var showConfirmDialog by remember { mutableStateOf(false) }

    CollapsibleSection("Device") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
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
            OutlinedButton(onClick = { showConfirmDialog = true }) {
                Text("Unlink")
            }
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Unlink device?") },
            text = { Text("This will disconnect this device from your account. You can link it again later.") },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmDialog = false
                    onUnlinkDevice()
                }) { Text("Unlink") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) { Text("Cancel") }
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
            .padding(horizontal = 24.dp, vertical = 2.dp),
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

@Composable
private fun StatusRow(label: String, available: Boolean) {
    val colors = LocalHyperboreaColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 2.dp),
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
