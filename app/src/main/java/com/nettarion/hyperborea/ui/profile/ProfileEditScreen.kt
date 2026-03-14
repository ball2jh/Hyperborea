package com.nettarion.hyperborea.ui.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors

@Composable
fun ProfileEditScreen(
    profileId: Long?,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    onDeleted: () -> Unit = {},
    viewModel: ProfileEditViewModel = hiltViewModel(),
) {
    val colors = LocalHyperboreaColors.current
    val name by viewModel.name.collectAsStateWithLifecycle()
    val weight by viewModel.weight.collectAsStateWithLifecycle()
    val height by viewModel.height.collectAsStateWithLifecycle()
    val heightInches by viewModel.heightInches.collectAsStateWithLifecycle()
    val age by viewModel.age.collectAsStateWithLifecycle()
    val ftpWatts by viewModel.ftpWatts.collectAsStateWithLifecycle()
    val maxHeartRate by viewModel.maxHeartRate.collectAsStateWithLifecycle()
    val useImperial by viewModel.useImperial.collectAsStateWithLifecycle()

    LaunchedEffect(profileId) {
        viewModel.loadProfile(profileId)
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
            // Header row: back + title + unit toggle
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
                    text = if (profileId != null) "Edit Profile" else "New Profile",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.textHigh,
                )
                Spacer(Modifier.weight(1f))
                UnitToggle(
                    useImperial = useImperial,
                    onToggle = viewModel::toggleUnits,
                )
            }

            Spacer(Modifier.height(32.dp))

            // Name field — full width
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
                // Left column — Body
                Column(modifier = Modifier.weight(1f)) {
                    SectionHeader("BODY")

                    // Weight
                    OutlinedTextField(
                        value = weight,
                        onValueChange = viewModel::setWeight,
                        label = { Text("Weight") },
                        suffix = { Text(if (useImperial) "lbs" else "kg", color = colors.textLow) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = textFieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(16.dp))

                    // Height
                    if (useImperial) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedTextField(
                                value = height,
                                onValueChange = viewModel::setHeight,
                                label = { Text("Height") },
                                suffix = { Text("ft", color = colors.textLow) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = textFieldColors,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = heightInches,
                                onValueChange = viewModel::setHeightInches,
                                label = { Text("") },
                                suffix = { Text("in", color = colors.textLow) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = textFieldColors,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = height,
                            onValueChange = viewModel::setHeight,
                            label = { Text("Height") },
                            suffix = { Text("cm", color = colors.textLow) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = textFieldColors,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // Age
                    OutlinedTextField(
                        value = age,
                        onValueChange = viewModel::setAge,
                        label = { Text("Age") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = textFieldColors,
                        modifier = Modifier.fillMaxWidth(0.5f),
                    )
                }

                // Right column — Training
                Column(modifier = Modifier.weight(1f)) {
                    SectionHeader("TRAINING")

                    OutlinedTextField(
                        value = ftpWatts,
                        onValueChange = viewModel::setFtpWatts,
                        label = { Text("FTP") },
                        suffix = { Text("watts", color = colors.textLow) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = textFieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(16.dp))

                    OutlinedTextField(
                        value = maxHeartRate,
                        onValueChange = viewModel::setMaxHeartRate,
                        label = { Text("Max Heart Rate") },
                        suffix = { Text("bpm", color = colors.textLow) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = textFieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Buttons — bottom
            var showDeleteConfirmation by remember { mutableStateOf(false) }

            if (showDeleteConfirmation) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirmation = false },
                    title = { Text("Delete Profile") },
                    text = {
                        Text(
                            "Delete \"${name.trim()}\"? All rides for this profile will also be deleted. This cannot be undone.",
                            color = colors.textMedium,
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showDeleteConfirmation = false
                            viewModel.deleteProfile(onDeleted)
                        }) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirmation = false }) {
                            Text("Cancel")
                        }
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (profileId != null) {
                    TextButton(
                        onClick = { showDeleteConfirmation = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Delete Profile")
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                Row {
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

@Composable
private fun UnitToggle(
    useImperial: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalHyperboreaColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !useImperial,
            onClick = { if (useImperial) onToggle() },
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
            onClick = { if (!useImperial) onToggle() },
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
