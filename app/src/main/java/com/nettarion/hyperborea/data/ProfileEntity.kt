package com.nettarion.hyperborea.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val weightKg: Float?,
    val heightCm: Int?,
    val age: Int?,
    val ftpWatts: Int?,
    val maxHeartRate: Int?,
    val useImperial: Boolean = false,
    val enabledBroadcasts: String,
    val createdAt: Long,
    val isActive: Boolean = false,
)
