package com.nettarion.hyperborea.core.ftms

import com.nettarion.hyperborea.core.model.DeviceType

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.test.buildDeviceInfo
import org.junit.Test

class FtmsServiceMetadataTest {

    private val testDeviceInfo = buildDeviceInfo(
        maxResistance = 24,
        minResistance = 1,
        minIncline = -6f,
        maxIncline = 40f,
        maxPower = 2000,
        minPower = 0,
        powerStep = 1,
        resistanceStep = 1.0f,
        inclineStep = 0.5f,
    )

    @Test
    fun `ftmsFeatureValue for BIKE is 8 bytes`() {
        val value = FtmsServiceMetadata.ftmsFeatureValue(DeviceType.BIKE)
        assertThat(value.size).isEqualTo(8)
    }

    @Test
    fun `ftmsFeatureValue for BIKE does not advertise spin down or wheel circumference`() {
        // Target-settings bits live in bytes 4-7 (uint32 LE). Bit 14 (Wheel Circumference) and
        // bit 15 (Spin Down Control) must stay clear: advertising spin-down invites clients into
        // a calibration flow the equipment mishandles (0 W unload target → resistance pinned at
        // max). Bit 13 (Indoor Bike Simulation) stays set.
        val value = FtmsServiceMetadata.ftmsFeatureValue(DeviceType.BIKE)
        val targetBits = (value[4].toInt() and 0xFF) or
            ((value[5].toInt() and 0xFF) shl 8) or
            ((value[6].toInt() and 0xFF) shl 16) or
            ((value[7].toInt() and 0xFF) shl 24)
        assertThat(targetBits and (1 shl 13)).isNotEqualTo(0) // Indoor Bike Simulation
        assertThat(targetBits and (1 shl 14)).isEqualTo(0)    // Wheel Circumference
        assertThat(targetBits and (1 shl 15)).isEqualTo(0)    // Spin Down Control
    }

    @Test
    fun `ftmsFeatureValue for TREADMILL is 8 bytes with correct content`() {
        val value = FtmsServiceMetadata.ftmsFeatureValue(DeviceType.TREADMILL)
        assertThat(value.size).isEqualTo(8)
        assertThat(value).isEqualTo(byteArrayOf(
            0x0D, 0xD6.toByte(), 0x00, 0x00, 0x0A, 0x00, 0x00, 0x00,
        ))
    }

    @Test
    fun `ftmsFeatureValue for ROWER is 8 bytes with correct content`() {
        val value = FtmsServiceMetadata.ftmsFeatureValue(DeviceType.ROWER)
        assertThat(value.size).isEqualTo(8)
        assertThat(value).isEqualTo(byteArrayOf(
            0x87.toByte(), 0x56, 0x00, 0x00, 0x0C, 0x00, 0x00, 0x00,
        ))
    }

    @Test
    fun `ftmsFeatureValue for ELLIPTICAL is 8 bytes with correct content`() {
        val value = FtmsServiceMetadata.ftmsFeatureValue(DeviceType.ELLIPTICAL)
        assertThat(value.size).isEqualTo(8)
        assertThat(value).isEqualTo(byteArrayOf(
            0x8F.toByte(), 0x56, 0x00, 0x00, 0x0E, 0x00, 0x00, 0x00,
        ))
    }

    @Test
    fun `serviceDataAdValue for BIKE has available flag and bike bit`() {
        val value = FtmsServiceMetadata.serviceDataAdValue(DeviceType.BIKE)
        assertThat(value).isEqualTo(byteArrayOf(0x01, 0x20, 0x00))
    }

    @Test
    fun `resistanceRangeValue encodes from DeviceInfo`() {
        val value = FtmsServiceMetadata.resistanceRangeValue(testDeviceInfo)
        assertThat(value.size).isEqualTo(6)
        // min=1 → 10 (0x0A,0x00), max=24 → 240 (0xF0,0x00), step=10 (0x0A,0x00)
        assertThat(value).isEqualTo(byteArrayOf(0x0A, 0x00, 0xF0.toByte(), 0x00, 0x0A, 0x00))
    }

    @Test
    fun `inclinationRangeValue encodes from DeviceInfo`() {
        val value = FtmsServiceMetadata.inclinationRangeValue(testDeviceInfo)
        assertThat(value.size).isEqualTo(6)
        // min=-6 → -60 (0xC4,0xFF), max=40 → 400 (0x90,0x01), step=5 (0x05,0x00)
        assertThat(value).isEqualTo(byteArrayOf(0xC4.toByte(), 0xFF.toByte(), 0x90.toByte(), 0x01, 0x05, 0x00))
    }

    @Test
    fun `powerRangeValue encodes from DeviceInfo`() {
        val value = FtmsServiceMetadata.powerRangeValue(testDeviceInfo)
        assertThat(value.size).isEqualTo(6)
        // min=0 (0x00,0x00), max=2000 (0xD0,0x07), step=1 (0x01,0x00)
        assertThat(value).isEqualTo(byteArrayOf(0x00, 0x00, 0xD0.toByte(), 0x07, 0x01, 0x00))
    }

    @Test
    fun `static values have expected content`() {
        assertThat(FtmsServiceMetadata.TRAINING_STATUS_VALUE).isEqualTo(byteArrayOf(0x00, 0x01))
        assertThat(FtmsServiceMetadata.CPS_FEATURE_VALUE).isEqualTo(byteArrayOf(0x0C, 0x00, 0x00, 0x00))
        assertThat(FtmsServiceMetadata.SENSOR_LOCATION_VALUE).isEqualTo(byteArrayOf(0x0D))
    }
}
