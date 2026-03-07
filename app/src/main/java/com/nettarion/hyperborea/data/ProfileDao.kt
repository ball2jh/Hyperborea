package com.nettarion.hyperborea.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY createdAt ASC")
    fun getAllProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE isActive = 1 LIMIT 1")
    fun getActiveProfile(): Flow<ProfileEntity?>

    @Insert
    suspend fun insert(profile: ProfileEntity): Long

    @Update
    suspend fun update(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE profiles SET isActive = 0")
    suspend fun clearActiveProfile()

    @Query("UPDATE profiles SET isActive = 1 WHERE id = :id")
    suspend fun setActive(id: Long)

    @Query("SELECT * FROM ride_summaries WHERE profileId = :profileId ORDER BY startedAt DESC")
    fun getRideSummaries(profileId: Long): Flow<List<RideSummaryEntity>>

    @Insert
    suspend fun insertRideSummary(summary: RideSummaryEntity): Long

    @Query("DELETE FROM ride_summaries WHERE id = :id")
    suspend fun deleteRideSummary(id: Long)

    @Insert
    suspend fun insertWorkoutSamples(samples: List<WorkoutSampleEntity>)

    @Query("SELECT * FROM workout_samples WHERE rideId = :rideId ORDER BY timestampSeconds ASC")
    fun getWorkoutSamples(rideId: Long): Flow<List<WorkoutSampleEntity>>
}
