package com.nettarion.hyperborea.ui.components

import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors

/**
 * A [FilterChip] in the app's electric-blue selection style, used for the device-type,
 * supported-metric, and unit-toggle chip rows.
 */
@Composable
fun HyperboreaFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHyperboreaColors.current
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier,
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
