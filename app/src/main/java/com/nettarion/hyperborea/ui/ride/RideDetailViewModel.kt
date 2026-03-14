package com.nettarion.hyperborea.ui.ride

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.fitfile.FitActivityBuilder
import com.nettarion.hyperborea.core.model.Profile
import com.nettarion.hyperborea.core.model.DerivedMetrics
import com.nettarion.hyperborea.core.model.RideSummary
import com.nettarion.hyperborea.core.model.WorkoutSample
import com.nettarion.hyperborea.core.model.computeDerivedMetrics
import com.nettarion.hyperborea.core.profile.ProfileRepository
import com.nettarion.hyperborea.platform.FitExporter
import com.nettarion.hyperborea.ui.admin.ExportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RideDetailViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val logger: AppLogger,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val fitExporter = FitExporter(context, logger)

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

    val derivedMetrics: StateFlow<DerivedMetrics?> = combine(rideSummary, samples, profile) { s, sa, p ->
        if (s != null) computeDerivedMetrics(s, sa, p) else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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
                _exportResult.value = fitExporter.exportToFile(fitBytes, summary.startedAt)
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
