package com.nettarion.hyperborea.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nettarion.hyperborea.core.model.Profile
import com.nettarion.hyperborea.core.profile.ProfileRepository
import com.nettarion.hyperborea.core.model.RideSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
) : ViewModel() {

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

    fun deleteRide(id: Long) {
        viewModelScope.launch {
            profileRepository.deleteRideSummary(id)
        }
    }
}
