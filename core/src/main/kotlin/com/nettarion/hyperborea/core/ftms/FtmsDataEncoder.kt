package com.nettarion.hyperborea.core.ftms

import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.model.ExerciseData

import com.nettarion.hyperborea.core.ftms.ByteUtils.putUint32LE
import com.nettarion.hyperborea.core.ftms.ByteUtils.sint16LE
import com.nettarion.hyperborea.core.ftms.ByteUtils.uint16LE
import com.nettarion.hyperborea.core.ftms.ByteUtils.uint24LE

object FtmsDataEncoder {

    fun encodeData(deviceType: DeviceType, data: ExerciseData): ByteArray = when (deviceType) {
        DeviceType.BIKE -> encodeIndoorBikeData(data)
        DeviceType.TREADMILL -> encodeTreadmillData(data)
        DeviceType.ROWER -> encodeRowerData(data)
        DeviceType.ELLIPTICAL -> encodeCrossTrainerData(data)
    }

    fun dataCharacteristicShortUuid(deviceType: DeviceType): Int = when (deviceType) {
        DeviceType.BIKE -> 0x2AD2        // Indoor Bike Data
        DeviceType.TREADMILL -> 0x2ACD   // Treadmill Data
        DeviceType.ROWER -> 0x2AD1       // Rower Data
        DeviceType.ELLIPTICAL -> 0x2ACE  // Cross Trainer Data
    }

    /**
     * Encodes ExerciseData into FTMS Indoor Bike Data (0x2AD2) characteristic value.
     *
     * Flags (uint16 LE): bit0=0 → speed present, bit2 → cadence, bit4 → total distance,
     * bit5 → resistance, bit6 → power, bit8 → expended energy, bit9 → heart rate,
     * bit11 → elapsed time.
     */
    fun encodeIndoorBikeData(data: ExerciseData): ByteArray {
        var flags = 0x0000 // bit0=0 means "more data" / speed present

        val parts = mutableListOf<ByteArray>()

        // Speed: uint16, 0.01 km/h resolution
        val speedRaw = ((data.speed ?: 0f) * 100).toInt().coerceIn(0, 0xFFFF)
        parts.add(uint16LE(speedRaw))

        // Cadence: uint16, 0.5 rpm resolution (bit 2)
        val cadence = data.cadence
        if (cadence != null) {
            flags = flags or (1 shl 2)
            parts.add(uint16LE((cadence * 2).coerceIn(0, 0xFFFF)))
        }

        // Total Distance: uint24, 1m resolution (bit 4)
        val distance = data.distance
        if (distance != null) {
            flags = flags or (1 shl 4)
            val meters = (distance * 1000).toLong().coerceIn(0, 0xFFFFFF)
            parts.add(uint24LE(meters))
        }

        // Resistance level: sint16, 0.1 unitless (bit 5)
        val resistance = data.resistance
        if (resistance != null) {
            flags = flags or (1 shl 5)
            parts.add(sint16LE(resistance * 10))
        }

        // Instantaneous power: sint16, watts (bit 6)
        val power = data.power
        if (power != null) {
            flags = flags or (1 shl 6)
            parts.add(sint16LE(power))
        }

        // Expended Energy: total uint16 + per-hour uint16 + per-minute uint8 (bit 8)
        val calories = data.calories
        if (calories != null) {
            flags = flags or (1 shl 8)
            parts.add(uint16LE(calories.coerceIn(0, 0xFFFE)))
            parts.add(uint16LE(0xFFFF)) // Energy per hour: "Data Not Available"
            parts.add(byteArrayOf(0xFF.toByte())) // Energy per minute: "Data Not Available"
        }

        // Heart rate: uint8, bpm (bit 9)
        val heartRate = data.heartRate
        if (heartRate != null) {
            flags = flags or (1 shl 9)
            parts.add(byteArrayOf(heartRate.coerceIn(0, 255).toByte()))
        }

        // Elapsed Time: uint16, seconds (bit 11)
        val elapsedTime = data.elapsedTime
        if (elapsedTime > 0) {
            flags = flags or (1 shl 11)
            parts.add(uint16LE(elapsedTime.toInt().coerceIn(0, 0xFFFF)))
        }

        val result = ByteArray(2 + parts.sumOf { it.size })
        result[0] = (flags and 0xFF).toByte()
        result[1] = (flags shr 8).toByte()
        var offset = 2
        for (part in parts) {
            part.copyInto(result, offset)
            offset += part.size
        }
        return result
    }

