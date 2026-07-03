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
     * Accumulates an FTMS characteristic value: a little-endian flags header followed by the
     * payload of the fields whose flag bit was set. Every FTMS Data characteristic follows this
     * shape, so the four encoders below differ only in which fields they list.
     *
     * [flagByteCount] is 2 for the uint16-flag characteristics (Indoor Bike / Treadmill / Rower)
     * and 3 for Cross Trainer's uint24 flags. Bit 0 stays 0 across all of them (the "more data" /
     * mandatory-field-present convention), so the leading field is added with no flag bit.
     *
     * The byte helpers delegate to [ByteUtils], keeping the wire bytes identical to hand-assembled
     * output (little-endian, with the same coerce/truncation semantics).
     */
    private class FtmsFrameBuilder(private val flagByteCount: Int) {
        private var flags = 0
        private val parts = mutableListOf<ByteArray>()

        /** Append the mandatory leading field (bit 0 = 0, no flag to set). */
        fun mandatory(block: FtmsFrameBuilder.() -> Unit): FtmsFrameBuilder = apply { block() }

        /** Append an optional field, setting its [flagBit]. */
        fun field(flagBit: Int, block: FtmsFrameBuilder.() -> Unit): FtmsFrameBuilder = apply {
            flags = flags or (1 shl flagBit)
            block()
        }

        fun addUint8(value: Int) = apply { parts.add(byteArrayOf(value.toByte())) }
        fun addUint16LE(value: Int) = apply { parts.add(uint16LE(value)) }
        fun addSint16LE(value: Int) = apply { parts.add(sint16LE(value)) }
        fun addUint24LE(value: Long) = apply { parts.add(uint24LE(value)) }

        fun build(): ByteArray {
            val result = ByteArray(flagByteCount + parts.sumOf { it.size })
            for (i in 0 until flagByteCount) {
                result[i] = ((flags shr (8 * i)) and 0xFF).toByte()
            }
            var offset = flagByteCount
            for (part in parts) {
                part.copyInto(result, offset)
                offset += part.size
            }
            return result
        }
    }

    /**
     * Encodes ExerciseData into FTMS Indoor Bike Data (0x2AD2) characteristic value.
     *
     * Flags (uint16 LE): bit0=0 → speed present, bit2 → cadence, bit4 → total distance,
     * bit5 → resistance, bit6 → power, bit8 → expended energy, bit9 → heart rate,
     * bit11 → elapsed time.
     */
    fun encodeIndoorBikeData(data: ExerciseData): ByteArray = FtmsFrameBuilder(2).apply {
        // Speed: uint16, 0.01 km/h resolution
        mandatory { addUint16LE(((data.speed ?: 0f) * 100).toInt().coerceIn(0, 0xFFFF)) }

        // Cadence: uint16, 0.5 rpm resolution (bit 2)
        data.cadence?.let { cadence ->
            field(2) { addUint16LE((cadence * 2).coerceIn(0, 0xFFFF)) }
        }

        // Total Distance: uint24, 1m resolution (bit 4)
        data.distance?.let { distance ->
            field(4) { addUint24LE((distance * 1000).toLong().coerceIn(0, 0xFFFFFF)) }
        }

        // Resistance level: sint16, 0.1 unitless (bit 5)
        data.resistance?.let { resistance ->
            field(5) { addSint16LE(resistance * 10) }
        }

        // Instantaneous power: sint16, watts (bit 6)
        data.power?.let { power ->
            field(6) { addSint16LE(power) }
        }

        // Expended Energy: total uint16 + per-hour uint16 + per-minute uint8 (bit 8)
        data.calories?.let { calories ->
            field(8) {
                addUint16LE(calories.coerceIn(0, 0xFFFE))
                addUint16LE(0xFFFF) // Energy per hour: "Data Not Available"
                addUint8(0xFF)      // Energy per minute: "Data Not Available"
            }
        }

        // Heart rate: uint8, bpm (bit 9)
        data.heartRate?.let { heartRate ->
            field(9) { addUint8(heartRate.coerceIn(0, 255)) }
        }

        // Elapsed Time: uint16, seconds (bit 11)
        if (data.elapsedTime > 0) {
            field(11) { addUint16LE(data.elapsedTime.toInt().coerceIn(0, 0xFFFF)) }
        }
    }.build()

    /**
     * Encodes ExerciseData into FTMS Treadmill Data (0x2ACD) characteristic value.
     *
     * Flags (uint16 LE): bit0=0 → speed present, bit2 → total distance, bit3 → inclination,
     * bit7 → expended energy, bit8 → heart rate, bit10 → elapsed time,
     * bit12 → force on belt and power output.
     */
    fun encodeTreadmillData(data: ExerciseData): ByteArray = FtmsFrameBuilder(2).apply {
        // Speed: uint16, 0.01 km/h resolution
        mandatory { addUint16LE(((data.speed ?: 0f) * 100).toInt().coerceIn(0, 0xFFFF)) }

        // Total Distance: uint24, 1m resolution (bit 2)
        data.distance?.let { distance ->
            field(2) { addUint24LE((distance * 1000).toLong().coerceIn(0, 0xFFFFFF)) }
        }

        // Inclination + Ramp Angle: sint16 (0.1%) + sint16 (ramp=0) (bit 3)
        data.incline?.let { incline ->
            field(3) {
                addSint16LE((incline * 10).toInt())
                addSint16LE(0) // Ramp angle
            }
        }

        // Elevation Gain: uint16 positive gain + uint16 negative gain, 0.1m resolution (bit 5)
        data.verticalGain?.let { gain ->
            field(5) {
                addUint16LE((gain * 10).toInt().coerceIn(0, 0xFFFF)) // positive gain
                addUint16LE(0) // negative gain (not tracked separately)
            }
        }

        // Expended Energy: total uint16 + per-hour uint16 + per-minute uint8 (bit 7)
        data.calories?.let { calories ->
            field(7) {
                addUint16LE(calories.coerceIn(0, 0xFFFE))
                addUint16LE(0xFFFF)
                addUint8(0xFF)
            }
        }

        // Heart rate: uint8, bpm (bit 8)
        data.heartRate?.let { heartRate ->
            field(8) { addUint8(heartRate.coerceIn(0, 255)) }
        }

        // Elapsed Time: uint16, seconds (bit 10)
        if (data.elapsedTime > 0) {
            field(10) { addUint16LE(data.elapsedTime.toInt().coerceIn(0, 0xFFFF)) }
        }

        // Force on Belt + Power Output: sint16 (force=0) + sint16 (watts) (bit 12)
        data.power?.let { power ->
            field(12) {
                addSint16LE(0) // Force on belt
                addSint16LE(power)
            }
        }
    }.build()

    /**
     * Encodes ExerciseData into FTMS Rower Data (0x2AD1) characteristic value.
     *
     * Flags (uint16 LE): bit0=0 → stroke rate/count present, bit2 → total distance,
     * bit5 → instantaneous power, bit7 → resistance level, bit8 → expended energy,
     * bit9 → heart rate, bit11 → elapsed time.
     */
    fun encodeRowerData(data: ExerciseData): ByteArray = FtmsFrameBuilder(2).apply {
        // Stroke Rate + Count: always present (bit 0 = 0)
        mandatory {
            val strokeRate = data.strokeRate ?: data.cadence ?: 0
            addUint8((strokeRate * 2).coerceIn(0, 255))
            addUint16LE((data.strokeCount ?: 0).coerceIn(0, 0xFFFF))
        }

        // Total Distance: uint24, 1m resolution (bit 2)
        data.distance?.let { distance ->
            field(2) { addUint24LE((distance * 1000).toLong().coerceIn(0, 0xFFFFFF)) }
        }

        // Instantaneous Power: sint16, watts (bit 5)
        data.power?.let { power ->
            field(5) { addSint16LE(power) }
        }

        // Resistance Level: sint16, 0.1 unitless (bit 7)
        data.resistance?.let { resistance ->
            field(7) { addSint16LE(resistance * 10) }
        }

        // Expended Energy: total uint16 + per-hour uint16 + per-minute uint8 (bit 8)
        data.calories?.let { calories ->
            field(8) {
                addUint16LE(calories.coerceIn(0, 0xFFFE))
                addUint16LE(0xFFFF)
                addUint8(0xFF)
            }
        }

        // Heart rate: uint8, bpm (bit 9)
        data.heartRate?.let { heartRate ->
            field(9) { addUint8(heartRate.coerceIn(0, 255)) }
        }

        // Elapsed Time: uint16, seconds (bit 11)
        if (data.elapsedTime > 0) {
            field(11) { addUint16LE(data.elapsedTime.toInt().coerceIn(0, 0xFFFF)) }
        }
    }.build()

    /**
     * Encodes ExerciseData into FTMS Cross Trainer Data (0x2ACE) characteristic value.
     *
     * Flags (uint24 LE — 3 bytes): bit0=0 → speed present, bit2 → total distance,
     * bit3 → step rate, bit6 → inclination, bit7 → resistance level,
     * bit8 → instantaneous power, bit10 → expended energy, bit11 → heart rate,
     * bit13 → elapsed time.
     */
    fun encodeCrossTrainerData(data: ExerciseData): ByteArray = FtmsFrameBuilder(3).apply {
        // Speed: uint16, 0.01 km/h resolution
        mandatory { addUint16LE(((data.speed ?: 0f) * 100).toInt().coerceIn(0, 0xFFFF)) }

        // Total Distance: uint24, 1m resolution (bit 2)
        data.distance?.let { distance ->
            field(2) { addUint24LE((distance * 1000).toLong().coerceIn(0, 0xFFFFFF)) }
        }

        // Step/Min + Avg Step Rate: uint16 (1 step/min) + uint16 (avg=0) (bit 3)
        data.cadence?.let { cadence ->
            field(3) {
                addUint16LE(cadence)
                addUint16LE(0) // Average step rate
            }
        }

        // Inclination + Ramp Angle: sint16 (0.1%) + sint16 (ramp=0) (bit 6)
        data.incline?.let { incline ->
            field(6) {
                addSint16LE((incline * 10).toInt())
                addSint16LE(0) // Ramp angle
            }
        }

        // Resistance Level: sint16, 0.1 unitless (bit 7)
        data.resistance?.let { resistance ->
            field(7) { addSint16LE(resistance * 10) }
        }

        // Instantaneous Power: sint16, watts (bit 8)
        data.power?.let { power ->
            field(8) { addSint16LE(power) }
        }

        // Expended Energy: total uint16 + per-hour uint16 + per-minute uint8 (bit 10)
        data.calories?.let { calories ->
            field(10) {
                addUint16LE(calories.coerceIn(0, 0xFFFE))
                addUint16LE(0xFFFF)
                addUint8(0xFF)
            }
        }

        // Heart rate: uint8, bpm (bit 11)
        data.heartRate?.let { heartRate ->
            field(11) { addUint8(heartRate.coerceIn(0, 255)) }
        }

        // Elapsed Time: uint16, seconds (bit 13)
        if (data.elapsedTime > 0) {
            field(13) { addUint16LE(data.elapsedTime.toInt().coerceIn(0, 0xFFFF)) }
        }
    }.build()

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
     * Encodes ExerciseData into RSC Measurement (0x2A53) characteristic value.
     *
     * Layout (BLE Running Speed and Cadence spec):
     *  - Flags (uint8): bit1 → total distance present (always set here), bit2 → running (not
     *    walking), set when belt speed ≥ [FtmsServiceMetadata.RUN_WALK_THRESHOLD_KPH].
     *  - Instantaneous Speed (uint16 LE): 1/256 m/s.
     *  - Instantaneous Cadence (uint8): steps/min — always 0; treadmills report belt speed, not
     *    step cadence. Run apps derive pace from speed, so 0 is tolerated.
     *  - Total Distance (uint32 LE): 1/10 m.
     */
    fun encodeRscMeasurement(data: ExerciseData): ByteArray {
        val speedKph = data.speed ?: 0f
        val speedRaw = ((speedKph / 3.6f) * 256f).toInt().coerceIn(0, 0xFFFF)
        val totalDistanceDm = ((data.distance ?: 0f) * 10_000f).toLong().coerceIn(0, 0xFFFFFFFFL)

        var flags = 1 shl 1 // total distance present
        if (speedKph >= FtmsServiceMetadata.RUN_WALK_THRESHOLD_KPH) {
            flags = flags or (1 shl 2) // running status
        }

        val result = ByteArray(8)
        result[0] = (flags and 0xFF).toByte()
        uint16LE(speedRaw).copyInto(result, 1)
        result[3] = 0x00 // instantaneous cadence (steps/min) — not measured
        putUint32LE(result, 4, totalDistanceDm)
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
