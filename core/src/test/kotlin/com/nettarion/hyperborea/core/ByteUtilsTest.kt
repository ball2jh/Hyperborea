package com.nettarion.hyperborea.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ByteUtilsTest {

    @Test
    fun `uint16LE encodes zero`() {
        assertThat(ByteUtils.uint16LE(0)).isEqualTo(byteArrayOf(0x00, 0x00))
    }

    @Test
    fun `uint16LE encodes max unsigned`() {
        assertThat(ByteUtils.uint16LE(0xFFFF)).isEqualTo(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
    }

    @Test
    fun `uint16LE encodes little-endian`() {
        // 0x0102 → low byte 0x02, high byte 0x01
        assertThat(ByteUtils.uint16LE(0x0102)).isEqualTo(byteArrayOf(0x02, 0x01))
    }

    @Test
    fun `sint16LE encodes positive value`() {
        assertThat(ByteUtils.sint16LE(100)).isEqualTo(byteArrayOf(0x64, 0x00))
    }

    @Test
    fun `sint16LE encodes negative value`() {
        // -60 → 0xFFC4 → low=0xC4, high=0xFF
        assertThat(ByteUtils.sint16LE(-60)).isEqualTo(byteArrayOf(0xC4.toByte(), 0xFF.toByte()))
    }

    @Test
    fun `sint16LE clamps to min`() {
        val result = ByteUtils.sint16LE(-40000)
        // Should clamp to -32768 = 0x8000
        assertThat(result).isEqualTo(byteArrayOf(0x00, 0x80.toByte()))
    }

    @Test
    fun `sint16LE clamps to max`() {
        val result = ByteUtils.sint16LE(40000)
        // Should clamp to 32767 = 0x7FFF
        assertThat(result).isEqualTo(byteArrayOf(0xFF.toByte(), 0x7F))
    }

    @Test
    fun `uint24LE encodes three bytes little-endian`() {
        // 2500 = 0x0009C4 → C4, 09, 00
        val result = ByteUtils.uint24LE(2500L)
        assertThat(result).isEqualTo(byteArrayOf(0xC4.toByte(), 0x09, 0x00))
    }

    @Test
    fun `uint24LE encodes max`() {
        val result = ByteUtils.uint24LE(0xFFFFFFL)
        assertThat(result).isEqualTo(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
    }

    @Test
    fun `putUint32LE writes four bytes little-endian`() {
        val dest = ByteArray(6)
        ByteUtils.putUint32LE(dest, 1, 0x01020304L)
        assertThat(dest[0]).isEqualTo(0x00) // untouched
        assertThat(dest[1]).isEqualTo(0x04)
        assertThat(dest[2]).isEqualTo(0x03)
        assertThat(dest[3]).isEqualTo(0x02)
        assertThat(dest[4]).isEqualTo(0x01)
        assertThat(dest[5]).isEqualTo(0x00) // untouched
    }
}
