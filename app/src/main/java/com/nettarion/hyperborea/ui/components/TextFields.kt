package com.nettarion.hyperborea.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors

/** The app-wide outlined-text-field palette, driven by [LocalHyperboreaColors]. */
@Composable
fun hyperboreaTextFieldColors(): TextFieldColors {
    val colors = LocalHyperboreaColors.current
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.textHigh,
        unfocusedTextColor = colors.textHigh,
        focusedBorderColor = colors.electricBlue,
        unfocusedBorderColor = colors.divider,
        focusedLabelColor = colors.electricBlue,
        unfocusedLabelColor = colors.textMedium,
        cursorColor = colors.electricBlue,
    )
}

/**
 * A single-line numeric [OutlinedTextField] with the shared field palette. [decimal] picks the
 * decimal vs. whole-number keyboard; [suffix] renders a low-emphasis unit label when non-null.
 */
@Composable
fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    decimal: Boolean = false,
) {
    val colors = LocalHyperboreaColors.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        suffix = suffix?.let { { Text(it, color = colors.textLow) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
        ),
        colors = hyperboreaTextFieldColors(),
        modifier = modifier,
    )
}
