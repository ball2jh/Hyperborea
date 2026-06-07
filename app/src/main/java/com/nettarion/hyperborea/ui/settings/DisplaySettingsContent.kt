package com.nettarion.hyperborea.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors

/**
 * "Display" tab — global units preference plus the screen-presentation toggles
 * (system overlay, immersive mode). Reachable from the dashboard via the gear
 * → AdminDrawer → "Open Settings" path, for guests and named profiles alike.
 */
@Composable
fun DisplaySettingsContent(
    viewModel: DisplaySettingsViewModel = hiltViewModel(),
) {
    val colors = LocalHyperboreaColors.current
    val useImperial by viewModel.useImperial.collectAsStateWithLifecycle()
    val overlayEnabled by viewModel.overlayEnabled.collectAsStateWithLifecycle()
    val immersiveModeEnabled by viewModel.immersiveModeEnabled.collectAsStateWithLifecycle()
    val screenSleepEnabled by viewModel.screenSleepEnabled.collectAsStateWithLifecycle()
    val screenSleepMinutes by viewModel.screenSleepTimeoutMinutes.collectAsStateWithLifecycle()

    // Re-read the WRITE_SETTINGS grant after the user returns from the special-access screen.
    // The launcher result carries no payload (the system screen reports nothing), so we bump a
    // tick to recompute canWriteSettings() and re-apply the timeout if it was just granted.
    var permissionTick by remember { mutableIntStateOf(0) }
    val canWriteSettings = remember(permissionTick) { viewModel.canWriteSettings() }
    val writeSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        permissionTick++
        viewModel.reapplyScreenSleep()
    }
    fun requestWriteSettings() {
        viewModel.writeSettingsIntent()?.let { writeSettingsLauncher.launch(it) }
    }

    Text(
        text = "Display",
        style = MaterialTheme.typography.headlineMedium,
        color = colors.textHigh,
    )
    Spacer(Modifier.height(16.dp))

    // Units
    SettingsRow(
        title = "Units",
        subtitle = if (useImperial) "Imperial — mph, mi, lbs, ft" else "Metric — km/h, km, kg, cm",
    ) {
        UnitsToggle(
            useImperial = useImperial,
            onSelect = viewModel::setUseImperial,
        )
    }

    HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 8.dp))

    // System overlay
    SettingsRow(
        title = "System Overlay",
        subtitle = "Show exercise data over other apps",
    ) {
        ThemedSwitch(
            checked = overlayEnabled,
            onCheckedChange = viewModel::setOverlayEnabled,
        )
    }

    HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 8.dp))

    // Immersive mode
    SettingsRow(
        title = "Immersive Mode",
        subtitle = "Hide navigation and status bars",
    ) {
        ThemedSwitch(
            checked = immersiveModeEnabled,
            onCheckedChange = viewModel::setImmersiveModeEnabled,
        )
    }

    HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 8.dp))

    // Screen sleep — turn the display off after a period of inactivity. The screen is held on
    // during an active workout regardless; the timeout only applies when idle.
    SettingsRow(
        title = "Screen Sleep",
        subtitle = when {
            !screenSleepEnabled -> "Turn the display off after a period of inactivity"
            !canWriteSettings -> "Permission needed to control the screen timeout"
            else -> "Sleeps after ${screenSleepMinutes} min idle — stays on during workouts"
        },
    ) {
        ThemedSwitch(
            checked = screenSleepEnabled,
            onCheckedChange = { enabled ->
                viewModel.setScreenSleepEnabled(enabled)
                if (enabled && !viewModel.canWriteSettings()) requestWriteSettings()
            },
        )
    }

    if (screenSleepEnabled && !canWriteSettings) {
        TextButton(onClick = { requestWriteSettings() }) {
            Text("Grant permission")
        }
    }

    if (screenSleepEnabled && canWriteSettings) {
        Spacer(Modifier.height(8.dp))
        SleepTimeoutSelector(
            selectedMinutes = screenSleepMinutes,
            onSelect = viewModel::setScreenSleepTimeoutMinutes,
        )
    }
}

/** Idle-duration options, balanced for both busy-gym and home use. */
private val SLEEP_TIMEOUT_OPTIONS = listOf(2, 5, 10, 30)

@Composable
private fun SleepTimeoutSelector(
    selectedMinutes: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = LocalHyperboreaColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SLEEP_TIMEOUT_OPTIONS.forEach { minutes ->
            val selected = minutes == selectedMinutes
            FilterChip(
                selected = selected,
                onClick = { if (!selected) onSelect(minutes) },
                label = { Text("$minutes min") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = colors.electricBlue.copy(alpha = 0.15f),
                    selectedLabelColor = colors.electricBlue,
                    containerColor = MaterialTheme.colorScheme.surface,
                    labelColor = colors.textLow,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected,
                    borderColor = colors.divider,
                    selectedBorderColor = colors.electricBlue,
                ),
            )
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
) {
    val colors = LocalHyperboreaColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textHigh,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMedium,
            )
        }
        trailing()
    }
}

@Composable
private fun ThemedSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.primary,
            checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
        ),
    )
}

@Composable
private fun UnitsToggle(
    useImperial: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    val colors = LocalHyperboreaColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !useImperial,
            onClick = { if (useImperial) onSelect(false) },
            label = { Text("Metric") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = colors.electricBlue.copy(alpha = 0.15f),
                selectedLabelColor = colors.electricBlue,
                containerColor = MaterialTheme.colorScheme.surface,
                labelColor = colors.textLow,
            ),
            border = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = !useImperial,
                borderColor = colors.divider,
                selectedBorderColor = colors.electricBlue,
            ),
        )
        FilterChip(
            selected = useImperial,
            onClick = { if (!useImperial) onSelect(true) },
            label = { Text("Imperial") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = colors.electricBlue.copy(alpha = 0.15f),
                selectedLabelColor = colors.electricBlue,
                containerColor = MaterialTheme.colorScheme.surface,
                labelColor = colors.textLow,
            ),
            border = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = useImperial,
                borderColor = colors.divider,
                selectedBorderColor = colors.electricBlue,
            ),
        )
    }
}
