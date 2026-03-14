package com.nettarion.hyperborea.core.model

data class Profile(
    val id: Long = 0,
    val name: String,
    val weightKg: Float? = null,
    val heightCm: Int? = null,
    val age: Int? = null,
    val ftpWatts: Int? = null,
    val maxHeartRate: Int? = null,
    val useImperial: Boolean = true,
    val createdAt: Long = 0,
)
