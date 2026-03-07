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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.nettarion.hyperborea.core.AdapterState
import com.nettarion.hyperborea.core.ExerciseData
import com.nettarion.hyperborea.core.OrchestratorState
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors

@Composable
fun StatusBar(
    orchestratorState: OrchestratorState,
    broadcasts: List<BroadcastUiState>,
    exerciseData: ExerciseData?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHyperboreaColors.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(orchestratorState)
        Spacer(Modifier.width(8.dp))
        Text(
            text = orchestratorState.displayText(),
            style = MaterialTheme.typography.titleLarge,
            color = colors.textHigh,
        )

        // Workout mode badge
        val controlMode = exerciseData?.controlModeLabel()
        if (controlMode != null && orchestratorState is OrchestratorState.Running) {
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

        Spacer(Modifier.weight(1f))

        broadcasts.forEach { broadcast ->
            Spacer(Modifier.width(16.dp))
            BroadcastBadge(broadcast)
        }

        Spacer(Modifier.width(24.dp))

        when (orchestratorState) {
            is OrchestratorState.Idle, is OrchestratorState.Error -> {
                OutlinedButton(
                    onClick = onStart,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.accentWarm),
                    border = BorderStroke(1.dp, colors.accentWarm),
                ) {
                    Text("\u25B6  START", style = MaterialTheme.typography.titleLarge)
                }
            }
            is OrchestratorState.Running -> {
                OutlinedButton(
                    onClick = onStop,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.statusError),
                    border = BorderStroke(1.dp, colors.statusError),
                ) {
                    Text("\u25A0  STOP", style = MaterialTheme.typography.titleLarge)
                }
            }
            is OrchestratorState.Preparing, is OrchestratorState.Stopping -> {
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = colors.textMedium,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (orchestratorState is OrchestratorState.Preparing) "PREPARING" else "STOPPING",
                        style = MaterialTheme.typography.titleLarge,
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
        is OrchestratorState.Running -> colors.statusActive
        is OrchestratorState.Error -> colors.statusError
        is OrchestratorState.Preparing, is OrchestratorState.Stopping -> colors.accentWarm
        is OrchestratorState.Idle -> colors.statusIdle
    }

    val isPulsing = state is OrchestratorState.Preparing || state is OrchestratorState.Stopping
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
            .size(10.dp)
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
    val clientText = if (broadcast.clientCount > 0) "${broadcast.clientCount}" else "\u2014"

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = broadcast.id.displayName,
            style = MaterialTheme.typography.titleLarge,
            color = colors.textMedium,
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = clientText,
            style = MaterialTheme.typography.titleLarge,
            color = colors.textMedium,
        )
    }
}

private fun OrchestratorState.displayText(): String = when (this) {
    is OrchestratorState.Idle -> "Idle"
    is OrchestratorState.Preparing -> step
    is OrchestratorState.Running -> "Broadcasting"
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
    if (targetIncline != null) return "SIM ${String.format("%.1f", targetIncline)}%"
    // targetResistance means direct resistance control
    if (targetResistance != null) return "RES $targetResistance"
    // targetSpeed means speed control
    if (targetSpeed != null) return "SPD ${String.format("%.1f", targetSpeed)} km/h"
    return null
}
