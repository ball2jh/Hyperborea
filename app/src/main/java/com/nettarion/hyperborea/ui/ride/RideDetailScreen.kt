package com.nettarion.hyperborea.ui.ride

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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nettarion.hyperborea.core.model.RideSummary
import com.nettarion.hyperborea.core.model.WorkoutSample
import com.nettarion.hyperborea.ui.theme.Amber
import com.nettarion.hyperborea.ui.theme.ElectricBlue
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors
import com.nettarion.hyperborea.ui.theme.StatusActive
import com.nettarion.hyperborea.ui.theme.StatusError
import com.nettarion.hyperborea.ui.util.UnitFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Suppress("DEPRECATION")
@Composable
fun RideDetailScreen(
    rideId: Long,
    onBack: () -> Unit,
    viewModel: RideDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(rideId) { viewModel.load(rideId) }

    val summary by viewModel.rideSummary.collectAsStateWithLifecycle()
    val samples by viewModel.samples.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val exportResult by viewModel.exportResult.collectAsStateWithLifecycle()
    val colors = LocalHyperboreaColors.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        val ride = summary
        if (ride == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading...", color = colors.textMedium)
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                // Left panel — charts
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.65f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Outlined.ArrowBack, contentDescription = "Back", tint = colors.textHigh)
                        }
                        val dateFormat = SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.US)
                        val durationText = formatDuration(ride.durationSeconds)
                        Text(
                            text = "${dateFormat.format(Date(ride.startedAt))}  ·  $durationText",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textHigh,
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    val chartModifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)

                    ChartIfData(samples, { it.power }, "Power", "W", ElectricBlue, chartModifier)
                    ChartIfData(samples, { it.heartRate }, "Heart Rate", "bpm", StatusError, chartModifier)
                    ChartIfData(samples, { it.cadence }, "Cadence", "rpm", StatusActive, chartModifier)
                    ChartIfData(samples, { it.speedKph }, "Speed", "km/h", Amber, chartModifier)
                    ChartIfData(samples, { it.resistance }, "Resistance", "", colors.textMedium, chartModifier)
                    ChartIfData(samples, { it.incline }, "Incline", "%", colors.accentWarm, chartModifier)
                }

                // Divider
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(colors.divider),
                )

                // Right panel — stats + export
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.35f)
                        .padding(20.dp),
                ) {
                    val useImperial = profile?.useImperial == true

                    Text("Summary", style = MaterialTheme.typography.titleSmall, color = colors.textHigh)
                    Spacer(Modifier.height(12.dp))

                    SummaryStats(ride, useImperial)

                    Spacer(Modifier.weight(1f))

                    Button(
                        onClick = viewModel::exportFit,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                    ) {
                        Text("Export FIT", fontSize = 14.sp)
                    }
                }
            }
        }

        // Snackbar
        exportResult?.let { result ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = {
                    TextButton(onClick = viewModel::dismissExportResult) {
                        Text("Dismiss")
                    }
                },
            ) {
                Text(
                    text = result.error ?: "Exported to ${result.filePath}",
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun ChartIfData(
    samples: List<WorkoutSample>,
    valueSelector: (WorkoutSample) -> Number?,
    label: String,
    unit: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
) {
    val hasData = samples.any { valueSelector(it) != null }
    if (!hasData) return

    WorkoutChart(
        samples = samples,
        valueSelector = valueSelector,
        label = label,
        unit = unit,
        lineColor = color,
        modifier = modifier,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SummaryStats(ride: RideSummary, useImperial: Boolean) {
    val colors = LocalHyperboreaColors.current

    @Composable
    fun stat(label: String, value: String) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = colors.textMedium)
            Text(value, style = MaterialTheme.typography.bodySmall, color = colors.textHigh)
        }
    }

    stat("Distance", UnitFormatter.distanceDisplay(ride.distanceKm, useImperial))
    stat("Calories", "${ride.calories}")
    stat("Duration", formatDuration(ride.durationSeconds))

    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = colors.divider)

    ride.avgPower?.let { stat("Avg Power", "${it}W") }
    ride.maxPower?.let { stat("Max Power", "${it}W") }
    ride.normalizedPower?.let { stat("NP", "${it}W") }
    ride.intensityFactor?.let { stat("IF", String.format(Locale.US, "%.2f", it)) }
    ride.trainingStressScore?.let { stat("TSS", String.format(Locale.US, "%.0f", it)) }

    if (listOfNotNull(ride.avgHeartRate, ride.avgCadence, ride.avgSpeedKph).isNotEmpty()) {
        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = colors.divider)
    }

    ride.avgHeartRate?.let { stat("Avg HR", "${it} bpm") }
    ride.maxHeartRate?.let { stat("Max HR", "${it} bpm") }
    ride.avgCadence?.let { stat("Avg Cadence", "${it} rpm") }
    ride.maxCadence?.let { stat("Max Cadence", "${it} rpm") }
    ride.avgSpeedKph?.let { stat("Avg Speed", UnitFormatter.speedDisplay(it, useImperial)) }
    ride.maxSpeedKph?.let { stat("Max Speed", UnitFormatter.speedDisplay(it, useImperial)) }

    if (listOfNotNull(ride.avgResistance, ride.avgIncline, ride.totalElevationGainMeters).isNotEmpty()) {
        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = colors.divider)
    }

    ride.avgResistance?.let { stat("Avg Resistance", "$it") }
    ride.maxResistance?.let { stat("Max Resistance", "$it") }
    ride.avgIncline?.let { stat("Avg Incline", String.format(Locale.US, "%.1f%%", it)) }
    ride.maxIncline?.let { stat("Max Incline", String.format(Locale.US, "%.1f%%", it)) }
    ride.totalElevationGainMeters?.let { stat("Elevation", UnitFormatter.elevationDisplay(it, useImperial)) }
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m ${s}s"
}
