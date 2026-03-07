package com.nettarion.hyperborea.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ProfileRepository {
    val profiles: Flow<List<Profile>>
    val activeProfile: StateFlow<Profile?>

    suspend fun createProfile(name: String): Profile
    suspend fun updateProfile(profile: Profile)
    suspend fun deleteProfile(id: Long)
    suspend fun setActiveProfile(id: Long)

    fun getRideSummaries(profileId: Long): Flow<List<RideSummary>>
    suspend fun saveRideSummary(summary: RideSummary, samples: List<WorkoutSample> = emptyList())
    suspend fun deleteRideSummary(id: Long)
    fun getWorkoutSamples(rideId: Long): Flow<List<WorkoutSample>>
}
