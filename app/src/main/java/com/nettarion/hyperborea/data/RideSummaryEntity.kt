package com.nettarion.hyperborea.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ride_summaries",
    foreignKeys = [ForeignKey(
        entity = ProfileEntity::class,
        parentColumns = ["id"],
        childColumns = ["profileId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("profileId")],
)
data class RideSummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val startedAt: Long,
    val durationSeconds: Long,
    val distanceKm: Float,
    val calories: Int,
    val avgPower: Int?,
    val maxPower: Int?,
    val avgCadence: Int?,
    val maxCadence: Int?,
    val avgSpeedKph: Float?,
    val maxSpeedKph: Float?,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
    val avgResistance: Int?,
    val maxResistance: Int?,
    val avgIncline: Float?,
    val maxIncline: Float?,
    val totalElevationGainMeters: Float?,
    val normalizedPower: Int?,
    val intensityFactor: Float?,
    val trainingStressScore: Float?,
)
