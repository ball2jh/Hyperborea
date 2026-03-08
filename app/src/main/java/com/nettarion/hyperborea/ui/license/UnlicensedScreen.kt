package com.nettarion.hyperborea.ui.license

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors

@Composable
fun UnlicensedScreen(
    onLinkDevice: () -> Unit,
) {
    val colors = LocalHyperboreaColors.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(48.dp),
        ) {
            Text(
                text = "Device Not Linked",
                style = MaterialTheme.typography.headlineLarge,
                color = colors.textHigh,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Link this device to your Hyperborea account to get started.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textMedium,
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(onClick = onLinkDevice) {
                Text("Link Device")
            }
        }
    }
}
