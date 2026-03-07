package com.nettarion.hyperborea.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors
import com.nettarion.hyperborea.ui.theme.MetricUnitStyle
import com.nettarion.hyperborea.ui.theme.TargetCompactStyle
import com.nettarion.hyperborea.ui.theme.TargetStyle

enum class MetricStyle { HERO, STANDARD, COMPACT }

@Composable
fun MetricDisplay(
    value: String?,
    unit: String,
    label: String,
    style: MetricStyle,
    modifier: Modifier = Modifier,
    target: String? = null,
) {
    val colors = LocalHyperboreaColors.current
    val displayValue = value ?: "\u2014"
    val valueColor = if (value != null) colors.textHigh else colors.textLow

    when (style) {
        MetricStyle.HERO -> {
            val heroValueColor = if (value != null) colors.accentWarm else colors.textLow
            Box(modifier = modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textLow,
                    modifier = Modifier.align(Alignment.TopStart),
                )
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = displayValue,
                            style = MaterialTheme.typography.displayLarge,
                            color = heroValueColor,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = unit,
                            style = MetricUnitStyle,
                            color = colors.textMedium,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                    if (target != null) {
                        Text(
                            text = "\u2192 $target $unit",
                            style = TargetStyle,
                            color = colors.electricBlue,
                        )
                    }
                }
            }
        }
        MetricStyle.STANDARD -> {
            Box(modifier = modifier.fillMaxSize().padding(12.dp)) {
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textLow,
                    modifier = Modifier.align(Alignment.TopStart),
                )
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = displayValue,
                        style = MaterialTheme.typography.displayMedium,
                        color = valueColor,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textMedium,
                    )
                    if (target != null) {
                        Text(
                            text = "\u2192 $target",
                            style = TargetStyle,
                            color = colors.electricBlue,
                        )
                    }
                }
            }
        }
        MetricStyle.COMPACT -> {
            Row(
                modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textLow,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = displayValue,
                        style = MaterialTheme.typography.displaySmall,
                        color = valueColor,
                    )
                    if (unit.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = unit,
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textMedium,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    if (target != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "\u2192 $target",
                            style = TargetCompactStyle,
                            color = colors.electricBlue,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
