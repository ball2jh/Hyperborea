package com.nettarion.hyperborea.ui.device

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.model.Metric
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceConfigScreen(
    modelNumber: Int?,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    viewModel: DeviceConfigViewModel = hiltViewModel(),
) {
    val colors = LocalHyperboreaColors.current
    val name by viewModel.name.collectAsStateWithLifecycle()
    val type by viewModel.type.collectAsStateWithLifecycle()
    val supportedMetrics by viewModel.supportedMetrics.collectAsStateWithLifecycle()
    val maxResistance by viewModel.maxResistance.collectAsStateWithLifecycle()
    val minResistance by viewModel.minResistance.collectAsStateWithLifecycle()
    val maxIncline by viewModel.maxIncline.collectAsStateWithLifecycle()
    val minIncline by viewModel.minIncline.collectAsStateWithLifecycle()
    val maxPower by viewModel.maxPower.collectAsStateWithLifecycle()
    val minPower by viewModel.minPower.collectAsStateWithLifecycle()
    val resistanceStep by viewModel.resistanceStep.collectAsStateWithLifecycle()
    val inclineStep by viewModel.inclineStep.collectAsStateWithLifecycle()
    val speedStep by viewModel.speedStep.collectAsStateWithLifecycle()
    val powerStep by viewModel.powerStep.collectAsStateWithLifecycle()
    val maxSpeed by viewModel.maxSpeed.collectAsStateWithLifecycle()
    val isCustom by viewModel.isCustom.collectAsStateWithLifecycle()

    LaunchedEffect(modelNumber) {
        viewModel.load(modelNumber)
    }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.textHigh,
        unfocusedTextColor = colors.textHigh,
        focusedBorderColor = colors.electricBlue,
        unfocusedBorderColor = colors.divider,
        focusedLabelColor = colors.electricBlue,
        unfocusedLabelColor = colors.textMedium,
        cursorColor = colors.electricBlue,
    )

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
            // Header
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
                        onClick = { viewModel.resetToDefaults() },
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textMedium),
                        border = BorderStroke(1.dp, colors.divider),
                    ) {
                        Text("Reset to Defaults")
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            // Name field
            OutlinedTextField(
                value = name,
                onValueChange = viewModel::setName,
                label = { Text("Name") },
                singleLine = true,
                colors = textFieldColors,
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
                // Left column — Device
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
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
                            FilterChip(
                                selected = type == dt,
                                onClick = { viewModel.setType(dt) },
                                label = { Text(dt.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = colors.electricBlue.copy(alpha = 0.15f),
                                    selectedLabelColor = colors.electricBlue,
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    labelColor = colors.textLow,
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = type == dt,
                                    borderColor = colors.divider,
                                    selectedBorderColor = colors.electricBlue,
                                ),
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
                            FilterChip(
                                selected = metric in supportedMetrics,
                                onClick = { viewModel.toggleMetric(metric) },
                                label = {
                                    Text(
                                        metric.name.lowercase().replace('_', ' ')
                                            .replaceFirstChar { it.uppercase() },
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = colors.electricBlue.copy(alpha = 0.15f),
                                    selectedLabelColor = colors.electricBlue,
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    labelColor = colors.textLow,
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = metric in supportedMetrics,
                                    borderColor = colors.divider,
                                    selectedBorderColor = colors.electricBlue,
                                ),
                            )
                        }
                    }
                }

                // Right column — Ranges
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    SectionHeader("RANGES")

                    // Resistance
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = minResistance,
                            onValueChange = viewModel::setMinResistance,
                            label = { Text("Min Resistance") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = textFieldColors,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = maxResistance,
                            onValueChange = viewModel::setMaxResistance,
                            label = { Text("Max Resistance") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = textFieldColors,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // Incline
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = minIncline,
                            onValueChange = viewModel::setMinIncline,
                            label = { Text("Min Incline") },
                            suffix = { Text("%", color = colors.textLow) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            colors = textFieldColors,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = maxIncline,
                            onValueChange = viewModel::setMaxIncline,
                            label = { Text("Max Incline") },
                            suffix = { Text("%", color = colors.textLow) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            colors = textFieldColors,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // Power
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = minPower,
                            onValueChange = viewModel::setMinPower,
                            label = { Text("Min Power") },
                            suffix = { Text("W", color = colors.textLow) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = textFieldColors,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = maxPower,
                            onValueChange = viewModel::setMaxPower,
                            label = { Text("Max Power") },
                            suffix = { Text("W", color = colors.textLow) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = textFieldColors,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // Steps
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = resistanceStep,
                            onValueChange = viewModel::setResistanceStep,
                            label = { Text("Resistance Step") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            colors = textFieldColors,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = inclineStep,
                            onValueChange = viewModel::setInclineStep,
                            label = { Text("Incline Step") },
                            suffix = { Text("%", color = colors.textLow) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            colors = textFieldColors,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = speedStep,
                            onValueChange = viewModel::setSpeedStep,
                            label = { Text("Speed Step") },
                            suffix = { Text("kph", color = colors.textLow) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            colors = textFieldColors,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = powerStep,
                            onValueChange = viewModel::setPowerStep,
                            label = { Text("Power Step") },
                            suffix = { Text("W", color = colors.textLow) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = textFieldColors,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // Max speed
                    OutlinedTextField(
                        value = maxSpeed,
                        onValueChange = viewModel::setMaxSpeed,
                        label = { Text("Max Speed") },
                        suffix = { Text("kph", color = colors.textLow) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = textFieldColors,
                        modifier = Modifier.fillMaxWidth(0.48f),
                    )
                }
            }

            // Bottom buttons
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
                    onClick = { viewModel.save(onSaved) },
                    enabled = name.isNotBlank(),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.electricBlue),
                    border = BorderStroke(
                        1.dp,
                        if (name.isNotBlank()) colors.electricBlue else colors.divider,
                    ),
                ) {
                    Text("Save", modifier = Modifier.padding(horizontal = 24.dp))
                }
            }
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
