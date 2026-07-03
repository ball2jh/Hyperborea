package com.nettarion.hyperborea.ui.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nettarion.hyperborea.ui.components.HyperboreaFilterChip
import com.nettarion.hyperborea.ui.components.NumberField
import com.nettarion.hyperborea.ui.components.hyperboreaTextFieldColors
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors

@Composable
fun ProfileEditScreen(
    profileId: Long?,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    onDeleted: () -> Unit = {},
    viewModel: ProfileEditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val useImperial by viewModel.useImperial.collectAsStateWithLifecycle()

    LaunchedEffect(profileId) {
        viewModel.loadProfile(profileId)
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
            Header(
                isEditing = profileId != null,
                useImperial = useImperial,
                onBack = onBack,
                onToggleUnits = viewModel::toggleUnits,
            )

            Spacer(Modifier.height(32.dp))

            // Name field — full width
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
                Column(modifier = Modifier.weight(1f)) {
                    BodySection(state = state, useImperial = useImperial, viewModel = viewModel)
                }
                Column(modifier = Modifier.weight(1f)) {
                    TrainingSection(state = state, viewModel = viewModel)
                }
            }

            BottomButtons(
                isEditing = profileId != null,
                name = state.name,
                onBack = onBack,
                onSave = { viewModel.save(onSaved) },
                onDelete = { viewModel.deleteProfile(onDeleted) },
            )
        }
    }
}

@Composable
private fun Header(
    isEditing: Boolean,
    useImperial: Boolean,
    onBack: () -> Unit,
    onToggleUnits: () -> Unit,
) {
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
            text = if (isEditing) "Edit Profile" else "New Profile",
            style = MaterialTheme.typography.headlineMedium,
            color = colors.textHigh,
        )
        Spacer(Modifier.weight(1f))
        UnitToggle(useImperial = useImperial, onToggle = onToggleUnits)
    }
}

@Composable
private fun ColumnScope.BodySection(
    state: ProfileEditUiState,
    useImperial: Boolean,
    viewModel: ProfileEditViewModel,
) {
    SectionHeader("BODY")

    // Weight
    NumberField(
        value = state.weight,
        onValueChange = viewModel::setWeight,
        label = "Weight",
        suffix = if (useImperial) "lbs" else "kg",
        decimal = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(16.dp))

    // Height
    if (useImperial) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            NumberField(
                value = state.height,
                onValueChange = viewModel::setHeight,
                label = "Height",
                suffix = "ft",
                modifier = Modifier.weight(1f),
            )
            NumberField(
                value = state.heightInches,
                onValueChange = viewModel::setHeightInches,
                label = "",
                suffix = "in",
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        NumberField(
            value = state.height,
            onValueChange = viewModel::setHeight,
            label = "Height",
            suffix = "cm",
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Spacer(Modifier.height(16.dp))

    // Age
    NumberField(
        value = state.age,
        onValueChange = viewModel::setAge,
        label = "Age",
        modifier = Modifier.fillMaxWidth(0.5f),
    )
}

@Composable
private fun ColumnScope.TrainingSection(
    state: ProfileEditUiState,
    viewModel: ProfileEditViewModel,
) {
    SectionHeader("TRAINING")

    NumberField(
        value = state.ftpWatts,
        onValueChange = viewModel::setFtpWatts,
        label = "FTP",
        suffix = "watts",
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(16.dp))

    NumberField(
        value = state.maxHeartRate,
        onValueChange = viewModel::setMaxHeartRate,
        label = "Max Heart Rate",
        suffix = "bpm",
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BottomButtons(
    isEditing: Boolean,
    name: String,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalHyperboreaColors.current
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
                    onDelete()
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
        if (isEditing) {
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
                onClick = onSave,
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HyperboreaFilterChip(
            selected = !useImperial,
            onClick = { if (useImperial) onToggle() },
            label = "Metric",
        )
        HyperboreaFilterChip(
            selected = useImperial,
            onClick = { if (!useImperial) onToggle() },
            label = "Imperial",
        )
    }
}
