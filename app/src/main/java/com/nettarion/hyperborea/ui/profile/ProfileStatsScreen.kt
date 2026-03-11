package com.nettarion.hyperborea.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nettarion.hyperborea.core.model.RideSummary
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors
import com.nettarion.hyperborea.ui.util.UnitFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Suppress("DEPRECATION")
@Composable
fun ProfileStatsScreen(
    profileId: Long,
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
    onSwitchProfile: () -> Unit,
    viewModel: ProfileStatsViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val rides by viewModel.rideSummaries.collectAsStateWithLifecycle()
    val stats by viewModel.aggregateStats.collectAsStateWithLifecycle()
    val colors = LocalHyperboreaColors.current

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        // Left panel — profile info
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(0.dp)
                .weight(0.4f)
                .padding(24.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.textHigh,
                )
            }

            Spacer(Modifier.height(16.dp))

            profile?.let { p ->
                val initials = p.name.take(2).uppercase()
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(colors.electricBlue, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = initials,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textHigh,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(p.name, style = MaterialTheme.typography.headlineSmall, color = colors.textHigh)

                Spacer(Modifier.height(8.dp))
                val details = buildList {
                    p.weightKg?.let { add(UnitFormatter.weightDisplay(it, p.useImperial)) }
                    p.heightCm?.let { add(UnitFormatter.heightDisplay(it, p.useImperial)) }
                    p.age?.let { add("age $it") }
                    p.ftpWatts?.let { add("FTP ${it}W") }
                    p.maxHeartRate?.let { add("MaxHR ${it}bpm") }
                }
                if (details.isNotEmpty()) {
                    Text(
                        text = details.joinToString(" | "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMedium,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = colors.divider)
            Spacer(Modifier.height(16.dp))

            val imperial = profile?.useImperial == true
            StatRow("Total Rides", "${stats.totalRides}")
            StatRow("Total Distance", UnitFormatter.distanceDisplay(stats.totalDistanceKm, imperial))
            StatRow("Total Calories", "${stats.totalCalories}")
            StatRow("Total Time", formatDuration(stats.totalTimeSeconds))

            Spacer(Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onEditProfile,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.electricBlue),
                ) { Text("Edit Profile") }
                OutlinedButton(
                    onClick = onSwitchProfile,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textMedium),
                ) { Text("Switch Profile") }
            }
        }

        // Divider
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(colors.divider),
        )

        // Right panel — ride history
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.6f)
                .padding(24.dp),
        ) {
            Text(
                "Ride History",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textHigh,
            )
            Spacer(Modifier.height(16.dp))

            if (rides.isEmpty()) {
                Text("No rides yet", style = MaterialTheme.typography.bodyLarge, color = colors.textMedium)
            } else {
                val useImperial = profile?.useImperial == true
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(rides, key = { it.id }) { ride ->
                        RideRow(ride = ride, useImperial = useImperial, onDelete = { viewModel.deleteRide(ride.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    val colors = LocalHyperboreaColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.textMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.textHigh, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RideRow(ride: RideSummary, useImperial: Boolean, onDelete: () -> Unit) {
    val colors = LocalHyperboreaColors.current
    var expanded by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.US) }

    val distText = UnitFormatter.distanceDisplay(ride.distanceKm, useImperial)
    fun speedText(kph: Float) = UnitFormatter.speedDisplay(kph, useImperial)

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete Ride") },
            text = {
                Text(
                    "Delete ride from ${dateFormat.format(Date(ride.startedAt))}? This cannot be undone.",
                    color = colors.textMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmation = false
                    onDelete()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = dateFormat.format(Date(ride.startedAt)),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textHigh,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(formatDuration(ride.durationSeconds), style = MaterialTheme.typography.bodySmall, color = colors.textMedium)
                Text(distText, style = MaterialTheme.typography.bodySmall, color = colors.textMedium)
                Text("${ride.calories} cal", style = MaterialTheme.typography.bodySmall, color = colors.textMedium)
                ride.avgPower?.let {
                    Text("${it}W avg", style = MaterialTheme.typography.bodySmall, color = colors.electricBlue)
                }
                IconButton(onClick = { showDeleteConfirmation = true }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete ride", tint = colors.textLow, modifier = Modifier.size(16.dp))
                }
            }
        }

        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                ride.normalizedPower?.let { MetricPair("NP", "${it}W") }
                ride.intensityFactor?.let { MetricPair("IF", String.format(Locale.US, "%.2f", it)) }
                ride.trainingStressScore?.let { MetricPair("TSS", String.format(Locale.US, "%.0f", it)) }
                ride.avgPower?.let { MetricPair("Avg Power", "${it}W") }
                ride.maxPower?.let { MetricPair("Max Power", "${it}W") }
                ride.avgCadence?.let { MetricPair("Avg Cadence", "${it}rpm") }
                ride.maxCadence?.let { MetricPair("Max Cadence", "${it}rpm") }
                ride.avgSpeedKph?.let { MetricPair("Avg Speed", speedText(it)) }
                ride.maxSpeedKph?.let { MetricPair("Max Speed", speedText(it)) }
                ride.avgHeartRate?.let { MetricPair("Avg HR", "${it}bpm") }
                ride.maxHeartRate?.let { MetricPair("Max HR", "${it}bpm") }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                ride.avgResistance?.let { MetricPair("Avg Resistance", "$it") }
                ride.maxResistance?.let { MetricPair("Max Resistance", "$it") }
                ride.avgIncline?.let { MetricPair("Avg Incline", String.format(Locale.US, "%.1f%%", it)) }
                ride.maxIncline?.let { MetricPair("Max Incline", String.format(Locale.US, "%.1f%%", it)) }
                ride.totalElevationGainMeters?.let {
                    MetricPair("Elevation", UnitFormatter.elevationDisplay(it, useImperial))
                }
            }
        }
    }
}

@Composable
private fun MetricPair(label: String, value: String) {
    val colors = LocalHyperboreaColors.current
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.textLow)
        Text(value, style = MaterialTheme.typography.bodySmall, color = colors.textHigh)
    }
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m ${s}s"
}
