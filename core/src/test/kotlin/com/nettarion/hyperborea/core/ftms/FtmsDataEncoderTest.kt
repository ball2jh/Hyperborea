package com.nettarion.hyperborea.core.ftms

import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.model.ExerciseData

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FtmsDataEncoderTest {

    private fun exerciseData(
        power: Int? = null,
        cadence: Int? = null,
        speed: Float? = null,
        resistance: Int? = null,
        incline: Float? = null,
        heartRate: Int? = null,
        distance: Float? = null,
        calories: Int? = null,
        elapsedTime: Long = 0L,
    ) = ExerciseData(
        power = power,
        cadence = cadence,
        speed = speed,
        resistance = resistance,
        incline = incline,
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
        assertThat(FtmsDataEncoder.dataCharacteristicShortUuid(DeviceType.TREADMILL)).isEqualTo(0x2ACD)
        assertThat(FtmsDataEncoder.dataCharacteristicShortUuid(DeviceType.ROWER)).isEqualTo(0x2AD1)
        assertThat(FtmsDataEncoder.dataCharacteristicShortUuid(DeviceType.ELLIPTICAL)).isEqualTo(0x2ACE)
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

    // --- encodeData dispatch for other types ---

    @Test
    fun `encodeData with TREADMILL delegates to encodeTreadmillData`() {
        val data = exerciseData(speed = 12.0f, power = 100)
        val dispatched = FtmsDataEncoder.encodeData(DeviceType.TREADMILL, data)
        val direct = FtmsDataEncoder.encodeTreadmillData(data)
        assertThat(dispatched).isEqualTo(direct)
    }

    @Test
    fun `encodeData with ROWER delegates to encodeRowerData`() {
        val data = exerciseData(cadence = 30, power = 150)
        val dispatched = FtmsDataEncoder.encodeData(DeviceType.ROWER, data)
        val direct = FtmsDataEncoder.encodeRowerData(data)
        assertThat(dispatched).isEqualTo(direct)
    }

    @Test
    fun `encodeData with ELLIPTICAL delegates to encodeCrossTrainerData`() {
        val data = exerciseData(speed = 8.0f, cadence = 60)
        val dispatched = FtmsDataEncoder.encodeData(DeviceType.ELLIPTICAL, data)
        val direct = FtmsDataEncoder.encodeCrossTrainerData(data)
        assertThat(dispatched).isEqualTo(direct)
    }

    // --- Treadmill Data ---

    @Test
    fun `encodeTreadmillData with all null fields produces minimal packet`() {
        val data = exerciseData()
        val result = FtmsDataEncoder.encodeTreadmillData(data)
        // Flags (2 bytes) + speed (2 bytes) = 4 bytes minimum
        assertThat(result.size).isEqualTo(4)
        assertThat(uint16LE(result, 0)).isEqualTo(0x0000)
        assertThat(uint16LE(result, 2)).isEqualTo(0)
    }

    @Test
    fun `encodeTreadmillData encodes speed at 0_01 resolution`() {
        val data = exerciseData(speed = 12.5f)
        val result = FtmsDataEncoder.encodeTreadmillData(data)
        assertThat(uint16LE(result, 2)).isEqualTo(1250)
    }

    @Test
    fun `encodeTreadmillData with distance sets bit 2`() {
        val data = exerciseData(distance = 3.0f) // 3 km = 3000 m
        val result = FtmsDataEncoder.encodeTreadmillData(data)
        val flags = uint16LE(result, 0)
        assertThat(flags and (1 shl 2)).isNotEqualTo(0)
        assertThat(uint24LE(result, 4)).isEqualTo(3000)
    }

    @Test
    fun `encodeTreadmillData with incline sets bit 3 and encodes inclination plus ramp angle`() {
        val data = exerciseData(incline = 5.0f)
        val result = FtmsDataEncoder.encodeTreadmillData(data)
        val flags = uint16LE(result, 0)
        assertThat(flags and (1 shl 3)).isNotEqualTo(0)
        // Inclination: 5.0 * 10 = 50
        assertThat(sint16LE(result, 4)).isEqualTo(50)
        // Ramp angle: 0
        assertThat(sint16LE(result, 6)).isEqualTo(0)
    }

    @Test
    fun `encodeTreadmillData with calories sets bit 7`() {
        val data = exerciseData(calories = 200)
        val result = FtmsDataEncoder.encodeTreadmillData(data)
        val flags = uint16LE(result, 0)
        assertThat(flags and (1 shl 7)).isNotEqualTo(0)
        assertThat(uint16LE(result, 4)).isEqualTo(200)
        assertThat(uint16LE(result, 6)).isEqualTo(0xFFFF)
        assertThat(result[8].toInt() and 0xFF).isEqualTo(0xFF)
    }

    @Test
    fun `encodeTreadmillData with heart rate sets bit 8`() {
        val data = exerciseData(heartRate = 155)
        val result = FtmsDataEncoder.encodeTreadmillData(data)
        val flags = uint16LE(result, 0)
        assertThat(flags and (1 shl 8)).isNotEqualTo(0)
        assertThat(result[4].toInt() and 0xFF).isEqualTo(155)
    }

    @Test
    fun `encodeTreadmillData with elapsed time sets bit 10`() {
        val data = exerciseData(elapsedTime = 1800L)
        val result = FtmsDataEncoder.encodeTreadmillData(data)
        val flags = uint16LE(result, 0)
        assertThat(flags and (1 shl 10)).isNotEqualTo(0)
        assertThat(uint16LE(result, 4)).isEqualTo(1800)
    }

    @Test
    fun `encodeTreadmillData with power sets bit 12 with force on belt`() {
        val data = exerciseData(power = 250)
        val result = FtmsDataEncoder.encodeTreadmillData(data)
        val flags = uint16LE(result, 0)
        assertThat(flags and (1 shl 12)).isNotEqualTo(0)
        // Force on belt = 0
        assertThat(sint16LE(result, 4)).isEqualTo(0)
        // Power = 250
        assertThat(sint16LE(result, 6)).isEqualTo(250)
    }

    @Test
    fun `encodeTreadmillData with all fields`() {
        val data = exerciseData(
            speed = 10.0f, incline = 3.0f, power = 200,
            heartRate = 140, distance = 5.0f, calories = 300, elapsedTime = 2400L,
        )
        val result = FtmsDataEncoder.encodeTreadmillData(data)
        val flags = uint16LE(result, 0)
        assertThat(flags and (1 shl 2)).isNotEqualTo(0)  // distance
        assertThat(flags and (1 shl 3)).isNotEqualTo(0)  // inclination
        assertThat(flags and (1 shl 7)).isNotEqualTo(0)  // energy
        assertThat(flags and (1 shl 8)).isNotEqualTo(0)  // heart rate
        assertThat(flags and (1 shl 10)).isNotEqualTo(0) // elapsed time
        assertThat(flags and (1 shl 12)).isNotEqualTo(0) // force on belt + power
    }

    // --- Rower Data ---

    @Test
    fun `encodeRowerData with all null fields produces minimal packet`() {
        val data = exerciseData()
        val result = FtmsDataEncoder.encodeRowerData(data)
        // Flags only (2 bytes), no mandatory speed field
        assertThat(result.size).isEqualTo(2)
        assertThat(uint16LE(result, 0)).isEqualTo(0x0000)
    }

    @Test
    fun `encodeRowerData with cadence sets bit 0 and encodes stroke rate plus count`() {
        val data = exerciseData(cadence = 30)
        val result = FtmsDataEncoder.encodeRowerData(data)
        val flags = uint16LE(result, 0)
        assertThat(flags and (1 shl 0)).isNotEqualTo(0)
        // Stroke rate at 0.5 spm: 30 * 2 = 60
        assertThat(result[2].toInt() and 0xFF).isEqualTo(60)
        // Stroke count = 0
        assertThat(uint16LE(result, 3)).isEqualTo(0)
    }

    @Test
    fun `encodeRowerData with distance sets bit 2`() {
        val data = exerciseData(distance = 1.5f) // 1.5 km = 1500 m
        val result = FtmsDataEncoder.encodeRowerData(data)
        val flags = uint16LE(result, 0)
        assertThat(flags and (1 shl 2)).isNotEqualTo(0)
        assertThat(uint24LE(result, 2)).isEqualTo(1500)
    }

    @Test
    fun `encodeRowerData with power sets bit 5`() {
        val data = exerciseData(power = 180)
        val result = FtmsDataEncoder.encodeRowerData(data)
        val flags = uint16LE(result, 0)
        assertThat(flags and (1 shl 5)).isNotEqualTo(0)
        assertThat(sint16LE(result, 2)).isEqualTo(180)
    }

    @Test
    fun `encodeRowerData with resistance sets bit 7`() {
        val data = exerciseData(resistance = 8)
        val result = FtmsDataEncoder.encodeRowerData(data)
        val flags = uint16LE(result, 0)
        assertThat(flags and (1 shl 7)).isNotEqualTo(0)
        // Resistance at 0.1 resolution: 8 * 10 = 80
        assertThat(sint16LE(result, 2)).isEqualTo(80)
    }

    @Test
    fun `encodeRowerData with calories sets bit 8`() {
        val data = exerciseData(calories = 100)
        val result = FtmsDataEncoder.encodeRowerData(data)
        val flags = uint16LE(result, 0)
        assertThat(flags and (1 shl 8)).isNotEqualTo(0)
        assertThat(uint16LE(result, 2)).isEqualTo(100)
        assertThat(uint16LE(result, 4)).isEqualTo(0xFFFF)
        assertThat(result[6].toInt() and 0xFF).isEqualTo(0xFF)
    }

    @Test
    fun `encodeRowerData with heart rate sets bit 9`() {
        val data = exerciseData(heartRate = 130)
        val result = FtmsDataEncoder.encodeRowerData(data)
        val flags = uint16LE(result, 0)
        assertThat(flags and (1 shl 9)).isNotEqualTo(0)
        assertThat(result[2].toInt() and 0xFF).isEqualTo(130)
    }

    @Test
    fun `encodeRowerData with elapsed time sets bit 11`() {
        val data = exerciseData(elapsedTime = 900L)
        val result = FtmsDataEncoder.encodeRowerData(data)
        val flags = uint16LE(result, 0)
        assertThat(flags and (1 shl 11)).isNotEqualTo(0)
        assertThat(uint16LE(result, 2)).isEqualTo(900)
    }

    @Test
    fun `encodeRowerData with all fields`() {
        val data = exerciseData(
            cadence = 28, resistance = 6, power = 200,
            heartRate = 145, distance = 2.0f, calories = 180, elapsedTime = 1200L,
        )
        val result = FtmsDataEncoder.encodeRowerData(data)
        val flags = uint16LE(result, 0)
        assertThat(flags and (1 shl 0)).isNotEqualTo(0)  // stroke rate
        assertThat(flags and (1 shl 2)).isNotEqualTo(0)  // distance
        assertThat(flags and (1 shl 5)).isNotEqualTo(0)  // power
        assertThat(flags and (1 shl 7)).isNotEqualTo(0)  // resistance
        assertThat(flags and (1 shl 8)).isNotEqualTo(0)  // energy
        assertThat(flags and (1 shl 9)).isNotEqualTo(0)  // heart rate
        assertThat(flags and (1 shl 11)).isNotEqualTo(0) // elapsed time
    }

    // --- Cross Trainer Data ---

    @Test
    fun `encodeCrossTrainerData with all null fields produces minimal packet`() {
        val data = exerciseData()
        val result = FtmsDataEncoder.encodeCrossTrainerData(data)
        // Flags (3 bytes) + speed (2 bytes) = 5 bytes minimum
        assertThat(result.size).isEqualTo(5)
        assertThat(result[0].toInt() and 0xFF).isEqualTo(0)
        assertThat(result[1].toInt() and 0xFF).isEqualTo(0)
        assertThat(result[2].toInt() and 0xFF).isEqualTo(0)
        assertThat(uint16LE(result, 3)).isEqualTo(0)
    }

    @Test
    fun `encodeCrossTrainerData encodes speed at 0_01 resolution`() {
        val data = exerciseData(speed = 8.5f)
        val result = FtmsDataEncoder.encodeCrossTrainerData(data)
        assertThat(uint16LE(result, 3)).isEqualTo(850)
    }

    @Test
    fun `encodeCrossTrainerData with distance sets bit 2`() {
        val data = exerciseData(distance = 4.0f) // 4 km = 4000 m
        val result = FtmsDataEncoder.encodeCrossTrainerData(data)
        val flags = result[0].toInt() and 0xFF
        assertThat(flags and (1 shl 2)).isNotEqualTo(0)
        assertThat(uint24LE(result, 5)).isEqualTo(4000)
    }

    @Test
    fun `encodeCrossTrainerData with cadence sets bit 3 and encodes step rate plus avg`() {
        val data = exerciseData(cadence = 60)
        val result = FtmsDataEncoder.encodeCrossTrainerData(data)
        val flags = result[0].toInt() and 0xFF
        assertThat(flags and (1 shl 3)).isNotEqualTo(0)
        // Step/min at 1 step/min resolution: 60
        assertThat(uint16LE(result, 5)).isEqualTo(60)
        // Average step rate = 0
        assertThat(uint16LE(result, 7)).isEqualTo(0)
    }

    @Test
    fun `encodeCrossTrainerData with incline sets bit 6`() {
        val data = exerciseData(incline = 4.0f)
        val result = FtmsDataEncoder.encodeCrossTrainerData(data)
        val flags = result[0].toInt() and 0xFF
        assertThat(flags and (1 shl 6)).isNotEqualTo(0)
        // Inclination: 4.0 * 10 = 40
        assertThat(sint16LE(result, 5)).isEqualTo(40)
        // Ramp angle: 0
        assertThat(sint16LE(result, 7)).isEqualTo(0)
    }

    @Test
    fun `encodeCrossTrainerData with resistance sets bit 7`() {
        val data = exerciseData(resistance = 10)
        val result = FtmsDataEncoder.encodeCrossTrainerData(data)
        val flags = result[0].toInt() and 0xFF
        assertThat(flags and (1 shl 7)).isNotEqualTo(0)
        assertThat(sint16LE(result, 5)).isEqualTo(100)
    }

    @Test
    fun `encodeCrossTrainerData with power sets bit 8`() {
        val data = exerciseData(power = 175)
        val result = FtmsDataEncoder.encodeCrossTrainerData(data)
        val flags1 = result[1].toInt() and 0xFF
        assertThat(flags1 and (1 shl 0)).isNotEqualTo(0) // bit 8 = byte[1] bit 0
        assertThat(sint16LE(result, 5)).isEqualTo(175)
    }

    @Test
    fun `encodeCrossTrainerData with calories sets bit 10`() {
        val data = exerciseData(calories = 250)
        val result = FtmsDataEncoder.encodeCrossTrainerData(data)
        val flags1 = result[1].toInt() and 0xFF
        assertThat(flags1 and (1 shl 2)).isNotEqualTo(0) // bit 10 = byte[1] bit 2
        assertThat(uint16LE(result, 5)).isEqualTo(250)
        assertThat(uint16LE(result, 7)).isEqualTo(0xFFFF)
        assertThat(result[9].toInt() and 0xFF).isEqualTo(0xFF)
    }

    @Test
    fun `encodeCrossTrainerData with heart rate sets bit 11`() {
        val data = exerciseData(heartRate = 160)
        val result = FtmsDataEncoder.encodeCrossTrainerData(data)
        val flags1 = result[1].toInt() and 0xFF
        assertThat(flags1 and (1 shl 3)).isNotEqualTo(0) // bit 11 = byte[1] bit 3
        assertThat(result[5].toInt() and 0xFF).isEqualTo(160)
    }

    @Test
    fun `encodeCrossTrainerData with elapsed time sets bit 13`() {
        val data = exerciseData(elapsedTime = 2700L)
        val result = FtmsDataEncoder.encodeCrossTrainerData(data)
        val flags1 = result[1].toInt() and 0xFF
        assertThat(flags1 and (1 shl 5)).isNotEqualTo(0) // bit 13 = byte[1] bit 5
        assertThat(uint16LE(result, 5)).isEqualTo(2700)
    }

    @Test
    fun `encodeCrossTrainerData with all fields`() {
        val data = exerciseData(
            speed = 7.0f, cadence = 55, resistance = 8, power = 160, incline = 2.0f,
            heartRate = 135, distance = 3.5f, calories = 220, elapsedTime = 1500L,
        )
        val result = FtmsDataEncoder.encodeCrossTrainerData(data)
        val flags0 = result[0].toInt() and 0xFF
        val flags1 = result[1].toInt() and 0xFF
        assertThat(flags0 and (1 shl 2)).isNotEqualTo(0) // distance
        assertThat(flags0 and (1 shl 3)).isNotEqualTo(0) // step rate
        assertThat(flags0 and (1 shl 6)).isNotEqualTo(0) // inclination
        assertThat(flags0 and (1 shl 7)).isNotEqualTo(0) // resistance
        assertThat(flags1 and (1 shl 0)).isNotEqualTo(0) // power (bit 8)
        assertThat(flags1 and (1 shl 2)).isNotEqualTo(0) // energy (bit 10)
        assertThat(flags1 and (1 shl 3)).isNotEqualTo(0) // heart rate (bit 11)
        assertThat(flags1 and (1 shl 5)).isNotEqualTo(0) // elapsed time (bit 13)
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

    // --- Training Status (MCU hardware values) ---

    @Test
    fun `encodeTrainingStatus MCU idle maps to 0x01`() {
        val result = FtmsDataEncoder.encodeTrainingStatus(1) // IDLE
        assertThat(result).isEqualTo(byteArrayOf(0x00, 0x01))
    }

    @Test
    fun `encodeTrainingStatus MCU running maps to manual mode 0x0D`() {
        val result = FtmsDataEncoder.encodeTrainingStatus(2) // RUNNING
        assertThat(result).isEqualTo(byteArrayOf(0x00, 0x0D))
    }

    @Test
    fun `encodeTrainingStatus MCU pause maps to idle 0x01`() {
        val result = FtmsDataEncoder.encodeTrainingStatus(3) // PAUSE
        assertThat(result).isEqualTo(byteArrayOf(0x00, 0x01))
    }

    @Test
    fun `encodeTrainingStatus MCU post workout maps to 0x0F`() {
        val result = FtmsDataEncoder.encodeTrainingStatus(4) // RESULTS
        assertThat(result).isEqualTo(byteArrayOf(0x00, 0x0F))
    }

    @Test
    fun `encodeTrainingStatus MCU safety key maps to idle 0x01`() {
        val result = FtmsDataEncoder.encodeTrainingStatus(8) // DMK (Safety Key)
        assertThat(result).isEqualTo(byteArrayOf(0x00, 0x01))
    }

    @Test
    fun `encodeTrainingStatus MCU warm up maps to 0x02`() {
        val result = FtmsDataEncoder.encodeTrainingStatus(10) // WARM_UP
        assertThat(result).isEqualTo(byteArrayOf(0x00, 0x02))
    }

    @Test
    fun `encodeTrainingStatus MCU cool down maps to 0x0B`() {
        val result = FtmsDataEncoder.encodeTrainingStatus(11) // COOL_DOWN
        assertThat(result).isEqualTo(byteArrayOf(0x00, 0x0B))
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
