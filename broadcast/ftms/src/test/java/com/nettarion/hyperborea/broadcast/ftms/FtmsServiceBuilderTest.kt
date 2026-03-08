package com.nettarion.hyperborea.broadcast.ftms

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.test.buildDeviceInfo
import org.junit.Test

class FtmsServiceBuilderTest {

    private val testDeviceInfo = buildDeviceInfo(
        maxResistance = 24,
        minResistance = 1,
        minIncline = -10f,
        maxIncline = 20f,
        maxPower = 2000,
    )

    @Test
    fun `FTMS Feature static value is 8 bytes`() {
        val value = FtmsServiceBuilder.staticValueFor(FtmsServiceBuilder.FTMS_FEATURE_UUID, testDeviceInfo)
        assertThat(value).isNotNull()
        assertThat(value!!.size).isEqualTo(8)
        assertThat(value).isEqualTo(FtmsServiceBuilder.ftmsFeatureValue(DeviceType.BIKE))
    }

    @Test
    fun `CPS Feature static value is 4 bytes`() {
        val value = FtmsServiceBuilder.staticValueFor(FtmsServiceBuilder.CPS_FEATURE_UUID, testDeviceInfo)
        assertThat(value).isNotNull()
        assertThat(value!!.size).isEqualTo(4)
    }

    @Test
    fun `Supported Resistance Range encodes from DeviceInfo`() {
        val value = FtmsServiceBuilder.staticValueFor(FtmsServiceBuilder.SUPPORTED_RESISTANCE_UUID, testDeviceInfo)
        assertThat(value).isNotNull()
        assertThat(value!!.size).isEqualTo(6)
        // min=1 → 10 (0x0A,0x00), max=24 → 240 (0xF0,0x00), step=10 (0x0A,0x00)
        assertThat(value).isEqualTo(byteArrayOf(0x0A, 0x00, 0xF0.toByte(), 0x00, 0x0A, 0x00))
    }

    @Test
    fun `Supported Inclination Range encodes from DeviceInfo`() {
        val value = FtmsServiceBuilder.staticValueFor(FtmsServiceBuilder.SUPPORTED_INCLINATION_UUID, testDeviceInfo)
        assertThat(value).isNotNull()
        assertThat(value!!.size).isEqualTo(6)
        // min=-10 → -100 (0x9C,0xFF), max=20 → 200 (0xC8,0x00), step=5 (0x05,0x00)
        assertThat(value).isEqualTo(byteArrayOf(0x9C.toByte(), 0xFF.toByte(), 0xC8.toByte(), 0x00, 0x05, 0x00))
    }

    @Test
    fun `Supported Power Range encodes from DeviceInfo`() {
        val value = FtmsServiceBuilder.staticValueFor(FtmsServiceBuilder.SUPPORTED_POWER_UUID, testDeviceInfo)
        assertThat(value).isNotNull()
        assertThat(value!!.size).isEqualTo(6)
        // min=0 (0x00,0x00), max=2000 (0xD0,0x07), step=1 (0x01,0x00)
        assertThat(value).isEqualTo(byteArrayOf(0x00, 0x00, 0xD0.toByte(), 0x07, 0x01, 0x00))
    }

    @Test
    fun `unknown UUID returns null`() {
        val value = FtmsServiceBuilder.staticValueFor(FtmsServiceBuilder.dataCharacteristicUuid(DeviceType.BIKE), testDeviceInfo)
        assertThat(value).isNull()
    }
}
