package com.nettarion.hyperborea.ui.dashboard

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nettarion.hyperborea.HyperboreaService
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.adapter.AdapterState
import com.nettarion.hyperborea.core.adapter.BroadcastAdapter
import com.nettarion.hyperborea.core.adapter.BroadcastId
import com.nettarion.hyperborea.core.fit.FitActivityBuilder
import com.nettarion.hyperborea.core.model.ClientInfo
import com.nettarion.hyperborea.core.adapter.HardwareAdapter
import com.nettarion.hyperborea.core.orchestration.Orchestrator
import com.nettarion.hyperborea.core.profile.ProfileRepository
import com.nettarion.hyperborea.core.system.SystemMonitor
import com.nettarion.hyperborea.core.profile.UserPreferences
import com.nettarion.hyperborea.ui.admin.ExportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val orchestrator: Orchestrator,
    private val hardwareAdapter: HardwareAdapter,
    private val broadcastAdapters: Set<@JvmSuppressWildcards BroadcastAdapter>,
    private val systemMonitor: SystemMonitor,
    private val userPreferences: UserPreferences,
    private val profileRepository: ProfileRepository,
    private val logger: AppLogger,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val broadcastsFlow: Flow<List<BroadcastUiState>> = combine(
        buildList<Flow<*>> {
            for (adapter in broadcastAdapters.sortedBy { it.id.ordinal }) {
                add(adapter.state)
                add(adapter.connectedClients)
            }
            add(userPreferences.enabledBroadcasts)
        },
    ) { values ->
        val enabledIds = values.last() as Set<*>
        val sorted = broadcastAdapters.sortedBy { it.id.ordinal }
        sorted.mapIndexed { i, adapter ->
            val state = values[i * 2] as AdapterState
            @Suppress("UNCHECKED_CAST")
            val clients = values[i * 2 + 1] as Set<ClientInfo>
            BroadcastUiState(
                id = adapter.id,
                state = state,
                clientCount = clients.size,
                enabled = adapter.id in enabledIds,
            )
        }
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        orchestrator.state,
        hardwareAdapter.exerciseData,
        hardwareAdapter.state,
        hardwareAdapter.deviceInfo,
        combine(systemMonitor.snapshot.map { it.status }, broadcastsFlow, profileRepository.activeProfile, ::Triple),
    ) { orchState, exercise, hwState, deviceInfo, (status, broadcasts, profile) ->
        DashboardUiState(
            orchestratorState = orchState,
            exerciseData = exercise,
            hardwareState = hwState,
            deviceInfo = deviceInfo,
            broadcasts = broadcasts,
            systemStatus = status,
            profileName = profile?.name,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        DashboardUiState(
            orchestratorState = orchestrator.state.value,
            exerciseData = null,
            hardwareState = AdapterState.Inactive,
            deviceInfo = null,
            broadcasts = emptyList(),
            systemStatus = systemMonitor.snapshot.value.status,
        ),
    )

    val activeProfileId: Long?
        get() = profileRepository.activeProfile.value?.id

    fun startBroadcasting() {
        val intent = Intent(context, HyperboreaService::class.java).apply {
            action = HyperboreaService.ACTION_ACTIVATE
        }
        context.startService(intent)
    }

    fun stopBroadcasting(save: Boolean = true) {
        val action = if (save) HyperboreaService.ACTION_DEACTIVATE else HyperboreaService.ACTION_DEACTIVATE_DISCARD
        val intent = Intent(context, HyperboreaService::class.java).apply {
            this.action = action
        }
        context.startService(intent)
    }

    /** Returns elapsed seconds from the current exercise data, or 0 if not available. */
    val currentElapsedSeconds: Long
        get() = uiState.value.exerciseData?.elapsedTime ?: 0

    fun pauseBroadcasting() {
        val intent = Intent(context, HyperboreaService::class.java).apply {
            action = HyperboreaService.ACTION_PAUSE
        }
        context.startService(intent)
    }

    fun resumeBroadcasting() {
        val intent = Intent(context, HyperboreaService::class.java).apply {
            action = HyperboreaService.ACTION_RESUME
        }
        context.startService(intent)
    }

    fun toggleBroadcast(id: BroadcastId, enabled: Boolean) {
        userPreferences.setBroadcastEnabled(id, enabled)
    }

    // --- Post-save actions (View / Export after stopping) ---

    private var pendingAction: PostSaveAction = PostSaveAction.None

    private val _postSaveEvent = MutableStateFlow<PostSaveEvent?>(null)
    val postSaveEvent: StateFlow<PostSaveEvent?> = _postSaveEvent.asStateFlow()

    private val _exportResult = MutableStateFlow<ExportResult?>(null)
    val rideExportResult: StateFlow<ExportResult?> = _exportResult.asStateFlow()

    init {
        viewModelScope.launch {
            orchestrator.lastSavedRideId.collect { rideId ->
                if (rideId == null) return@collect
                when (pendingAction) {
                    PostSaveAction.View -> _postSaveEvent.value = PostSaveEvent.ViewRide(rideId)
                    PostSaveAction.Export -> exportFit(rideId)
                    PostSaveAction.None -> {}
                }
                pendingAction = PostSaveAction.None
            }
        }
    }

    fun stopAndView() {
        pendingAction = PostSaveAction.View
        stopBroadcasting(save = true)
    }

    fun stopAndExport() {
        pendingAction = PostSaveAction.Export
        stopBroadcasting(save = true)
    }

    fun consumePostSaveEvent() {
        _postSaveEvent.value = null
    }

    fun dismissExportResult() {
        _exportResult.value = null
    }

    private fun exportFit(rideId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val summary = profileRepository.getRideSummary(rideId).first() ?: return@launch
                val samples = profileRepository.getWorkoutSamples(rideId).first()
                val fitBytes = FitActivityBuilder.buildActivityFile(summary, samples, profileRepository.activeProfile.value)

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(summary.startedAt))
                val filename = "hyperborea_$timestamp.fit"

                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloadsDir.mkdirs()
                val file = File(downloadsDir, filename)

                try {
                    file.writeBytes(fitBytes)
                    _exportResult.value = ExportResult(file.absolutePath)
                    logger.i(TAG, "FIT exported to ${file.absolutePath}")
                } catch (e: Exception) {
                    logger.e(TAG, "Failed to write FIT to Downloads", e)
                    val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    if (fallbackDir != null) {
                        fallbackDir.mkdirs()
                        val fallbackFile = File(fallbackDir, filename)
                        fallbackFile.writeBytes(fitBytes)
                        _exportResult.value = ExportResult(fallbackFile.absolutePath)
                    } else {
                        _exportResult.value = ExportResult(null, error = "Failed to save: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                logger.e(TAG, "FIT export failed", e)
                _exportResult.value = ExportResult(null, error = "Export failed: ${e.message}")
            }
        }
    }

    private enum class PostSaveAction { None, View, Export }

    private companion object {
        const val TAG = "Dashboard"
    }
}

sealed interface PostSaveEvent {
    data class ViewRide(val rideId: Long) : PostSaveEvent
}
