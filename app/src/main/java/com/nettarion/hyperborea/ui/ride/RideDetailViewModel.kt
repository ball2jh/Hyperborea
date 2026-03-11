package com.nettarion.hyperborea.ui.ride

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.fit.FitActivityBuilder
import com.nettarion.hyperborea.core.model.Profile
import com.nettarion.hyperborea.core.model.RideSummary
import com.nettarion.hyperborea.core.model.WorkoutSample
import com.nettarion.hyperborea.core.profile.ProfileRepository
import com.nettarion.hyperborea.ui.admin.ExportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class RideDetailViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val logger: AppLogger,
    application: Application,
) : AndroidViewModel(application) {

    private val _rideId = MutableStateFlow<Long?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val rideSummary: StateFlow<RideSummary?> = _rideId
        .filterNotNull()
        .flatMapLatest { profileRepository.getRideSummary(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val samples: StateFlow<List<WorkoutSample>> = _rideId
        .filterNotNull()
        .flatMapLatest { profileRepository.getWorkoutSamples(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val profile: StateFlow<Profile?> = profileRepository.activeProfile

    private val _exportResult = MutableStateFlow<ExportResult?>(null)
    val exportResult: StateFlow<ExportResult?> = _exportResult.asStateFlow()

    fun load(rideId: Long) {
        _rideId.value = rideId
    }

    fun exportFit() {
        val summary = rideSummary.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sampleList = samples.value
                val fitBytes = FitActivityBuilder.buildActivityFile(summary, sampleList, profile.value)

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
                    val fallbackDir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    if (fallbackDir != null) {
                        fallbackDir.mkdirs()
                        val fallbackFile = File(fallbackDir, filename)
                        fallbackFile.writeBytes(fitBytes)
                        _exportResult.value = ExportResult(fallbackFile.absolutePath)
                        logger.i(TAG, "FIT exported to ${fallbackFile.absolutePath} (fallback)")
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

    fun dismissExportResult() {
        _exportResult.value = null
    }

    private companion object {
        const val TAG = "RideDetail"
    }
}
