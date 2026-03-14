package com.nettarion.hyperborea.ui.admin

import android.content.Context
import android.content.Intent
import com.nettarion.hyperborea.core.LicenseChecker
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nettarion.hyperborea.core.adapter.AdapterState
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.adapter.BroadcastAdapter
import com.nettarion.hyperborea.core.adapter.BroadcastId
import com.nettarion.hyperborea.core.model.ClientInfo
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.model.DeviceIdentity
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.model.FanMode
import com.nettarion.hyperborea.core.adapter.HardwareAdapter
import com.nettarion.hyperborea.core.LogEntry
import com.nettarion.hyperborea.core.LogStore
import com.nettarion.hyperborea.core.system.SystemLogEntry
import com.nettarion.hyperborea.core.system.SystemLogStore
import com.nettarion.hyperborea.core.system.SystemMonitor
import com.nettarion.hyperborea.core.system.SystemSnapshot
import com.nettarion.hyperborea.core.profile.UserPreferences
import com.nettarion.hyperborea.platform.support.SupportDiagnosticsBuilder
import com.nettarion.hyperborea.platform.support.SupportHttpClient
import com.nettarion.hyperborea.platform.update.TrackState
import com.nettarion.hyperborea.platform.update.UpdateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONObject
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
    private val broadcastAdapters: Set<@JvmSuppressWildcards BroadcastAdapter>,
    private val updateManager: UpdateManager,
    private val userPreferences: UserPreferences,
    private val supportHttpClient: SupportHttpClient,
    private val licenseChecker: LicenseChecker,
    private val logger: AppLogger,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val diagnosticsBuilder = SupportDiagnosticsBuilder(
        logStore, systemLogStore, systemMonitor, hardwareAdapter, broadcastAdapters,
    )

    val broadcastDiagnostics: Flow<List<BroadcastDiagnostic>> = run {
        val sorted = broadcastAdapters.sortedBy { it.id.ordinal }
        val perAdapterFlows = sorted.map { adapter ->
            combine(adapter.state, adapter.connectedClients) { state, clients ->
                BroadcastDiagnostic(
                    id = adapter.id,
                    state = state,
                    clients = clients,
                )
            }
        }
        combine(perAdapterFlows) { it.toList() }
    }

    val logEntries: StateFlow<List<LogEntry>> = logStore.entries
    val systemLogEntries: StateFlow<List<SystemLogEntry>> = systemLogStore.entries
    val systemSnapshot: StateFlow<SystemSnapshot> = systemMonitor.snapshot
    val deviceIdentity: StateFlow<DeviceIdentity?> = hardwareAdapter.deviceIdentity
    val exerciseData: StateFlow<ExerciseData?> = hardwareAdapter.exerciseData
    val appTrackState: StateFlow<TrackState> = updateManager.appTrack.state
    val checking: StateFlow<Boolean> = updateManager.checking
    val enabledBroadcasts: StateFlow<Set<BroadcastId>> = userPreferences.enabledBroadcasts
    val overlayEnabled: StateFlow<Boolean> = userPreferences.overlayEnabled
    val fanMode: StateFlow<FanMode> = userPreferences.fanMode

    private val _exportResult = MutableStateFlow<ExportResult?>(null)
    val exportResult: StateFlow<ExportResult?> = _exportResult.asStateFlow()

    private val _supportUploadState = MutableStateFlow<SupportUploadState>(SupportUploadState.Idle)
    val supportUploadState: StateFlow<SupportUploadState> = _supportUploadState.asStateFlow()

    private val _calibrationState = MutableStateFlow<CalibrationState>(CalibrationState.Idle)
    val calibrationState: StateFlow<CalibrationState> = _calibrationState.asStateFlow()

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
                try {
                    fallbackDir.mkdirs()
                    val fallbackFile = File(fallbackDir, filename)
                    fallbackFile.writeText(content)
                    _exportResult.value = ExportResult(fallbackFile.absolutePath)
                    return
                } catch (e2: Exception) {
                    logger.e(TAG, "Fallback write also failed", e2)
                }
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

    fun toggleOverlay(enabled: Boolean) =
        userPreferences.setOverlayEnabled(enabled)

    fun setFanMode(mode: FanMode) =
        userPreferences.setFanMode(mode)

    fun uploadSupport() {
        if (_supportUploadState.value is SupportUploadState.Uploading) return
        _supportUploadState.value = SupportUploadState.Uploading

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val deviceUuid = licenseChecker.deviceUuid
                val authToken = licenseChecker.authToken

                if (authToken.isNullOrEmpty()) {
                    _supportUploadState.value = SupportUploadState.Error("Device not linked")
                    return@launch
                }

                val json = diagnosticsBuilder.build(deviceUuid)

                val response = supportHttpClient.upload(authToken, json.toString())
                if (response == null) {
                    _supportUploadState.value = SupportUploadState.Error("Upload failed")
                    return@launch
                }

                val code = JSONObject(response).optString("code", "")
                if (code.isEmpty()) {
                    _supportUploadState.value = SupportUploadState.Error("Invalid response")
                } else {
                    logger.i(TAG, "Support upload succeeded, code=$code")
                    _supportUploadState.value = SupportUploadState.Success(code)
                }
            } catch (e: Exception) {
                logger.e(TAG, "Support upload failed", e)
                _supportUploadState.value = SupportUploadState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun dismissSupportUpload() {
        _supportUploadState.value = SupportUploadState.Idle
    }

    fun calibrateIncline() {
        if (_calibrationState.value is CalibrationState.InProgress) return
        _calibrationState.value = CalibrationState.InProgress
        viewModelScope.launch(Dispatchers.IO) {
            try {
                hardwareAdapter.sendCommand(DeviceCommand.CalibrateIncline)
                logger.i(TAG, "Incline calibration completed")
                _calibrationState.value = CalibrationState.Done
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                logger.e(TAG, "Incline calibration failed", e)
                _calibrationState.value = CalibrationState.Failed(e.message ?: "Calibration failed")
            }
        }
    }

    fun dismissCalibration() {
        _calibrationState.value = CalibrationState.Idle
    }

    private companion object {
        const val TAG = "AdminViewModel"
    }
}

sealed interface CalibrationState {
    data object Idle : CalibrationState
    data object InProgress : CalibrationState
    data object Done : CalibrationState
    data class Failed(val message: String) : CalibrationState
}

sealed interface SupportUploadState {
    data object Idle : SupportUploadState
    data object Uploading : SupportUploadState
    data class Success(val code: String) : SupportUploadState
    data class Error(val message: String) : SupportUploadState
}

data class ExportResult(
    val filePath: String?,
    val error: String? = null,
)

data class BroadcastDiagnostic(
    val id: BroadcastId,
    val state: AdapterState,
    val clients: Set<ClientInfo>,
)
