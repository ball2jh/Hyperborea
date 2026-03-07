package com.nettarion.hyperborea.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "workout_samples",
    foreignKeys = [ForeignKey(
        entity = RideSummaryEntity::class,
        parentColumns = ["id"],
        childColumns = ["rideId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("rideId")],
)
data class WorkoutSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rideId: Long,
    val timestampSeconds: Long,
    val power: Int?,
    val cadence: Int?,
    val speedKph: Float?,
    val heartRate: Int?,
    val resistance: Int?,
    val incline: Float?,
    val calories: Int?,
    val distanceKm: Float?,
)