    /**
     * Encodes ExerciseData into FTMS Treadmill Data (0x2ACD) characteristic value.
     *
     * Flags (uint16 LE): bit0=0 → speed present, bit2 → total distance, bit3 → inclination,
     * bit7 → expended energy, bit8 → heart rate, bit10 → elapsed time,
     * bit12 → force on belt and power output.
     */
    fun encodeTreadmillData(data: ExerciseData): ByteArray {
        var flags = 0x0000 // bit0=0 means speed present

        val parts = mutableListOf<ByteArray>()

        // Speed: uint16, 0.01 km/h resolution
        val speedRaw = ((data.speed ?: 0f) * 100).toInt().coerceIn(0, 0xFFFF)
        parts.add(uint16LE(speedRaw))

        // Total Distance: uint24, 1m resolution (bit 2)
        val distance = data.distance
        if (distance != null) {
            flags = flags or (1 shl 2)
            val meters = (distance * 1000).toLong().coerceIn(0, 0xFFFFFF)
            parts.add(uint24LE(meters))
        }

        // Inclination + Ramp Angle: sint16 (0.1%) + sint16 (ramp=0) (bit 3)
        val incline = data.incline
        if (incline != null) {
            flags = flags or (1 shl 3)
            parts.add(sint16LE((incline * 10).toInt()))
            parts.add(sint16LE(0)) // Ramp angle
        }

        // Elevation Gain: uint16 positive gain + uint16 negative gain, 0.1m resolution (bit 5)
        val gain = data.verticalGain
        if (gain != null) {
            flags = flags or (1 shl 5)
            parts.add(uint16LE((gain * 10).toInt().coerceIn(0, 0xFFFF))) // positive gain
            parts.add(uint16LE(0)) // negative gain (not tracked separately)
        }

        // Expended Energy: total uint16 + per-hour uint16 + per-minute uint8 (bit 7)
        val calories = data.calories
        if (calories != null) {
            flags = flags or (1 shl 7)
            parts.add(uint16LE(calories.coerceIn(0, 0xFFFE)))
            parts.add(uint16LE(0xFFFF))
            parts.add(byteArrayOf(0xFF.toByte()))
        }

        // Heart rate: uint8, bpm (bit 8)
        val heartRate = data.heartRate
        if (heartRate != null) {
            flags = flags or (1 shl 8)
            parts.add(byteArrayOf(heartRate.coerceIn(0, 255).toByte()))
        }

        // Elapsed Time: uint16, seconds (bit 10)
        val elapsedTime = data.elapsedTime
        if (elapsedTime > 0) {
            flags = flags or (1 shl 10)
            parts.add(uint16LE(elapsedTime.toInt().coerceIn(0, 0xFFFF)))
        }

        // Force on Belt + Power Output: sint16 (force=0) + sint16 (watts) (bit 12)
        val power = data.power
        if (power != null) {
            flags = flags or (1 shl 12)
            parts.add(sint16LE(0)) // Force on belt
            parts.add(sint16LE(power))
        }

        val result = ByteArray(2 + parts.sumOf { it.size })
        result[0] = (flags and 0xFF).toByte()
        result[1] = (flags shr 8).toByte()
        var offset = 2
        for (part in parts) {
            part.copyInto(result, offset)
            offset += part.size
        }
        return result
    }

