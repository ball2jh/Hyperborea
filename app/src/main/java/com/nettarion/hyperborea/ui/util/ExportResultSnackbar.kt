package com.nettarion.hyperborea.ui.util

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nettarion.hyperborea.ui.admin.ExportResult
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors
import kotlinx.coroutines.delay

private const val SNACKBAR_DURATION_MS = 4000L

class ExportSnackbarState {
    var message: String? by mutableStateOf(null)
        internal set
}

@Composable
fun rememberExportSnackbarState(
    exportResult: ExportResult?,
    onDismiss: () -> Unit,
): ExportSnackbarState {
    val state = remember { ExportSnackbarState() }

    LaunchedEffect(exportResult) {
        val result = exportResult ?: return@LaunchedEffect
        state.message = result.error ?: "Exported to ${result.filePath}"
        onDismiss()
        delay(SNACKBAR_DURATION_MS)
        state.message = null
    }

    return state
}

@Composable
fun ExportResultSnackbar(
    state: ExportSnackbarState,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHyperboreaColors.current
    state.message?.let { message ->
        Snackbar(
            modifier = modifier.padding(16.dp),
            containerColor = colors.divider,
            contentColor = colors.textHigh,
        ) {
            Text(text = message, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
        }
    }
}
