package com.nettarion.hyperborea.ui.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.model.Metric
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors
import com.nettarion.hyperborea.ui.util.UnitFormatter

/**
 * Treadmill-specific dashboard: quick-set preset columns on the outer edges (incline left, speed
 * right — Technogym-style), incline and speed hero tiles flanking a live 400 m track widget, and
 * the secondary metrics in a row along the bottom. Bikes and other equipment keep [MetricGrid].
 */
@Composable
fun TreadmillMetricGrid(
    exerciseData: ExerciseData?,
    deviceInfo: DeviceInfo?,
    useImperial: Boolean,
    controlsEnabled: Boolean,
    onSetIncline: (Float) -> Unit,
    onSetSpeed: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHyperboreaColors.current
    val supportedMetrics = deviceInfo?.supportedMetrics
    fun isSupported(metric: Metric): Boolean = supportedMetrics?.contains(metric) != false

    Column(modifier = modifier.fillMaxSize()) {
        // Hero row: incline presets | INCLINE | 400 m track | SPEED | speed presets
        Row(modifier = Modifier.weight(2f).fillMaxWidth()) {
            PresetColumn(
                header = "Incline",
                unit = "%",
                entries = TreadmillPresets.inclinePresets(
                    minIncline = deviceInfo?.minIncline ?: 0f,
                    maxIncline = deviceInfo?.maxIncline ?: 12f,
                ).map { PresetEntry(TreadmillPresets.formatValue(it), it) },
                activeValue = exerciseData?.targetIncline ?: exerciseData?.incline,
                activeTolerance = (deviceInfo?.inclineStep ?: 0.5f) / 2f,
                enabled = controlsEnabled,
                onSelect = onSetIncline,
                modifier = Modifier.width(108.dp),
            )
            VerticalDivider(thickness = 1.dp, color = colors.divider)
            // The incline goal shows only while en route: hidden once the deck arrives within
            // tolerance. (Speed deliberately keeps its goal visible — machines quantize commanded
            // speeds to their own grid, and users preferred seeing the commanded value as-is.)
            val inclineTolerance = maxOf((deviceInfo?.inclineStep ?: 0.5f) / 2f, 0.2f)
            MetricCell(
                value = exerciseData?.incline?.let { "%.1f".format(it) },
                unit = "%",
                label = "Incline",
                modifier = Modifier.weight(0.7f).fillMaxHeight(),
                valueStyle = MaterialTheme.typography.displayLarge,
                unitStyle = MaterialTheme.typography.headlineLarge,
                valueColor = colors.accentWarm,
                target = exerciseData?.targetIncline
                    ?.takeIf { goalVisible(it, exerciseData.incline, inclineTolerance) }
                    ?.let { "%.1f".format(it) },
                supported = isSupported(Metric.INCLINE),
            )
            VerticalDivider(thickness = 1.dp, color = colors.divider)
            RunningTrackWidget(
                distanceKm = exerciseData?.distance,
                useImperial = useImperial,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            VerticalDivider(thickness = 1.dp, color = colors.divider)
            MetricCell(
                value = exerciseData?.speed?.let {
                    "%.1f".format(if (useImperial) it * UnitFormatter.KM_TO_MI else it)
                },
                unit = if (useImperial) "mph" else "km/h",
                label = "Speed",
                modifier = Modifier.weight(0.7f).fillMaxHeight(),
                valueStyle = MaterialTheme.typography.displayLarge,
                unitStyle = MaterialTheme.typography.headlineLarge,
                valueColor = colors.accentWarm,
                // Runners think in pace: min/km (or min/mi) above the raw per-hour speed.
                overline = exerciseData?.speed?.let { UnitFormatter.paceDisplay(it, useImperial) },
                target = exerciseData?.targetSpeed?.let {
                    "%.1f".format(if (useImperial) it * UnitFormatter.KM_TO_MI else it)
                },
                supported = isSupported(Metric.SPEED),
            )
            VerticalDivider(thickness = 1.dp, color = colors.divider)
            PresetColumn(
                header = "Speed",
                unit = if (useImperial) "mph" else "km/h",
                entries = TreadmillPresets.speedPresets(
                    maxSpeedKph = deviceInfo?.maxSpeed ?: 20f,
                    imperial = useImperial,
                ).map { PresetEntry(it.display, it.kph) },
                activeValue = exerciseData?.targetSpeed ?: exerciseData?.speed,
                activeTolerance = (deviceInfo?.speedStep ?: 0.5f) / 2f,
                enabled = controlsEnabled,
                onSelect = onSetSpeed,
                modifier = Modifier.width(108.dp),
            )
        }
        HorizontalDivider(thickness = 1.dp, color = colors.divider)
        // Secondary row: Power | Time | Heart Rate | Distance | Calories
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val secondaryValueStyle = MaterialTheme.typography.displaySmall
            val secondaryUnitStyle = MaterialTheme.typography.labelLarge
            val secondaryLabelStyle = MaterialTheme.typography.titleMedium
            val secondaryTargetStyle = MaterialTheme.typography.labelMedium

            MetricCell(
                value = exerciseData?.power?.toString(),
                unit = "W",
                label = "Power",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                valueStyle = secondaryValueStyle,
                unitStyle = secondaryUnitStyle,
                labelStyle = secondaryLabelStyle,
                targetStyle = secondaryTargetStyle,
                target = exerciseData?.targetPower?.toString(),
                supported = isSupported(Metric.POWER),
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
                value = exerciseData?.heartRate?.toString(),
                unit = "BPM",
                label = "Heart Rate",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                valueStyle = secondaryValueStyle,
                unitStyle = secondaryUnitStyle,
                labelStyle = secondaryLabelStyle,
                supported = isSupported(Metric.HEART_RATE),
            )
            VerticalDivider(thickness = 1.dp, color = colors.divider)
            MetricCell(
                value = exerciseData?.distance?.let {
                    "%.2f".format(if (useImperial) it * UnitFormatter.KM_TO_MI else it)
                },
                unit = if (useImperial) "MI" else "KM",
                label = "Distance",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                valueStyle = secondaryValueStyle,
                unitStyle = secondaryUnitStyle,
                labelStyle = secondaryLabelStyle,
                supported = isSupported(Metric.DISTANCE),
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
                supported = isSupported(Metric.CALORIES),
            )
        }
    }
}