    /**
     * Encodes ExerciseData into FTMS Rower Data (0x2AD1) characteristic value.
     *
     * Flags (uint16 LE): bit0=0 → stroke rate/count present, bit2 → total distance,
     * bit5 → instantaneous power, bit7 → resistance level, bit8 → expended energy,
     * bit9 → heart rate, bit11 → elapsed time.
     */
    fun encodeRowerData(data: ExerciseData): ByteArray {
        var flags = 0x0000 // bit0=0 means stroke rate/count present

        val parts = mutableListOf<ByteArray>()

        // Stroke Rate + Count: always present (bit 0 = 0)
        val strokeRate = data.strokeRate ?: data.cadence ?: 0
        parts.add(byteArrayOf((strokeRate * 2).coerceIn(0, 255).toByte()))
        parts.add(uint16LE((data.strokeCount ?: 0).coerceIn(0, 0xFFFF)))

        // Total Distance: uint24, 1m resolution (bit 2)
        val distance = data.distance
        if (distance != null) {
            flags = flags or (1 shl 2)
            val meters = (distance * 1000).toLong().coerceIn(0, 0xFFFFFF)
            parts.add(uint24LE(meters))
        }

        // Instantaneous Power: sint16, watts (bit 5)
        val power = data.power
        if (power != null) {
            flags = flags or (1 shl 5)
            parts.add(sint16LE(power))
        }

        // Resistance Level: sint16, 0.1 unitless (bit 7)
        val resistance = data.resistance
        if (resistance != null) {
            flags = flags or (1 shl 7)
            parts.add(sint16LE(resistance * 10))
        }

        // Expended Energy: total uint16 + per-hour uint16 + per-minute uint8 (bit 8)
        val calories = data.calories
        if (calories != null) {
            flags = flags or (1 shl 8)
            parts.add(uint16LE(calories.coerceIn(0, 0xFFFE)))
            parts.add(uint16LE(0xFFFF))
            parts.add(byteArrayOf(0xFF.toByte()))
        }

        // Heart rate: uint8, bpm (bit 9)
        val heartRate = data.heartRate
        if (heartRate != null) {
            flags = flags or (1 shl 9)
            parts.add(byteArrayOf(heartRate.coerceIn(0, 255).toByte()))
        }

        // Elapsed Time: uint16, seconds (bit 11)
        val elapsedTime = data.elapsedTime
        if (elapsedTime > 0) {
            flags = flags or (1 shl 11)
            parts.add(uint16LE(elapsedTime.toInt().coerceIn(0, 0xFFFF)))
        }

        val result = ByteArray(2 + parts.sumOf { it.size })
        result[0] = (flags and 0xFF).toByte()
        result[1] = (flags shr 8).toByte()
        var offset = 2
        for (part in parts) {
            part.copyInto(result, offset)
            offset += part.size
        }
        return result
    }

    /**
     * Encodes ExerciseData into FTMS Cross Trainer Data (0x2ACE) characteristic value.
     *
     * Flags (uint24 LE — 3 bytes): bit0=0 → speed present, bit2 → total distance,
     * bit3 → step rate, bit6 → inclination, bit7 → resistance level,
     * bit8 → instantaneous power, bit10 → expended energy, bit11 → heart rate,
     * bit13 → elapsed time.
     */
    fun encodeCrossTrainerData(data: ExerciseData): ByteArray {
        var flags = 0x000000 // bit0=0 means speed present

        val parts = mutableListOf<ByteArray>()

        // Speed: uint16, 0.01 km/h resolution
        val speedRaw = ((data.speed ?: 0f) * 100).toInt().coerceIn(0, 0xFFFF)
        parts.add(uint16LE(speedRaw))

        // Total Distance: uint24, 1m resolution (bit 2)
        val distance = data.distance
        if (distance != null) {
            flags = flags or (1 shl 2)
            val meters = (distance * 1000).toLong().coerceIn(0, 0xFFFFFF)
            parts.add(uint24LE(meters))
        }

        // Step/Min + Avg Step Rate: uint16 (1 step/min) + uint16 (avg=0) (bit 3)
        val cadence = data.cadence
        if (cadence != null) {
            flags = flags or (1 shl 3)
            parts.add(uint16LE(cadence))
            parts.add(uint16LE(0)) // Average step rate
        }

        // Inclination + Ramp Angle: sint16 (0.1%) + sint16 (ramp=0) (bit 6)
        val incline = data.incline
        if (incline != null) {
            flags = flags or (1 shl 6)
            parts.add(sint16LE((incline * 10).toInt()))
            parts.add(sint16LE(0)) // Ramp angle
        }

        // Resistance Level: sint16, 0.1 unitless (bit 7)
        val resistance = data.resistance
        if (resistance != null) {
            flags = flags or (1 shl 7)
            parts.add(sint16LE(resistance * 10))
        }

        // Instantaneous Power: sint16, watts (bit 8)
        val power = data.power
        if (power != null) {
            flags = flags or (1 shl 8)
            parts.add(sint16LE(power))
        }

        // Expended Energy: total uint16 + per-hour uint16 + per-minute uint8 (bit 10)
        val calories = data.calories
        if (calories != null) {
            flags = flags or (1 shl 10)
            parts.add(uint16LE(calories.coerceIn(0, 0xFFFE)))
            parts.add(uint16LE(0xFFFF))
            parts.add(byteArrayOf(0xFF.toByte()))
        }

        // Heart rate: uint8, bpm (bit 11)
        val heartRate = data.heartRate
        if (heartRate != null) {
            flags = flags or (1 shl 11)
            parts.add(byteArrayOf(heartRate.coerceIn(0, 255).toByte()))
        }

        // Elapsed Time: uint16, seconds (bit 13)
        val elapsedTime = data.elapsedTime
        if (elapsedTime > 0) {
            flags = flags or (1 shl 13)
            parts.add(uint16LE(elapsedTime.toInt().coerceIn(0, 0xFFFF)))
        }

        val result = ByteArray(3 + parts.sumOf { it.size })
        result[0] = (flags and 0xFF).toByte()
        result[1] = ((flags shr 8) and 0xFF).toByte()
        result[2] = ((flags shr 16) and 0xFF).toByte()
        var offset = 3
        for (part in parts) {
            part.copyInto(result, offset)
            offset += part.size
        }
        return result
    }

