package com.nettarion.hyperborea.ui.dashboard

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nettarion.hyperborea.BuildConfig
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors

@Composable
fun BottomBar(
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHyperboreaColors.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textLow,
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSettingsClick) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "Settings",
                tint = colors.textLow,
            )
        }
    }
}
