package com.nettarion.hyperborea.sensor.hrm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HrmGattCallbackTest {

    @Test
    fun `parse uint8 heart rate`() {
        // Flags=0x00 (uint8 format), HR=72
        val data = byteArrayOf(0x00, 72)
        assertThat(HrmGattCallback.parseHeartRate(data)).isEqualTo(72)
    }

    @Test
    fun `parse uint16 heart rate`() {
        // Flags=0x01 (uint16 format), HR=0x00C8 = 200
        val data = byteArrayOf(0x01, 0xC8.toByte(), 0x00)
        assertThat(HrmGattCallback.parseHeartRate(data)).isEqualTo(200)
    }

    @Test
    fun `parse uint16 heart rate large value`() {
        // Flags=0x01 (uint16 format), HR=0x0100 = 256
        val data = byteArrayOf(0x01, 0x00, 0x01)
        assertThat(HrmGattCallback.parseHeartRate(data)).isEqualTo(256)
    }

    @Test
    fun `parse with sensor contact flags`() {
        // Flags=0x06 (uint8 format, sensor contact detected+supported), HR=85
        val data = byteArrayOf(0x06, 85)
        assertThat(HrmGattCallback.parseHeartRate(data)).isEqualTo(85)
    }

    @Test
    fun `parse empty data returns zero`() {
        assertThat(HrmGattCallback.parseHeartRate(byteArrayOf())).isEqualTo(0)
    }

    @Test
    fun `parse single byte returns zero`() {
        // Only flags, no HR value
        assertThat(HrmGattCallback.parseHeartRate(byteArrayOf(0x00))).isEqualTo(0)
    }

    @Test
    fun `parse uint16 with insufficient bytes falls back to uint8`() {
        // Flags=0x01 (uint16), but only 2 bytes total — falls back to uint8 parsing
        assertThat(HrmGattCallback.parseHeartRate(byteArrayOf(0x01, 0x50))).isEqualTo(80)
    }

    @Test
    fun `parse zero heart rate`() {
        val data = byteArrayOf(0x00, 0)
        assertThat(HrmGattCallback.parseHeartRate(data)).isEqualTo(0)
    }

    @Test
    fun `parse uint8 max value`() {
        val data = byteArrayOf(0x00, 0xFF.toByte())
        assertThat(HrmGattCallback.parseHeartRate(data)).isEqualTo(255)
    }
}
