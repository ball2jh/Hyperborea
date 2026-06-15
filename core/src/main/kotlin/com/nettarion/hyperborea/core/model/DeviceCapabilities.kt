package com.nettarion.hyperborea.core.model

/**
 * Equipment limits and type as reported by the MCU during a session's bring-up. Protocol-neutral:
 * V1 reads these from its startup capability fields, V2 from its subscribed limit features. The
 * adapter overlays the non-null values onto [DeviceInfo] (MCU-reported wins over catalog/type
 * defaults, but never over a user's saved custom config). Any field the console didn't report
 * stays null and the existing [DeviceInfo] value is kept.
 */
data class DeviceCapabilities(
    val maxSpeed: Float? = null,
    val minIncline: Float? = null,
    val maxIncline: Float? = null,
    val maxResistance: Int? = null,
    val maxPower: Int? = null,
    val equipmentType: DeviceType? = null,
)
