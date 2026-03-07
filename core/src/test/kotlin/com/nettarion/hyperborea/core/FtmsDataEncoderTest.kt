package com.nettarion.hyperborea.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FtmsDataEncoderTest {

    private fun exerciseData(
        power: Int? = null,
        cadence: Int? = null,
        speed: Float? = null,
        resistance: Int? = null,
        heartRate: Int? = null,
    ) = ExerciseData(
        power = power,
        cadence = cadence,
        speed = speed,
        resistance = resistance,
        incline = null,
        heartRate = heartRate,
        distance = null,
        calories = null,
        elapsedTime = 0L,
    )

    // --- Indoor Bike Data ---

    @Test
    fun `encodeIndoorBikeData with all null fields produces minimal packet`() {
        val data = exerciseData()
        val result = FtmsDataEncoder.encodeIndoorBikeData(data)
        // Flags (2 bytes) + speed (2 bytes) = 4 bytes minimum
        assertThat(result.size).isEqualTo(4)
        // Flags should be 0x0000 (only speed present, bit0=0)
        assertThat(uint16LE(result, 0)).isEqualTo(0x0000)
        // Speed should be 0
        assertThat(uint16LE(result, 2)).isEqualTo(0)
    }

    @Test
    fun `encodeIndoorBikeData encodes speed at 0_01 resolution`() {
        val data = exerciseData(speed = 25.5f)
        val result = FtmsDataEncoder.encodeIndoorBikeData(data)
        // 25.5 * 100 = 2550
        assertThat(uint16LE(result, 2)).isEqualTo(2550)
    }

    @Test
    fun `encodeIndoorBikeData with cadence sets bit 2`() {
        val data = exerciseData(cadence = 90)
        val result = FtmsDataEncoder.encodeIndoorBikeData(data)
        val flags = uint16LE(result, 0)
        assertThat(flags and (1 shl 2)).isNotEqualTo(0)
        // Cadence at 0.5 resolution: 90 * 2 = 180
        assertThat(uint16LE(result, 4)).isEqualTo(180)
    }

    @Test
    fun `encodeIndoorBikeData with power sets bit 6`() {
        val data = exerciseData(power = 200)
        val result = FtmsDataEncoder.encodeIndoorBikeData(data)
        val flags = uint16LE(result, 0)
        assertThat(flags and (1 shl 6)).isNotEqualTo(0)
    }

    @Test
    fun `encodeIndoorBikeData with all fields`() {
        val data = exerciseData(speed = 30.0f, cadence = 85, resistance = 10, power = 150, heartRate = 140)
        val result = FtmsDataEncoder.encodeIndoorBikeData(data)
        val flags = uint16LE(result, 0)
        // bits: cadence(2), resistance(5), power(6), heartRate(9)
        assertThat(flags and (1 shl 2)).isNotEqualTo(0)
        assertThat(flags and (1 shl 5)).isNotEqualTo(0)
        assertThat(flags and (1 shl 6)).isNotEqualTo(0)
        assertThat(flags and (1 shl 9)).isNotEqualTo(0)
    }

    // --- CPS Measurement ---

    @Test
    fun `encodeCpsMeasurement has correct flags and power`() {
        val data = exerciseData(power = 250)
        val result = FtmsDataEncoder.encodeCpsMeasurement(data, 100, 500, 50, 250)
        // Flags = 0x0030
        assertThat(uint16LE(result, 0)).isEqualTo(0x0030)
        // Power = 250
        assertThat(sint16LE(result, 2)).isEqualTo(250)
        // Wheel revs = 100
        assertThat(uint32LE(result, 4)).isEqualTo(100L)
        // Wheel time = 500
        assertThat(uint16LE(result, 8)).isEqualTo(500)
        // Crank revs = 50
        assertThat(uint16LE(result, 10)).isEqualTo(50)
        // Crank time = 250
        assertThat(uint16LE(result, 12)).isEqualTo(250)
    }

    @Test
    fun `encodeCpsMeasurement is 14 bytes`() {
        val data = exerciseData(power = 0)
        val result = FtmsDataEncoder.encodeCpsMeasurement(data, 0, 0, 0, 0)
        assertThat(result.size).isEqualTo(14)
    }

    // --- Helpers ---

    private fun uint16LE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun sint16LE(data: ByteArray, offset: Int): Int {
        val raw = uint16LE(data, offset)
        return if (raw >= 0x8000) raw - 0x10000 else raw
    }

    private fun uint32LE(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)
}
