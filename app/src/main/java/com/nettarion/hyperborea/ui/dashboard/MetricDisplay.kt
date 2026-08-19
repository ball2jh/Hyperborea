package com.nettarion.hyperborea.ui.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors

/**
 * Whether a blue "→ goal" is worth showing: only while the target meaningfully differs from the
 * actual. Hides the goal once reached — including on machines that quantize the commanded value
 * to their own grid (e.g. a 1.0 km/h target settling at 1.1 on an imperial-native MCU), which is
 * why callers pass a tolerance with a floor above that quantization error.
 */
internal fun goalVisible(target: Float?, actual: Float?, tolerance: Float): Boolean {
    if (target == null) return false
    if (actual == null) return true
    return kotlin.math.abs(target - actual) > tolerance
}

/** Elapsed-time display shared by the metric grids: `m:ss`, growing to `h:mm:ss` past an hour. */
internal fun formatTime(elapsedSeconds: Long): String {
    val hours = elapsedSeconds / 3600
    val minutes = (elapsedSeconds % 3600) / 60
    val seconds = elapsedSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

@Composable
fun MetricCell(
    value: String?,
    unit: String,
    label: String,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle = MaterialTheme.typography.displayMedium,
    unitStyle: TextStyle = MaterialTheme.typography.headlineMedium,
    labelStyle: TextStyle = MaterialTheme.typography.titleLarge,
    targetStyle: TextStyle = MaterialTheme.typography.headlineMedium,
    valueColor: Color = Color.Unspecified,
    target: String? = null,
    supported: Boolean = true,
    overline: String? = null,
    overlineStyle: TextStyle = MaterialTheme.typography.headlineSmall,
) {
    val colors = LocalHyperboreaColors.current
    val displayValue = value ?: "\u2014"
    val resolvedValueColor = if (valueColor != Color.Unspecified) {
        if (value != null) valueColor else colors.textLow
    } else {
        if (value != null) colors.textHigh else colors.textLow
    }
    val cellAlpha = if (supported) 1f else 0.3f

    Box(modifier = modifier.fillMaxSize().alpha(cellAlpha).padding(8.dp)) {
        Text(
            text = label.uppercase(),
            style = labelStyle,
            color = colors.textLow,
            modifier = Modifier.align(Alignment.TopStart),
        )
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (overline != null) {
                Text(
                    text = overline,
                    style = overlineStyle,
                    color = colors.textMedium,
                )
                Spacer(Modifier.height(4.dp))
            }
            Row {
                Text(
                    text = displayValue,
                    style = valueStyle,
                    color = resolvedValueColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.alignBy(LastBaseline),
                )
                if (unit.isNotEmpty()) {
                    Text(
                        text = " $unit",
                        style = unitStyle,
                        color = colors.textMedium,
                        modifier = Modifier.alignBy(LastBaseline),
                    )
                }
            }
            if (target != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "\u2192 $target",
                    style = targetStyle,
                    color = colors.electricBlue,
                )
            }
        }
    }
}
