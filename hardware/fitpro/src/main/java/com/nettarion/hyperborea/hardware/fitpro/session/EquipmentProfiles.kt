package com.nettarion.hyperborea.hardware.fitpro.session

import com.nettarion.hyperborea.core.erg.EquipmentProfile

object EquipmentProfiles {

    val S22I: EquipmentProfile = EquipmentProfile(
        maxResistance = 24,
    )

    fun fromProductId(productId: Int): EquipmentProfile? = when (productId) {
        2, 3, 4 -> S22I
        else -> null
    }
}
