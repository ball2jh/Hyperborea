package com.nettarion.hyperborea.core

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
    )

    @Test
    fun `ftmsFeatureValue for BIKE is 8 bytes`() {
        val value = FtmsServiceMetadata.ftmsFeatureValue(DeviceType.BIKE)
        assertThat(value.size).isEqualTo(8)
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
