package com.nettarion.hyperborea.ui.dashboard

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nettarion.hyperborea.core.adapter.AdapterState
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.orchestration.OrchestratorState
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun StatusBar(
    orchestratorState: OrchestratorState,
    broadcasts: List<BroadcastUiState>,
    exerciseData: ExerciseData?,
    profileName: String?,
    deviceName: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    onProfileClick: (() -> Unit)? = null,
) {
    val colors = LocalHyperboreaColors.current
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var currentTime by remember { mutableStateOf(timeFormat.format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            val delayMs = 60_000L - (now % 60_000L)
            delay(delayMs)
            currentTime = timeFormat.format(Date())
        }
    }

    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(48.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: status info
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(orchestratorState)
            Spacer(Modifier.width(8.dp))
            Text(
                text = orchestratorState.displayText(),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (deviceName != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "\u00B7",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textLow,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textLow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (profileName != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "— $profileName",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textMedium,
                )
            }

            // Workout mode badge
            val controlMode = exerciseData?.controlModeLabel()
            if (controlMode != null && (orchestratorState is OrchestratorState.Running || orchestratorState is OrchestratorState.Paused)) {
                Spacer(Modifier.width(16.dp))
                Text(
                    text = controlMode,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.electricBlue,
                    modifier = Modifier
                        .border(
                            BorderStroke(1.dp, colors.electricBlue),
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }

        // Center: action buttons
        when (orchestratorState) {
            is OrchestratorState.Idle, is OrchestratorState.Error -> {
                OutlinedButton(
                    onClick = onStart,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.accentWarm),
                    border = BorderStroke(1.dp, colors.accentWarm),
                ) {
                    Text("\u25B6  START", style = MaterialTheme.typography.titleSmall)
                }
            }
            is OrchestratorState.Running -> {
                OutlinedButton(
                    onClick = onPause,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.accentWarm),
                    border = BorderStroke(1.dp, colors.accentWarm),
                ) {
                    Text("\u2759\u2759  PAUSE", style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onStop,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.statusError),
                    border = BorderStroke(1.dp, colors.statusError),
                ) {
                    Text("\u25A0  STOP", style = MaterialTheme.typography.titleSmall)
                }
            }
            is OrchestratorState.Paused -> {
                OutlinedButton(
                    onClick = onResume,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.statusActive),
                    border = BorderStroke(1.dp, colors.statusActive),
                ) {
                    Text("\u25B6  RESUME", style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onStop,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.statusError),
                    border = BorderStroke(1.dp, colors.statusError),
                ) {
                    Text("\u25A0  STOP", style = MaterialTheme.typography.titleSmall)
                }
            }
            is OrchestratorState.Preparing, is OrchestratorState.Stopping -> {
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = colors.textMedium,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (orchestratorState is OrchestratorState.Preparing) "PREPARING" else "STOPPING",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }

        // Right: broadcast badges + settings
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            broadcasts.forEach { broadcast ->
                Spacer(Modifier.width(16.dp))
                BroadcastBadge(broadcast)
            }
            Spacer(Modifier.width(16.dp))
            Text(
                text = currentTime,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textMedium,
            )
            if (onProfileClick != null) {
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onProfileClick) {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = "Profile",
                        tint = colors.textLow,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "Quick Settings",
                    tint = colors.textLow,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
    }
}

@Composable
private fun StatusDot(state: OrchestratorState) {
    val colors = LocalHyperboreaColors.current
    val dotColor = when (state) {
        is OrchestratorState.Running -> if (state.degraded != null) colors.accentWarm else colors.statusActive
        is OrchestratorState.Paused -> colors.accentWarm
        is OrchestratorState.Error -> colors.statusError
        is OrchestratorState.Preparing, is OrchestratorState.Stopping -> colors.accentWarm
        is OrchestratorState.Idle -> colors.statusIdle
    }

    val isPulsing = state is OrchestratorState.Preparing || state is OrchestratorState.Stopping || state is OrchestratorState.Paused
    val alpha = if (isPulsing) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseAlpha",
        )
        pulseAlpha
    } else {
        1f
    }

    Box(
        modifier = Modifier
            .size(12.dp)
            .alpha(alpha)
            .background(dotColor, CircleShape),
    )
}

@Composable
private fun BroadcastBadge(broadcast: BroadcastUiState) {
    val colors = LocalHyperboreaColors.current
    val dotColor = when (broadcast.state) {
        is AdapterState.Active -> colors.statusActive
        is AdapterState.Activating -> colors.accentWarm
        is AdapterState.Error -> colors.statusError
        is AdapterState.Inactive -> colors.statusIdle
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = broadcast.id.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = colors.textMedium,
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(dotColor, CircleShape),
        )
    }
}

private fun OrchestratorState.displayText(): String = when (this) {
    is OrchestratorState.Idle -> "Idle"
    is OrchestratorState.Preparing -> step
    is OrchestratorState.Running -> if (degraded != null) "Degraded: $degraded" else "Broadcasting"
    is OrchestratorState.Paused -> "Paused"
    is OrchestratorState.Error -> "Error: $message"
    is OrchestratorState.Stopping -> "Stopping"
}

/**
 * Derives a human-readable control mode label from exercise data.
 * Returns null when no external control is active (manual riding).
 */
internal fun ExerciseData.controlModeLabel(): String? {
    // targetPower means ERG mode (Zwift is controlling wattage)
    if (targetPower != null) return "ERG ${targetPower}W"
    // targetIncline means simulation mode (Zwift is controlling gradient)
    if (targetIncline != null) return "SIM ${String.format(Locale.US, "%.1f", targetIncline)}%"
    // targetResistance means direct resistance control
    if (targetResistance != null) return "RES $targetResistance"
    // targetSpeed means speed control
    if (targetSpeed != null) return "SPD ${String.format(Locale.US, "%.1f", targetSpeed)} km/h"
    return null
}
