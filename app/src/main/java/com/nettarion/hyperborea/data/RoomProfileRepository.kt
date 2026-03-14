package com.nettarion.hyperborea.data

import androidx.room.withTransaction
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.adapter.BroadcastId
import com.nettarion.hyperborea.core.model.Profile
import com.nettarion.hyperborea.core.profile.ProfileRepository
import com.nettarion.hyperborea.core.model.RideSummary
import com.nettarion.hyperborea.core.model.WorkoutSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class RoomProfileRepository(
    private val database: HyperboreaDatabase,
    private val dao: ProfileDao,
    private val logger: AppLogger,
    scope: CoroutineScope,
) : ProfileRepository {

    override val profiles: Flow<List<Profile>> = dao.getAllProfiles().map { entities ->
        entities.map { it.toDomain() }
    }

    override val activeProfile: StateFlow<Profile?> = dao.getActiveProfile()
        .map { it?.toDomain() }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun createProfile(name: String): Profile {
        val entity = ProfileEntity(
            name = name,
            weightKg = null,
            heightCm = null,
            age = null,
            ftpWatts = null,
            maxHeartRate = null,
            enabledBroadcasts = BroadcastId.entries.joinToString(",") { it.name },
            createdAt = System.currentTimeMillis(),
        )
        val id = dao.insert(entity)
        logger.i(TAG, "Created profile '$name' (id=$id)")
        return entity.copy(id = id).toDomain()
    }

    override suspend fun updateProfile(profile: Profile) {
        val existing = activeProfile.value
        val isActive = existing != null && existing.id == profile.id
        dao.update(profile.toEntity(isActive))
        logger.i(TAG, "Updated profile '${profile.name}' (id=${profile.id})")
    }

    override suspend fun deleteProfile(id: Long) {
        dao.delete(id)
        logger.i(TAG, "Deleted profile id=$id")
    }

    override suspend fun setActiveProfile(id: Long) {
        database.withTransaction {
            dao.clearActiveProfile()
            dao.setActive(id)
        }
        logger.i(TAG, "Set active profile id=$id")
    }

    override fun getRideSummary(id: Long): Flow<RideSummary?> =
        dao.getRideSummary(id).map { it?.toDomain() }

    override fun getRideSummaries(profileId: Long): Flow<List<RideSummary>> =
        dao.getRideSummaries(profileId).map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun saveRideSummary(summary: RideSummary, samples: List<WorkoutSample>): Long {
        return database.withTransaction {
            val id = dao.insertRideSummary(summary.toEntity())
            if (samples.isNotEmpty()) {
                dao.insertWorkoutSamples(samples.map { it.toEntity(id) })
            }
            logger.i(TAG, "Saved ride summary id=$id for profile=${summary.profileId} (${samples.size} samples)")
            id
        }
    }

    override fun getWorkoutSamples(rideId: Long): Flow<List<WorkoutSample>> =
        dao.getWorkoutSamples(rideId).map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun deleteRideSummary(id: Long) {
        dao.deleteRideSummary(id)
        logger.i(TAG, "Deleted ride summary id=$id")
    }

    private companion object {
        const val TAG = "ProfileRepository"
    }
}

private fun ProfileEntity.toDomain() = Profile(
    id = id,
    name = name,
    weightKg = weightKg,
    heightCm = heightCm,
    age = age,
    ftpWatts = ftpWatts,
    maxHeartRate = maxHeartRate,
    useImperial = useImperial,
    createdAt = createdAt,
)

private fun Profile.toEntity(isActive: Boolean) = ProfileEntity(
    id = id,
    name = name,
    weightKg = weightKg,
    heightCm = heightCm,
    age = age,
    ftpWatts = ftpWatts,
    maxHeartRate = maxHeartRate,
    useImperial = useImperial,
    enabledBroadcasts = BroadcastId.entries.joinToString(",") { it.name },
    createdAt = createdAt,
    isActive = isActive,
)

private fun RideSummaryEntity.toDomain() = RideSummary(
    id = id,
    profileId = profileId,
    startedAt = startedAt,
    durationSeconds = durationSeconds,
    distanceKm = distanceKm,
    calories = calories,
    avgPower = avgPower,
    maxPower = maxPower,
    avgCadence = avgCadence,
    maxCadence = maxCadence,
    avgSpeedKph = avgSpeedKph,
    maxSpeedKph = maxSpeedKph,
    avgHeartRate = avgHeartRate,
    maxHeartRate = maxHeartRate,
    avgResistance = avgResistance,
    maxResistance = maxResistance,
    avgIncline = avgIncline,
    maxIncline = maxIncline,
    totalElevationGainMeters = totalElevationGainMeters,
    normalizedPower = normalizedPower,
    intensityFactor = intensityFactor,
    trainingStressScore = trainingStressScore,
)

private fun RideSummary.toEntity() = RideSummaryEntity(
    id = id,
    profileId = profileId,
    startedAt = startedAt,
    durationSeconds = durationSeconds,
    distanceKm = distanceKm,
    calories = calories,
    avgPower = avgPower,
    maxPower = maxPower,
    avgCadence = avgCadence,
    maxCadence = maxCadence,
    avgSpeedKph = avgSpeedKph,
    maxSpeedKph = maxSpeedKph,
    avgHeartRate = avgHeartRate,
    maxHeartRate = maxHeartRate,
    avgResistance = avgResistance,
    maxResistance = maxResistance,
    avgIncline = avgIncline,
    maxIncline = maxIncline,
    totalElevationGainMeters = totalElevationGainMeters,
    normalizedPower = normalizedPower,
    intensityFactor = intensityFactor,
    trainingStressScore = trainingStressScore,
)

private fun WorkoutSampleEntity.toDomain() = WorkoutSample(
    timestampSeconds = timestampSeconds,
    power = power,
    cadence = cadence,
    speedKph = speedKph,
    heartRate = heartRate,
    resistance = resistance,
    incline = incline,
    calories = calories,
    distanceKm = distanceKm,
)

private fun WorkoutSample.toEntity(rideId: Long) = WorkoutSampleEntity(
    rideId = rideId,
    timestampSeconds = timestampSeconds,
    power = power,
    cadence = cadence,
    speedKph = speedKph,
    heartRate = heartRate,
    resistance = resistance,
    incline = incline,
    calories = calories,
    distanceKm = distanceKm,
)
