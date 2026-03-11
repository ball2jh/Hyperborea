package com.nettarion.hyperborea.core.profile

import com.nettarion.hyperborea.core.model.Profile
import com.nettarion.hyperborea.core.model.RideSummary
import com.nettarion.hyperborea.core.model.WorkoutSample

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ProfileRepository {
    val profiles: Flow<List<Profile>>
    val activeProfile: StateFlow<Profile?>

    suspend fun createProfile(name: String): Profile
    suspend fun updateProfile(profile: Profile)
    suspend fun deleteProfile(id: Long)
    suspend fun setActiveProfile(id: Long)

    fun getRideSummary(id: Long): Flow<RideSummary?>
    fun getRideSummaries(profileId: Long): Flow<List<RideSummary>>
    suspend fun saveRideSummary(summary: RideSummary, samples: List<WorkoutSample> = emptyList())
    suspend fun deleteRideSummary(id: Long)
    fun getWorkoutSamples(rideId: Long): Flow<List<WorkoutSample>>
}
