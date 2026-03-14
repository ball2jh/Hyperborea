package com.nettarion.hyperborea.platform

import android.content.Context
import android.os.Environment
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.ui.admin.ExportResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FitExporter(
    private val context: Context,
    private val logger: AppLogger,
) {

    fun exportToFile(fitBytes: ByteArray, startedAtMs: Long): ExportResult {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAtMs))
        val filename = "hyperborea_$timestamp.fit"

        @Suppress("DEPRECATION")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        downloadsDir.mkdirs()
        val file = File(downloadsDir, filename)

        return try {
            file.writeBytes(fitBytes)
            logger.i(TAG, "FIT exported to ${file.absolutePath}")
            ExportResult(file.absolutePath)
        } catch (e: Exception) {
            logger.e(TAG, "Failed to write FIT to Downloads", e)
            val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (fallbackDir != null) {
                try {
                    fallbackDir.mkdirs()
                    val fallbackFile = File(fallbackDir, filename)
                    fallbackFile.writeBytes(fitBytes)
                    logger.i(TAG, "FIT exported to ${fallbackFile.absolutePath} (fallback)")
                    ExportResult(fallbackFile.absolutePath)
                } catch (e2: Exception) {
                    logger.e(TAG, "Fallback write also failed", e2)
                    ExportResult(null, error = "Failed to save: ${e.message}")
                }
            } else {
                ExportResult(null, error = "Failed to save: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "FitExporter"
    }
}
