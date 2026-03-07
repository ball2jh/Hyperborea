package com.nettarion.hyperborea.core

data class DeviceIdentity(
    val serialNumber: String? = null,
    val firmwareVersion: String? = null,
    val hardwareVersion: String? = null,
    val model: String? = null,
    val partNumber: String? = null,
)