    /**
     * Encodes ExerciseData into CPS Measurement (0x2A63) characteristic value.
     *
     * Flags 0x0030: wheel revolution data (bit 4) + crank revolution data (bit 5).
     */
    fun encodeCpsMeasurement(
        data: ExerciseData,
        cumulativeWheelRevs: Long,
        lastWheelEventTime: Int,
        cumulativeCrankRevs: Long,
        lastCrankEventTime: Int,
    ): ByteArray {
        val flags = 0x0030 // wheel rev data + crank rev data
        val power = data.power ?: 0

        val result = ByteArray(14)
        // Flags: uint16 LE
        result[0] = (flags and 0xFF).toByte()
        result[1] = (flags shr 8).toByte()
        // Instantaneous power: sint16 LE
        result[2] = (power and 0xFF).toByte()
        result[3] = (power shr 8).toByte()
        // Cumulative wheel revolutions: uint32 LE
        putUint32LE(result, 4, cumulativeWheelRevs)
        // Last wheel event time: uint16 LE (1/2048s resolution)
        result[8] = (lastWheelEventTime and 0xFF).toByte()
        result[9] = (lastWheelEventTime shr 8).toByte()
        // Cumulative crank revolutions: uint16 LE
        val crankRevs16 = (cumulativeCrankRevs and 0xFFFF).toInt()
        result[10] = (crankRevs16 and 0xFF).toByte()
        result[11] = (crankRevs16 shr 8).toByte()
        // Last crank event time: uint16 LE (1/1024s resolution)
        result[12] = (lastCrankEventTime and 0xFF).toByte()
        result[13] = (lastCrankEventTime shr 8).toByte()

        return result
    }

    /**
     * Encodes FTMS Training Status (0x2AD3) characteristic value from FitPro workout mode.
     *
     * Maps FitPro MCU hardware workout mode values to FTMS Training Status (Table 4.13).
     * Returns [Flags, TrainingStatus] — 2 bytes, no string.
     */
    fun encodeTrainingStatus(workoutMode: Int?): ByteArray {
        val status: Byte = when (workoutMode) {
            1 -> 0x01       // IDLE → Idle
            2 -> 0x0D       // RUNNING → Manual Mode (Quick Start)
            3 -> 0x01       // PAUSE → Idle
            4 -> 0x0F       // RESULTS → Post-Workout
            8 -> 0x01       // DMK (Safety Key) → Idle
            10 -> 0x02      // WARM_UP → Warming Up
            11 -> 0x0B      // COOL_DOWN → Cool Down
            else -> 0x00
        }
        return byteArrayOf(0x00, status) // Flags=0x00 (no string), TrainingStatus
    }

}
