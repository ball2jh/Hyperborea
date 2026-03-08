package com.nettarion.hyperborea.core.ftms

import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.DeviceType

import com.nettarion.hyperborea.core.ftms.ByteUtils.sint16LE
import com.nettarion.hyperborea.core.ftms.ByteUtils.uint16LE

object FtmsServiceMetadata {

    // Service short UUIDs
    const val FTMS_SERVICE = 0x1826
    const val CPS_SERVICE = 0x1818

    // FTMS characteristic short UUIDs
    const val FTMS_FEATURE = 0x2ACC
    const val SUPPORTED_RESISTANCE = 0x2AD6
    const val SUPPORTED_INCLINATION = 0x2AD5
    const val SUPPORTED_POWER = 0x2AD7
    const val FTMS_CONTROL_POINT = 0x2AD9
    const val TRAINING_STATUS = 0x2AD3
    const val FITNESS_MACHINE_STATUS = 0x2ADA

    // CPS characteristic short UUIDs
    const val CPS_FEATURE = 0x2A65
    const val SENSOR_LOCATION = 0x2A5D
    const val CPS_MEASUREMENT = 0x2A63

    // CCCD
    const val CCCD = 0x2902

    // Wahoo proprietary
    const val WAHOO_CONTROL = 0xE005

    // Static read values (device-independent)
    val TRAINING_STATUS_VALUE = byteArrayOf(0x00, 0x01)
    val CPS_FEATURE_VALUE = byteArrayOf(0x0C, 0x00, 0x00, 0x00)
    val SENSOR_LOCATION_VALUE = byteArrayOf(0x0D)

    fun ftmsFeatureValue(deviceType: DeviceType): ByteArray = when (deviceType) {
        DeviceType.BIKE -> byteArrayOf(
            0x8F.toByte(), 0x56, 0x00, 0x00, 0x0E, 0xE0.toByte(), 0x00, 0x00,
        )
        DeviceType.TREADMILL -> byteArrayOf(
            0x0D, 0xD6.toByte(), 0x00, 0x00, 0x0A, 0x00, 0x00, 0x00,
        )
        DeviceType.ROWER -> byteArrayOf(
            0x87.toByte(), 0x56, 0x00, 0x00, 0x0C, 0x00, 0x00, 0x00,
        )
        DeviceType.ELLIPTICAL -> byteArrayOf(
            0x8F.toByte(), 0x56, 0x00, 0x00, 0x0E, 0x00, 0x00, 0x00,
        )
    }

    // Service Data AD Type (Section 3.1): Flags + Fitness Machine Type
    fun serviceDataAdValue(deviceType: DeviceType): ByteArray = when (deviceType) {
        DeviceType.BIKE -> byteArrayOf(0x01, 0x20, 0x00)       // bit 5
        DeviceType.TREADMILL -> byteArrayOf(0x01, 0x01, 0x00)  // bit 0
        DeviceType.ROWER -> byteArrayOf(0x01, 0x10, 0x00)      // bit 4
        DeviceType.ELLIPTICAL -> byteArrayOf(0x01, 0x02, 0x00) // bit 1
    }

    fun resistanceRangeValue(info: DeviceInfo): ByteArray =
        sint16LE(info.minResistance * 10) + sint16LE(info.maxResistance * 10) + uint16LE((info.resistanceStep * 10).toInt())

    fun inclinationRangeValue(info: DeviceInfo): ByteArray =
        sint16LE((info.minIncline * 10).toInt()) + sint16LE((info.maxIncline * 10).toInt()) + uint16LE((info.inclineStep * 10).toInt())

    fun powerRangeValue(info: DeviceInfo): ByteArray =
        sint16LE(info.minPower) + sint16LE(info.maxPower) + uint16LE(info.powerStep)
}
