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
