package com.nettarion.hyperborea.core

object FtmsDataEncoder {

    fun encodeData(deviceType: DeviceType, data: ExerciseData): ByteArray = when (deviceType) {
        DeviceType.BIKE -> encodeIndoorBikeData(data)
        DeviceType.TREADMILL -> TODO("Treadmill data encoding")
        DeviceType.ROWER -> TODO("Rower data encoding")
        DeviceType.ELLIPTICAL -> TODO("Cross Trainer data encoding")
    }

    fun dataCharacteristicShortUuid(deviceType: DeviceType): Int = when (deviceType) {
        DeviceType.BIKE -> 0x2AD2        // Indoor Bike Data
        DeviceType.TREADMILL -> 0x2AD1   // Treadmill Data
        DeviceType.ROWER -> 0x2AD8       // Rower Data
        DeviceType.ELLIPTICAL -> 0x2AD4  // Cross Trainer Data
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
     * Maps FitPro ConsoleState values to FTMS Training Status (Table 4.13).
     * Returns [Flags, TrainingStatus] — 2 bytes, no string.
     */
    fun encodeTrainingStatus(workoutMode: Int?): ByteArray {
        val status: Byte = when (workoutMode) {
            2 -> 0x01       // IDLE → Idle
            3 -> 0x0D       // WORKOUT → Manual Mode (Quick Start)
            4 -> 0x01       // PAUSED → Idle
            5 -> 0x0F       // WORKOUT_RESULTS → Post-Workout
            7 -> 0x02       // WARM_UP → Warming Up
            8 -> 0x0B       // COOL_DOWN → Cool Down
            9 -> 0x0D       // RESUME → Manual Mode (Quick Start)
            else -> 0x00    // Other (DISCONNECTED, UNKNOWN, SAFETY_KEY, LOCKED, DEMO, SLEEP, ERROR)
        }
        return byteArrayOf(0x00, status) // Flags=0x00 (no string), TrainingStatus
    }

    private fun uint16LE(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), (value shr 8).toByte())

    private fun uint24LE(value: Long): ByteArray =
        byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
        )

    private fun sint16LE(value: Int): ByteArray {
        val clamped = value.coerceIn(-32768, 32767)
        return byteArrayOf((clamped and 0xFF).toByte(), (clamped shr 8).toByte())
    }

    private fun putUint32LE(dest: ByteArray, offset: Int, value: Long) {
        dest[offset] = (value and 0xFF).toByte()
        dest[offset + 1] = ((value shr 8) and 0xFF).toByte()
        dest[offset + 2] = ((value shr 16) and 0xFF).toByte()
        dest[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
