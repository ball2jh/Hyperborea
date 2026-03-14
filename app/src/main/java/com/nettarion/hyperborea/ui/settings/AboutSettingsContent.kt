package com.nettarion.hyperborea.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nettarion.hyperborea.BuildConfig
import com.nettarion.hyperborea.ui.admin.AdminViewModel
import com.nettarion.hyperborea.ui.admin.UpdatePanel
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors

@Composable
fun AboutSettingsContent(
    adminViewModel: AdminViewModel,
) {
    val colors = LocalHyperboreaColors.current
    val trackState by adminViewModel.appTrackState.collectAsStateWithLifecycle()
    val checking by adminViewModel.checking.collectAsStateWithLifecycle()

    Text(
        text = "About",
        style = MaterialTheme.typography.headlineMedium,
        color = colors.textHigh,
    )
    Spacer(Modifier.height(16.dp))

    // Version info
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = "Version",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textMedium,
            modifier = Modifier.width(100.dp),
        )
        Text(
            text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textHigh,
        )
    }

    Spacer(Modifier.height(16.dp))
    HorizontalDivider(color = colors.divider)
    Spacer(Modifier.height(16.dp))

    // Updates
    Text(
        text = "Updates",
        style = MaterialTheme.typography.bodyLarge,
        color = colors.textHigh,
    )
    UpdatePanel(
        trackState = trackState,
        checking = checking,
        onCheck = adminViewModel::checkForUpdates,
        onDownload = adminViewModel::downloadUpdate,
        onInstall = adminViewModel::installUpdate,
        onFinalize = adminViewModel::finalizeUpdate,
        onDismiss = adminViewModel::dismissUpdate,
    )
}
