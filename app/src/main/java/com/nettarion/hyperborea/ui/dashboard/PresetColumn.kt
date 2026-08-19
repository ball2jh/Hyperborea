package com.nettarion.hyperborea.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors

/**
 * One quick-set button: what the user reads and what gets commanded. Kept as a pair so the label
 * can be in the display unit (mph) while the target stays in the wire unit (km/h).
 */
internal data class PresetEntry(val label: String, val value: Float)

/**
 * Technogym-style vertical column of quick-set buttons flanking the treadmill dashboard: tap a
 * value to command it as a single absolute target (the smooth path — the deck sweeps to it in one
 * motion). Enabled only while a workout is Running, same rule as the −/+ clusters; the entry
 * matching the current target is highlighted.
 */
@Composable
internal fun PresetColumn(
    header: String,
    unit: String,
    entries: List<PresetEntry>,
    activeValue: Float?,
    activeTolerance: Float,
    enabled: Boolean,
    onSelect: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHyperboreaColors.current
    Column(
        modifier = modifier
            .fillMaxHeight()
            .alpha(if (enabled) 1f else 0.35f)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = header.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textLow,
        )
        Text(
            text = unit,
            style = MaterialTheme.typography.labelSmall,
            color = colors.accentWarm,
        )
        Spacer(Modifier.height(6.dp))
        entries.forEach { (label, value) ->
            val active = enabled && activeValue != null &&
                kotlin.math.abs(activeValue - value) <= activeTolerance
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (active) colors.electricBlue.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    )
                    .border(
                        BorderStroke(1.dp, if (active) colors.electricBlue else colors.divider),
                        RoundedCornerShape(10.dp),
                    )
                    .clickable(enabled = enabled) { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (active) colors.electricBlue else colors.textHigh,
                )
            }
        }
    }
}
