package com.nettarion.hyperborea.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nettarion.hyperborea.core.ExerciseData
import com.nettarion.hyperborea.core.Metric
import com.nettarion.hyperborea.ui.theme.CumulativeStyle
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors

/**
 * Metric descriptor linking a [Metric] to how it extracts and displays data.
 */
private data class MetricSlot(
    val metric: Metric,
    val label: String,
    val unit: String,
    val extract: (ExerciseData) -> String?,
    val extractTarget: ((ExerciseData) -> String?)? = null,
)

private val allMetricSlots = listOf(
    MetricSlot(Metric.CADENCE, "Cadence", "RPM", { it.cadence?.toString() }),
    MetricSlot(
        Metric.SPEED, "Speed", "km/h",
        extract = { it.speed?.let { s -> "%.1f".format(s) } },
        extractTarget = { it.targetSpeed?.let { s -> "%.1f".format(s) } },
    ),
    MetricSlot(Metric.HEART_RATE, "Heart Rate", "BPM", { it.heartRate?.toString() }),
    MetricSlot(
        Metric.RESISTANCE, "Resistance", "",
        extract = { it.resistance?.toString() },
        extractTarget = { it.targetResistance?.toString() },
    ),
    MetricSlot(
        Metric.INCLINE, "Incline", "%",
        extract = { it.incline?.let { i -> "%.1f".format(i) } },
        extractTarget = { it.targetIncline?.let { i -> "%.1f".format(i) } },
    ),
)

// Time is always shown, not tied to a Metric enum
private val timeSlot = MetricSlot(Metric.CADENCE, "Time", "", { formatTime(it.elapsedTime) })

// Cumulative stats
private data class CumulativeSlot(
    val metric: Metric,
    val label: String,
    val extract: (ExerciseData) -> String?,
)

private val cumulativeSlots = listOf(
    CumulativeSlot(Metric.DISTANCE, "KM") { it.distance?.let { d -> "%.1f".format(d) } },
    CumulativeSlot(Metric.CALORIES, "KCAL") { it.calories?.toString() },
)

@Composable
fun MetricGrid(
    exerciseData: ExerciseData?,
    supportedMetrics: Set<Metric>?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHyperboreaColors.current

    // When device isn't connected yet, show all metrics as placeholders
    val supported = supportedMetrics ?: Metric.entries.toSet()

    // Filter to only supported metrics
    val secondarySlots = allMetricSlots.filter { it.metric in supported }
    val activeCumulatives = cumulativeSlots.filter { it.metric in supported }
    val hasPower = Metric.POWER in supported

    Row(modifier = modifier.fillMaxSize()) {
        // Power hero zone (only if supported)
        if (hasPower) {
            Box(modifier = Modifier.weight(1.3f).fillMaxHeight()) {
                MetricDisplay(
                    value = exerciseData?.power?.toString(),
                    unit = "W",
                    label = "Power",
                    style = MetricStyle.HERO,
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
        }

        // Secondary metrics: distribute into columns of 2 (stacked pairs)
        // Plus time always gets a slot
        val slotsWithTime = secondarySlots.toMutableList()
        // Insert time after speed (or at end if no speed)
        val speedIndex = slotsWithTime.indexOfFirst { it.metric == Metric.SPEED }
        if (speedIndex >= 0) {
            slotsWithTime.add(speedIndex + 1, timeSlot)
        } else {
            slotsWithTime.add(timeSlot)
        }

        // Remove resistance/incline from standard slots — they go in the right column
        val rightColumnMetrics = setOf(Metric.RESISTANCE, Metric.INCLINE)
        val standardSlots = slotsWithTime.filter { it.metric !in rightColumnMetrics || it == timeSlot }
        val rightSlots = slotsWithTime.filter { it.metric in rightColumnMetrics }

        // Pair standard slots into columns of 2
        val columns = standardSlots.chunked(2)

        columns.forEach { column ->
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                column.forEachIndexed { index, slot ->
                    if (index > 0) {
                        HorizontalDivider(thickness = 1.dp, color = colors.divider)
                    }
                    MetricDisplay(
                        value = exerciseData?.let { slot.extract(it) },
                        unit = slot.unit,
                        label = slot.label,
                        style = MetricStyle.STANDARD,
                        modifier = Modifier.weight(1f),
                        target = exerciseData?.let { slot.extractTarget?.invoke(it) },
                    )
                }
                // If odd number, fill remaining space
                if (column.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
            VerticalDivider(thickness = 1.dp, color = colors.divider)
        }

        // Right column: resistance/incline (compact) + cumulative stats
        if (rightSlots.isNotEmpty() || activeCumulatives.isNotEmpty()) {
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                rightSlots.forEachIndexed { index, slot ->
                    if (index == 0) {
                        MetricDisplay(
                            value = exerciseData?.let { slot.extract(it) },
                            unit = slot.unit,
                            label = slot.label,
                            style = MetricStyle.COMPACT,
                            modifier = Modifier.padding(top = 16.dp),
                            target = exerciseData?.let { slot.extractTarget?.invoke(it) },
                        )
                    } else {
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = colors.divider,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        MetricDisplay(
                            value = exerciseData?.let { slot.extract(it) },
                            unit = slot.unit,
                            label = slot.label,
                            style = MetricStyle.COMPACT,
                            target = exerciseData?.let { slot.extractTarget?.invoke(it) },
                        )
                    }
                }

                if (rightSlots.isNotEmpty() && activeCumulatives.isNotEmpty()) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = colors.divider,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }

                Spacer(Modifier.weight(1f))

                if (activeCumulatives.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        activeCumulatives.forEach { cumulative ->
                            val value = exerciseData?.let { cumulative.extract(it) }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = value ?: "\u2014",
                                    style = CumulativeStyle,
                                    color = if (value != null) colors.textHigh else colors.textLow,
                                )
                                Text(
                                    text = cumulative.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colors.textLow,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(elapsedTimeMs: Long): String {
    val totalSeconds = elapsedTimeMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
