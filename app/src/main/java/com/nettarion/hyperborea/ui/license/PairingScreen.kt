package com.nettarion.hyperborea.ui.license

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nettarion.hyperborea.BuildConfig
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors
import kotlinx.coroutines.delay

@Composable
fun PairingScreen(
    pairingToken: String,
    pairingCode: String,
    expiresAt: Long,
    onCancel: () -> Unit,
) {
    val colors = LocalHyperboreaColors.current
    val qrContent = "${BuildConfig.SERVER_URL}/link/$pairingToken"
    val qrBitmap = remember(pairingToken) {
        QrCodeGenerator.generate(qrContent, 512)
    }

    var timeLeftSeconds by remember { mutableIntStateOf(0) }

    LaunchedEffect(expiresAt) {
        while (true) {
            val remaining = ((expiresAt - System.currentTimeMillis()) / 1000).toInt()
            timeLeftSeconds = remaining.coerceAtLeast(0)
            if (remaining <= 0) break
            delay(1000)
        }
    }

    val formattedCode = "${pairingCode.take(3)} ${pairingCode.drop(3)}"
    val formattedTime = "${timeLeftSeconds / 60}:${(timeLeftSeconds % 60).toString().padStart(2, '0')}"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(48.dp),
            horizontalArrangement = Arrangement.spacedBy(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // QR code
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = "Pairing QR code",
                modifier = Modifier.size(400.dp),
            )

            // Info column
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.widthIn(max = 500.dp),
            ) {
                Text(
                    text = "Link Your Device",
                    style = MaterialTheme.typography.headlineLarge,
                    color = colors.textHigh,
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Scan the QR code with your phone, or enter this code at",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = BuildConfig.SERVER_URL.removePrefix("https://"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.electricBlue,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Large pairing code
                Text(
                    text = formattedCode,
                    fontSize = 56.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = colors.textHigh,
                    letterSpacing = 6.sp,
                    maxLines = 1,
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Countdown
                Text(
                    text = if (timeLeftSeconds > 0) "Expires in $formattedTime" else "Expired",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (timeLeftSeconds > 0) colors.textMedium else colors.statusError,
                )

                Spacer(modifier = Modifier.height(32.dp))

                TextButton(onClick = onCancel) {
                    Text("Cancel", color = colors.textMedium)
                }
            }
        }
    }
}
