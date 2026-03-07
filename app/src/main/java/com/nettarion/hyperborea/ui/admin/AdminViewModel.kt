package com.nettarion.hyperborea.ui.admin

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.lifecycle.ViewModel
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.BroadcastId
import com.nettarion.hyperborea.core.DeviceIdentity
import com.nettarion.hyperborea.core.ExerciseData
import com.nettarion.hyperborea.core.HardwareAdapter
import com.nettarion.hyperborea.core.LogEntry
import com.nettarion.hyperborea.core.LogStore
import com.nettarion.hyperborea.core.SystemLogEntry
import com.nettarion.hyperborea.core.SystemLogStore
import com.nettarion.hyperborea.core.SystemMonitor
import com.nettarion.hyperborea.core.SystemSnapshot
import com.nettarion.hyperborea.core.UserPreferences
import com.nettarion.hyperborea.platform.update.TrackState
import com.nettarion.hyperborea.platform.update.UpdateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class AdminViewModel @Inject constructor(
    private val logStore: LogStore,
    private val systemLogStore: SystemLogStore,
    private val systemMonitor: SystemMonitor,
    private val hardwareAdapter: HardwareAdapter,
    private val updateManager: UpdateManager,
    private val userPreferences: UserPreferences,
    private val logger: AppLogger,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    val logEntries: StateFlow<List<LogEntry>> = logStore.entries
    val systemLogEntries: StateFlow<List<SystemLogEntry>> = systemLogStore.entries
    val systemSnapshot: StateFlow<SystemSnapshot> = systemMonitor.snapshot
    val deviceIdentity: StateFlow<DeviceIdentity?> = hardwareAdapter.deviceIdentity
    val exerciseData: StateFlow<ExerciseData?> = hardwareAdapter.exerciseData
    val appTrackState: StateFlow<TrackState> = updateManager.appTrack.state
    val checking: StateFlow<Boolean> = updateManager.checking
    val enabledBroadcasts: StateFlow<Set<BroadcastId>> = userPreferences.enabledBroadcasts

    private val _exportResult = MutableStateFlow<ExportResult?>(null)
    val exportResult: StateFlow<ExportResult?> = _exportResult.asStateFlow()

    fun clearLogs() = logStore.clear()
    fun clearSystemLogs() = systemLogStore.clear()

    fun exportLogs() {
        saveAndShare(logStore.export(), "hyperborea_app")
    }

    fun exportSystemLogs() {
        saveAndShare(systemLogStore.export(), "hyperborea_system")
    }

    fun dismissExportResult() {
        _exportResult.value = null
    }

    private fun saveAndShare(content: String, prefix: String) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "${prefix}_$timestamp.log"

        // Save to Downloads — accessible via adb pull /sdcard/Download/
        @Suppress("DEPRECATION")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        downloadsDir.mkdirs()
        val file = File(downloadsDir, filename)
        try {
            file.writeText(content)
        } catch (e: Exception) {
            logger.e(TAG, "Failed to write to Downloads", e)
            // Fall back to app-private external files dir
            val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (fallbackDir != null) {
                fallbackDir.mkdirs()
                val fallbackFile = File(fallbackDir, filename)
                fallbackFile.writeText(content)
                _exportResult.value = ExportResult(fallbackFile.absolutePath)
                return
            }
            _exportResult.value = ExportResult(null, error = "Failed to save: ${e.message}")
            return
        }

        _exportResult.value = ExportResult(file.absolutePath)
        logger.i(TAG, "Logs exported to ${file.absolutePath}")

        // Try share sheet if any app can handle it
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, content)
                putExtra(Intent.EXTRA_SUBJECT, filename)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(shareIntent, "Export logs").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (chooser.resolveActivity(context.packageManager) != null) {
                context.startActivity(chooser)
            }
        } catch (e: Exception) {
            logger.w(TAG, "Share sheet not available: ${e.message}")
        }
    }

    fun checkForUpdates() = updateManager.checkForUpdates()
    fun downloadUpdate() = updateManager.appTrack.download()
    fun installUpdate() = updateManager.appTrack.install()
    fun finalizeUpdate() = updateManager.appTrack.finalizeInstall()
    fun dismissUpdate() = updateManager.appTrack.dismiss()

    fun toggleBroadcast(id: BroadcastId, enabled: Boolean) =
        userPreferences.setBroadcastEnabled(id, enabled)

    private companion object {
        const val TAG = "AdminViewModel"
    }
}

data class ExportResult(
    val filePath: String?,
    val error: String? = null,
)
