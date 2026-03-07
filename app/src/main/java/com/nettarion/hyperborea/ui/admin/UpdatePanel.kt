package com.nettarion.hyperborea.ui.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nettarion.hyperborea.platform.update.TrackState
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors

@Composable
fun UpdatePanel(
    trackState: TrackState,
    checking: Boolean,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onFinalize: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHyperboreaColors.current

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        when (trackState) {
            is TrackState.Idle -> {
                Button(
                    onClick = onCheck,
                    enabled = !checking,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    if (checking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colors.textMedium,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Check for Updates")
                }
            }
            is TrackState.Available -> {
                Text(
                    text = "Update available: ${trackState.info.version}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textHigh,
                )
                if (trackState.info.releaseNotes.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = trackState.info.releaseNotes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMedium,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onDownload) {
                    Text("Download")
                }
            }
            is TrackState.Downloading -> {
                Text(
                    text = "Downloading ${trackState.info.version}...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMedium,
                )
                Spacer(Modifier.height(8.dp))
                if (trackState.progress.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { trackState.progress.bytesDownloaded.toFloat() / trackState.progress.totalBytes },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = colors.divider,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = colors.divider,
                    )
                }
                Spacer(Modifier.height(4.dp))
                val downloaded = trackState.progress.bytesDownloaded / 1024
                val total = trackState.progress.totalBytes / 1024
                Text(
                    text = if (total > 0) "${downloaded}KB / ${total}KB" else "${downloaded}KB",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textLow,
                )
            }
            is TrackState.ReadyToInstall -> {
                Text(
                    text = "${trackState.info.version} ready to install",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textHigh,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onInstall) {
                    Text("Install Now")
                }
            }
            is TrackState.Installing -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Installing...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textHigh,
                    )
                }
            }
            is TrackState.Installed -> {
                Text(
                    text = "${trackState.info.version} installed",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.statusActive,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onFinalize) {
                    Text("Restart")
                }
            }
            is TrackState.Error -> {
                Text(
                    text = trackState.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.statusError,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
        }
    }
}
