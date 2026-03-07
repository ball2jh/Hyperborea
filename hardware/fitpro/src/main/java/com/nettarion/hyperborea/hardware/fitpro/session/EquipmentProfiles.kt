package com.nettarion.hyperborea.hardware.fitpro.session

data class DeviceCapabilities(val maxResistance: Int, val inclineStep: Float = 0.5f)

object EquipmentProfiles {

    val S22I = DeviceCapabilities(maxResistance = 24, inclineStep = 0.5f)

    fun fromProductId(productId: Int): DeviceCapabilities? = when (productId) {
        2, 3, 4 -> S22I
        else -> null
    }
}
