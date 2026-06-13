package com.nettarion.hyperborea.broadcast.wifi

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.test.buildDeviceInfo
import org.junit.Test

class WifiServiceDefinitionTest {

    @Test
    fun `bike exposes FTMS and Cycling Power, not RSC`() {
        val def = WifiServiceDefinition(buildDeviceInfo(type = DeviceType.BIKE))
        assertThat(def.services).containsExactly(
            WifiServiceDefinition.FTMS_SERVICE,
            WifiServiceDefinition.CPS_SERVICE,
        ).inOrder()
        assertThat(def.services).doesNotContain(WifiServiceDefinition.RSC_SERVICE)
    }

    @Test
    fun `treadmill exposes FTMS and RSC, not Cycling Power`() {
        val def = WifiServiceDefinition(buildDeviceInfo(type = DeviceType.TREADMILL))
        assertThat(def.services).containsExactly(
            WifiServiceDefinition.FTMS_SERVICE,
            WifiServiceDefinition.RSC_SERVICE,
        ).inOrder()
        assertThat(def.services).doesNotContain(WifiServiceDefinition.CPS_SERVICE)
    }

    @Test
    fun `treadmill data characteristic is Treadmill Data`() {
        val def = WifiServiceDefinition(buildDeviceInfo(type = DeviceType.TREADMILL))
        assertThat(def.dataCharacteristic).isEqualTo(ShortUuid(0x2ACD))
    }

    @Test
    fun `treadmill RSC service exposes RSC Feature and Measurement`() {
        val def = WifiServiceDefinition(buildDeviceInfo(type = DeviceType.TREADMILL))
        val chars = def.characteristicsFor(WifiServiceDefinition.RSC_SERVICE)
        assertThat(chars).isNotNull()
        val uuids = chars!!.map { it.first }
        assertThat(uuids).containsExactly(
            WifiServiceDefinition.RSC_FEATURE,
            WifiServiceDefinition.RSC_MEASUREMENT,
        )
        // RSC Measurement is notifiable; RSC Feature is readable.
        assertThat(def.isNotifiable(WifiServiceDefinition.RSC_MEASUREMENT)).isTrue()
        assertThat(def.readValue(WifiServiceDefinition.RSC_FEATURE)).isEqualTo(byteArrayOf(0x02, 0x00))
    }

    @Test
    fun `treadmill does not resolve Cycling Power characteristics`() {
        val def = WifiServiceDefinition(buildDeviceInfo(type = DeviceType.TREADMILL))
        assertThat(def.characteristicsFor(WifiServiceDefinition.CPS_SERVICE)).isNull()
        assertThat(def.readValue(WifiServiceDefinition.CPS_FEATURE)).isNull()
    }

    @Test
    fun `bike does not resolve RSC characteristics`() {
        val def = WifiServiceDefinition(buildDeviceInfo(type = DeviceType.BIKE))
        assertThat(def.characteristicsFor(WifiServiceDefinition.RSC_SERVICE)).isNull()
        assertThat(def.readValue(WifiServiceDefinition.RSC_FEATURE)).isNull()
    }
}
