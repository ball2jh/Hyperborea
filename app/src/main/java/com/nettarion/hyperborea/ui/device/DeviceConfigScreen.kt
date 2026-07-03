package com.nettarion.hyperborea.ui.device

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.model.Metric
import com.nettarion.hyperborea.ui.components.HyperboreaFilterChip
import com.nettarion.hyperborea.ui.components.NumberField
import com.nettarion.hyperborea.ui.components.hyperboreaTextFieldColors
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors

@Composable
fun DeviceConfigScreen(
    modelNumber: Int?,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    viewModel: DeviceConfigViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(modelNumber) {
        viewModel.load(modelNumber)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 24.dp),
        ) {
            Header(isCustom = state.isCustom, onBack = onBack, onReset = viewModel::resetToDefaults)

            Spacer(Modifier.height(32.dp))

            // Name field
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text("Name") },
                singleLine = true,
                colors = hyperboreaTextFieldColors(),
                textStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(36.dp))

            // Two-column layout
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(64.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    DeviceSection(state = state, viewModel = viewModel)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    RangesSection(state = state, viewModel = viewModel)
                }
            }

            BottomButtons(canSave = state.name.isNotBlank(), onBack = onBack, onSave = { viewModel.save(onSaved) })
        }
    }
}

@Composable
private fun Header(isCustom: Boolean, onBack: () -> Unit, onReset: () -> Unit) {
    val colors = LocalHyperboreaColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        IconButton(onClick = onBack) {
            @Suppress("DEPRECATION")
            Icon(
                imageVector = Icons.Outlined.ArrowBack,
                contentDescription = "Back",
                tint = colors.textHigh,
            )
        }
        Text(
            text = "Device Configuration",
            style = MaterialTheme.typography.headlineMedium,
            color = colors.textHigh,
        )
        Spacer(Modifier.weight(1f))
        if (isCustom) {
            OutlinedButton(
                onClick = onReset,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textMedium),
                border = BorderStroke(1.dp, colors.divider),
            ) {
                Text("Reset to Defaults")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.DeviceSection(state: DeviceConfigUiState, viewModel: DeviceConfigViewModel) {
    val colors = LocalHyperboreaColors.current
    SectionHeader("DEVICE")

    // Device type
    Text(
        text = "Type",
        style = MaterialTheme.typography.bodyMedium,
        color = colors.textMedium,
    )
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DeviceType.entries.forEach { dt ->
            HyperboreaFilterChip(
                selected = state.type == dt,
                onClick = { viewModel.setType(dt) },
                label = dt.name.lowercase().replaceFirstChar { it.uppercase() },
            )
        }
    }

    Spacer(Modifier.height(24.dp))

    // Supported metrics
    Text(
        text = "Supported Metrics",
        style = MaterialTheme.typography.bodyMedium,
        color = colors.textMedium,
    )
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Metric.entries.forEach { metric ->
            HyperboreaFilterChip(
                selected = metric in state.supportedMetrics,
                onClick = { viewModel.toggleMetric(metric) },
                label = metric.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
            )
        }
    }
}

@Composable
private fun ColumnScope.RangesSection(state: DeviceConfigUiState, viewModel: DeviceConfigViewModel) {
    SectionHeader("RANGES")

    // Resistance
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        NumberField(
            value = state.minResistance,
            onValueChange = viewModel::setMinResistance,
            label = "Min Resistance",
            modifier = Modifier.weight(1f),
        )
        NumberField(
            value = state.maxResistance,
            onValueChange = viewModel::setMaxResistance,
            label = "Max Resistance",
            modifier = Modifier.weight(1f),
        )
    }

    Spacer(Modifier.height(16.dp))

    // Incline
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        NumberField(
            value = state.minIncline,
            onValueChange = viewModel::setMinIncline,
            label = "Min Incline",
            suffix = "%",
            decimal = true,
            modifier = Modifier.weight(1f),
        )
        NumberField(
            value = state.maxIncline,
            onValueChange = viewModel::setMaxIncline,
            label = "Max Incline",
            suffix = "%",
            decimal = true,
            modifier = Modifier.weight(1f),
        )
    }

    Spacer(Modifier.height(16.dp))

    // Power
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        NumberField(
            value = state.minPower,
            onValueChange = viewModel::setMinPower,
            label = "Min Power",
            suffix = "W",
            modifier = Modifier.weight(1f),
        )
        NumberField(
            value = state.maxPower,
            onValueChange = viewModel::setMaxPower,
            label = "Max Power",
            suffix = "W",
            modifier = Modifier.weight(1f),
        )
    }

    Spacer(Modifier.height(16.dp))

    // Steps
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        NumberField(
            value = state.resistanceStep,
            onValueChange = viewModel::setResistanceStep,
            label = "Resistance Step",
            decimal = true,
            modifier = Modifier.weight(1f),
        )
        NumberField(
            value = state.inclineStep,
            onValueChange = viewModel::setInclineStep,
            label = "Incline Step",
            suffix = "%",
            decimal = true,
            modifier = Modifier.weight(1f),
        )
    }

    Spacer(Modifier.height(16.dp))

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        NumberField(
            value = state.speedStep,
            onValueChange = viewModel::setSpeedStep,
            label = "Speed Step",
            suffix = "kph",
            decimal = true,
            modifier = Modifier.weight(1f),
        )
        NumberField(
            value = state.powerStep,
            onValueChange = viewModel::setPowerStep,
            label = "Power Step",
            suffix = "W",
            modifier = Modifier.weight(1f),
        )
    }

    Spacer(Modifier.height(16.dp))

    // Max speed
    NumberField(
        value = state.maxSpeed,
        onValueChange = viewModel::setMaxSpeed,
        label = "Max Speed",
        suffix = "kph",
        decimal = true,
        modifier = Modifier.fillMaxWidth(0.48f),
    )
}

@Composable
private fun BottomButtons(canSave: Boolean, onBack: () -> Unit, onSave: () -> Unit) {
    val colors = LocalHyperboreaColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        OutlinedButton(
            onClick = onBack,
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textMedium),
            border = BorderStroke(1.dp, colors.divider),
        ) {
            Text("Cancel", modifier = Modifier.padding(horizontal = 16.dp))
        }
        Spacer(Modifier.width(12.dp))
        OutlinedButton(
            onClick = onSave,
            enabled = canSave,
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.electricBlue),
            border = BorderStroke(
                1.dp,
                if (canSave) colors.electricBlue else colors.divider,
            ),
        ) {
            Text("Save", modifier = Modifier.padding(horizontal = 24.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    val colors = LocalHyperboreaColors.current
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = colors.textLow,
    )
    Spacer(Modifier.height(8.dp))
    HorizontalDivider(color = colors.divider)
    Spacer(Modifier.height(20.dp))
}
