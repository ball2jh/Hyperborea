package com.nettarion.hyperborea.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.model.Metric
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors

@Composable
fun MetricGrid(
    exerciseData: ExerciseData?,
    supportedMetrics: Set<Metric>?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHyperboreaColors.current

    Column(modifier = modifier.fillMaxSize()) {
        // Primary row: Cadence | POWER | Heart Rate
        Row(modifier = Modifier.weight(2f).fillMaxWidth()) {
            // Cadence
            MetricCell(
                value = exerciseData?.cadence?.toString(),
                unit = "RPM",
                label = "Cadence",
                modifier = Modifier.weight(0.7f).fillMaxHeight(),
            )
            VerticalDivider(thickness = 1.dp, color = colors.divider)
            // Power (hero) — amber accent + bottom border
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                MetricCell(
                    value = exerciseData?.power?.toString(),
                    unit = "W",
                    label = "Power",
                    modifier = Modifier.fillMaxSize(),
                    valueStyle = MaterialTheme.typography.displayLarge,
                    unitStyle = MaterialTheme.typography.headlineLarge,
                    valueColor = colors.accentWarm,
                    target = exerciseData?.targetPower?.toString(),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .height(2.dp)
                        .background(colors.accentWarm),
                )
            }
            VerticalDivider(thickness = 1.dp, color = colors.divider)
            // Heart Rate
            MetricCell(
                value = exerciseData?.heartRate?.toString(),
                unit = "BPM",
                label = "Heart Rate",
                modifier = Modifier.weight(0.7f).fillMaxHeight(),
            )
        }
        HorizontalDivider(thickness = 1.dp, color = colors.divider)
        // Secondary row: Speed | Time | Resistance | Incline | Distance | Calories
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val secondaryValueStyle = MaterialTheme.typography.displaySmall
            val secondaryUnitStyle = MaterialTheme.typography.labelLarge
            val secondaryLabelStyle = MaterialTheme.typography.titleMedium
            val secondaryTargetStyle = MaterialTheme.typography.labelMedium

            MetricCell(
                value = exerciseData?.speed?.let { "%.1f".format(it) },
                unit = "km/h",
                label = "Speed",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                valueStyle = secondaryValueStyle,
                unitStyle = secondaryUnitStyle,
                labelStyle = secondaryLabelStyle,
                targetStyle = secondaryTargetStyle,
                target = exerciseData?.targetSpeed?.let { "%.1f".format(it) },
            )
            VerticalDivider(thickness = 1.dp, color = colors.divider)
            MetricCell(
                value = formatTime(exerciseData?.elapsedTime ?: 0),
                unit = "",
                label = "Time",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                valueStyle = secondaryValueStyle,
                unitStyle = secondaryUnitStyle,
                labelStyle = secondaryLabelStyle,
            )
            VerticalDivider(thickness = 1.dp, color = colors.divider)
            MetricCell(
                value = exerciseData?.resistance?.toString(),
                unit = "",
                label = "Resistance",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                valueStyle = secondaryValueStyle,
                unitStyle = secondaryUnitStyle,
                labelStyle = secondaryLabelStyle,
                targetStyle = secondaryTargetStyle,
                target = exerciseData?.targetResistance?.toString(),
            )
            VerticalDivider(thickness = 1.dp, color = colors.divider)
            MetricCell(
                value = exerciseData?.incline?.let { "%.1f".format(it) },
                unit = "%",
                label = "Incline",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                valueStyle = secondaryValueStyle,
                unitStyle = secondaryUnitStyle,
                labelStyle = secondaryLabelStyle,
                targetStyle = secondaryTargetStyle,
                target = exerciseData?.targetIncline?.let { "%.1f".format(it) },
            )
            VerticalDivider(thickness = 1.dp, color = colors.divider)
            MetricCell(
                value = exerciseData?.distance?.let { "%.1f".format(it) },
                unit = "KM",
                label = "Distance",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                valueStyle = secondaryValueStyle,
                unitStyle = secondaryUnitStyle,
                labelStyle = secondaryLabelStyle,
            )
            VerticalDivider(thickness = 1.dp, color = colors.divider)
            MetricCell(
                value = exerciseData?.calories?.toString(),
                unit = "KCAL",
                label = "Calories",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                valueStyle = secondaryValueStyle,
                unitStyle = secondaryUnitStyle,
                labelStyle = secondaryLabelStyle,
            )
        }
    }
}

private fun formatTime(elapsedSeconds: Long): String {
    val totalSeconds = elapsedSeconds
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
