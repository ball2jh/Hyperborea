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
        distance: Float? = null,
        calories: Int? = null,
        elapsedTime: Long = 0L,
    ) = ExerciseData(
        power = power,
        cadence = cadence,
        speed = speed,
        resistance = resistance,
        incline = null,
        heartRate = heartRate,
        distance = distance,
        calories = calories,
        elapsedTime = elapsedTime,
    )

    // --- encodeData dispatch ---

    @Test
    fun `encodeData with BIKE delegates to encodeIndoorBikeData`() {
        val data = exerciseData(speed = 25.0f, power = 100)
        val dispatched = FtmsDataEncoder.encodeData(DeviceType.BIKE, data)
        val direct = FtmsDataEncoder.encodeIndoorBikeData(data)
        assertThat(dispatched).isEqualTo(direct)
    }

    // --- dataCharacteristicShortUuid ---

    @Test
    fun `dataCharacteristicShortUuid returns correct UUID for each type`() {
        assertThat(FtmsDataEncoder.dataCharacteristicShortUuid(DeviceType.BIKE)).isEqualTo(0x2AD2)
        assertThat(FtmsDataEncoder.dataCharacteristicShortUuid(DeviceType.TREADMILL)).isEqualTo(0x2AD1)
        assertThat(FtmsDataEncoder.dataCharacteristicShortUuid(DeviceType.ROWER)).isEqualTo(0x2AD8)
        assertThat(FtmsDataEncoder.dataCharacteristicShortUuid(DeviceType.ELLIPTICAL)).isEqualTo(0x2AD4)
    }

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
        val data = exerciseData(
            speed = 30.0f, cadence = 85, resistance = 10, power = 150,
            heartRate = 140, distance = 5.5f, calories = 250, elapsedTime = 1800L,
        )
        val result = FtmsDataEncoder.encodeIndoorBikeData(data)
        val flags = uint16LE(result, 0)
        // bits: cadence(2), distance(4), resistance(5), power(6), energy(8), heartRate(9), elapsedTime(11)
        assertThat(flags and (1 shl 2)).isNotEqualTo(0)
        assertThat(flags and (1 shl 4)).isNotEqualTo(0)
        assertThat(flags and (1 shl 5)).isNotEqualTo(0)
        assertThat(flags and (1 shl 6)).isNotEqualTo(0)
        assertThat(flags and (1 shl 8)).isNotEqualTo(0)
        assertThat(flags and (1 shl 9)).isNotEqualTo(0)
        assertThat(flags and (1 shl 11)).isNotEqualTo(0)
    }

    @Test
    fun `encodeIndoorBikeData with distance sets bit 4 and encodes uint24`() {
        val data = exerciseData(distance = 2.5f) // 2.5 km = 2500 m
        val result = FtmsDataEncoder.encodeIndoorBikeData(data)
        val flags = uint16LE(result, 0)
        assertThat(flags and (1 shl 4)).isNotEqualTo(0)
        // Distance is uint24 LE at offset 4 (after flags + speed)
        val meters = uint24LE(result, 4)
        assertThat(meters).isEqualTo(2500)
    }

    @Test
    fun `encodeIndoorBikeData with calories sets bit 8 and encodes energy fields`() {
        val data = exerciseData(calories = 150)
        val result = FtmsDataEncoder.encodeIndoorBikeData(data)
        val flags = uint16LE(result, 0)
        assertThat(flags and (1 shl 8)).isNotEqualTo(0)
        // Total energy uint16 at offset 4, per-hour 0xFFFF, per-minute 0xFF
        val totalEnergy = uint16LE(result, 4)
        assertThat(totalEnergy).isEqualTo(150)
        val perHour = uint16LE(result, 6)
        assertThat(perHour).isEqualTo(0xFFFF)
        assertThat(result[8].toInt() and 0xFF).isEqualTo(0xFF)
    }

    @Test
    fun `encodeIndoorBikeData with elapsed time sets bit 11`() {
        val data = exerciseData(elapsedTime = 3600L)
        val result = FtmsDataEncoder.encodeIndoorBikeData(data)
        val flags = uint16LE(result, 0)
        assertThat(flags and (1 shl 11)).isNotEqualTo(0)
        // Elapsed time uint16 at offset 4
        val seconds = uint16LE(result, 4)
        assertThat(seconds).isEqualTo(3600)
    }

    @Test
    fun `encodeIndoorBikeData with zero elapsed time omits field`() {
        val data = exerciseData(elapsedTime = 0L)
        val result = FtmsDataEncoder.encodeIndoorBikeData(data)
        val flags = uint16LE(result, 0)
        assertThat(flags and (1 shl 11)).isEqualTo(0)
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

    // --- Training Status ---

    @Test
    fun `encodeTrainingStatus idle maps to 0x01`() {
        val result = FtmsDataEncoder.encodeTrainingStatus(2) // IDLE
        assertThat(result).isEqualTo(byteArrayOf(0x00, 0x01))
    }

    @Test
    fun `encodeTrainingStatus workout maps to manual mode 0x0D`() {
        val result = FtmsDataEncoder.encodeTrainingStatus(3) // WORKOUT
        assertThat(result).isEqualTo(byteArrayOf(0x00, 0x0D))
    }

    @Test
    fun `encodeTrainingStatus warm up maps to 0x02`() {
        val result = FtmsDataEncoder.encodeTrainingStatus(7) // WARM_UP
        assertThat(result).isEqualTo(byteArrayOf(0x00, 0x02))
    }

    @Test
    fun `encodeTrainingStatus cool down maps to 0x0B`() {
        val result = FtmsDataEncoder.encodeTrainingStatus(8) // COOL_DOWN
        assertThat(result).isEqualTo(byteArrayOf(0x00, 0x0B))
    }

    @Test
    fun `encodeTrainingStatus post workout maps to 0x0F`() {
        val result = FtmsDataEncoder.encodeTrainingStatus(5) // WORKOUT_RESULTS
        assertThat(result).isEqualTo(byteArrayOf(0x00, 0x0F))
    }

    @Test
    fun `encodeTrainingStatus null maps to other 0x00`() {
        val result = FtmsDataEncoder.encodeTrainingStatus(null)
        assertThat(result).isEqualTo(byteArrayOf(0x00, 0x00))
    }

    @Test
    fun `encodeTrainingStatus unknown value maps to other 0x00`() {
        val result = FtmsDataEncoder.encodeTrainingStatus(99)
        assertThat(result).isEqualTo(byteArrayOf(0x00, 0x00))
    }

    // --- Helpers ---

    private fun uint16LE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun sint16LE(data: ByteArray, offset: Int): Int {
        val raw = uint16LE(data, offset)
        return if (raw >= 0x8000) raw - 0x10000 else raw
    }

    private fun uint24LE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16)

    private fun uint32LE(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)
}
