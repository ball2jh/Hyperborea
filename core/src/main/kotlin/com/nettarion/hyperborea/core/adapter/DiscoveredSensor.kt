package com.nettarion.hyperborea.core.adapter

data class DiscoveredSensor(
    val name: String?,
    val address: String,
    val rssi: Int,
)
