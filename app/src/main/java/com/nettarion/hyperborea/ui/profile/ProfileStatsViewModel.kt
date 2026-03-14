package com.nettarion.hyperborea.ui.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.fitfile.FitActivityBuilder
import com.nettarion.hyperborea.core.model.Profile
import com.nettarion.hyperborea.core.model.RideSummary
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AggregateStats(
    val totalRides: Int = 0,
    val totalDistanceKm: Float = 0f,
    val totalCalories: Int = 0,
    val totalTimeSeconds: Long = 0,
)

@HiltViewModel
class ProfileStatsViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val logger: AppLogger,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val fitExporter = FitExporter(context, logger)

    val profile: StateFlow<Profile?> = profileRepository.activeProfile

    @OptIn(ExperimentalCoroutinesApi::class)
    val rideSummaries: StateFlow<List<RideSummary>> = profileRepository.activeProfile
        .flatMapLatest { profile ->
            if (profile != null) profileRepository.getRideSummaries(profile.id) else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val aggregateStats: StateFlow<AggregateStats> = rideSummaries
        .map { rides ->
            AggregateStats(
                totalRides = rides.size,
                totalDistanceKm = rides.sumOf { it.distanceKm.toDouble() }.toFloat(),
                totalCalories = rides.sumOf { it.calories },
                totalTimeSeconds = rides.sumOf { it.durationSeconds },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AggregateStats())

    private val _exportResult = MutableStateFlow<ExportResult?>(null)
    val exportResult: StateFlow<ExportResult?> = _exportResult.asStateFlow()

    fun deleteRide(id: Long) {
        viewModelScope.launch {
            profileRepository.deleteRideSummary(id)
        }
    }

    fun exportRide(ride: RideSummary) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val samples = profileRepository.getWorkoutSamples(ride.id).first()
                val fitBytes = FitActivityBuilder.buildActivityFile(ride, samples, profile.value)
                _exportResult.value = fitExporter.exportToFile(fitBytes, ride.startedAt)
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
        const val TAG = "ProfileStats"
    }
}
