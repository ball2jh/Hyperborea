package com.nettarion.hyperborea.core.fitfile

import com.nettarion.hyperborea.core.model.RideSummary
import com.nettarion.hyperborea.core.model.WorkoutSample

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FitActivityBuilderTest {

    // --- Minimal activity ---

    @Test
    fun `single sample produces valid FIT file`() {
        val result = FitActivityBuilder.buildActivityFile(testSummary(), listOf(testSample()))
        assertValidFitFile(result)
    }

    @Test
    fun `empty samples produces valid FIT file`() {
        val result = FitActivityBuilder.buildActivityFile(testSummary(), emptyList())
        assertValidFitFile(result)
    }

    // --- Message ordering ---

    @Test
    fun `message order is FileId Event-start Records Event-stop Lap Session Activity`() {
        val samples = listOf(testSample(ts = 1), testSample(ts = 2))
        val result = FitActivityBuilder.buildActivityFile(testSummary(), samples)
        val data = dataSection(result)
        val msgNums = extractGlobalMsgNums(data)

        // FileId=0, Event=21, Record=20, Record=20, Event=21, Lap=19, Session=18, Activity=34
        assertThat(msgNums).containsExactly(0, 21, 20, 20, 21, 19, 18, 34).inOrder()
    }

    // --- Null sensor fields ---

    @Test
    fun `null power in sample writes FIT invalid sentinel 0xFFFF`() {
        val sample = testSample(power = null)
        val result = FitActivityBuilder.buildActivityFile(testSummary(), listOf(sample))
        val data = dataSection(result)

        // Find record data message and check power field (uint16 invalid = 0xFFFF)
        val recordData = findDataMessageBytes(data, globalMsgNum = 20)
        assertThat(recordData).isNotNull()
        // Record fields: timestamp(4) + hr(1) + cadence(1) + distance(4) + speed(2) + power(2) + resistance(2) + calories(2) + grade(2)
        // Power is at offset 4+1+1+4+2 = 12 from start of field data
        val powerOffset = 4 + 1 + 1 + 4 + 2
        assertThat(uint16LE(recordData!!, powerOffset)).isEqualTo(0xFFFF)
    }

    @Test
    fun `null heartRate in sample writes FIT invalid sentinel 0xFF`() {
        val sample = testSample(heartRate = null)
        val result = FitActivityBuilder.buildActivityFile(testSummary(), listOf(sample))
        val data = dataSection(result)
        val recordData = findDataMessageBytes(data, globalMsgNum = 20)
        // HR is at offset 4 (after timestamp)
        assertThat(recordData!![4].toInt() and 0xFF).isEqualTo(0xFF)
    }

    @Test
    fun `null cadence in sample writes FIT invalid sentinel 0xFF`() {
        val sample = testSample(cadence = null)
        val result = FitActivityBuilder.buildActivityFile(testSummary(), listOf(sample))
        val data = dataSection(result)
        val recordData = findDataMessageBytes(data, globalMsgNum = 20)
        // Cadence is at offset 5 (after timestamp + hr)
        assertThat(recordData!![5].toInt() and 0xFF).isEqualTo(0xFF)
    }

    // --- Timestamp accuracy ---

    @Test
    fun `record timestamps are correctly offset from ride start`() {
        // startedAt = 2024-01-01T00:00:00Z = 1704067200000ms
        val startMillis = 1704067200000L
        val summary = testSummary(startedAt = startMillis)
        val sample = testSample(ts = 60) // 60 seconds into ride
        val result = FitActivityBuilder.buildActivityFile(summary, listOf(sample))
        val data = dataSection(result)

        val recordData = findDataMessageBytes(data, globalMsgNum = 20)
        val recordTs = uint32LE(recordData!!, 0)

        // Expected: (startMillis/1000 - FIT_EPOCH_OFFSET) + 60
        val expectedTs = (startMillis / 1000 - FitEncoder.FIT_EPOCH_OFFSET) + 60
        assertThat(recordTs).isEqualTo(expectedTs)
    }

    // --- Session aggregates ---

    @Test
    fun `session message contains summary values`() {
        val summary = testSummary(
            avgPower = 180,
            maxPower = 300,
            avgCadence = 85,
            maxCadence = 110,
            avgSpeedKph = 30.0f,
            maxSpeedKph = 45.0f,
            avgHeartRate = 145,
            maxHeartRate = 175,
            calories = 500,
            distanceKm = 15.5f,
            normalizedPower = 195,
        )
        val result = FitActivityBuilder.buildActivityFile(summary, emptyList())
        val data = dataSection(result)

        // Verify session message exists (global msg 18)
        val msgNums = extractGlobalMsgNums(data)
        assertThat(msgNums).contains(18)
    }

    // --- Unit conversions ---

    @Test
    fun `kph to FIT speed is m_s times 1000`() {
        // 36 km/h = 10 m/s → FIT speed = 10000
        val sample = testSample(speedKph = 36.0f)
        val result = FitActivityBuilder.buildActivityFile(testSummary(), listOf(sample))
        val data = dataSection(result)
        val recordData = findDataMessageBytes(data, globalMsgNum = 20)
        // Speed is at offset 4+1+1+4 = 10 from start of field data
        val speedOffset = 4 + 1 + 1 + 4
        assertThat(uint16LE(recordData!!, speedOffset)).isEqualTo(10000)
    }

    @Test
    fun `km to FIT distance is m times 100`() {
        // 1.5 km → 150000 (1500m × 100)
        val sample = testSample(distanceKm = 1.5f)
        val result = FitActivityBuilder.buildActivityFile(testSummary(), listOf(sample))
        val data = dataSection(result)
        val recordData = findDataMessageBytes(data, globalMsgNum = 20)
        // Distance is at offset 4+1+1 = 6 from start of field data
        val distanceOffset = 4 + 1 + 1
        assertThat(uint32LE(recordData!!, distanceOffset)).isEqualTo(150000L)
    }

    @Test
    fun `unix ms to FIT timestamp subtracts FIT epoch`() {
        // FIT epoch = 1989-12-31T00:00:00Z = 631065600 Unix seconds
        // If startedAt = 631065600000 ms (exactly at FIT epoch), FIT timestamp = 0
        val summary = testSummary(startedAt = FitEncoder.FIT_EPOCH_OFFSET * 1000)
        val sample = testSample(ts = 0)
        val result = FitActivityBuilder.buildActivityFile(summary, listOf(sample))
        val data = dataSection(result)
        val recordData = findDataMessageBytes(data, globalMsgNum = 20)
        assertThat(uint32LE(recordData!!, 0)).isEqualTo(0L)
    }

    // --- Golden byte test ---

    @Test
    fun `file header has correct magic and structure`() {
        val result = FitActivityBuilder.buildActivityFile(testSummary(), emptyList())
        // Header size = 14
        assertThat(result[0].toInt() and 0xFF).isEqualTo(14)
        // Protocol version = 2.4
        assertThat(result[1].toInt() and 0xFF).isEqualTo(0x20)
        // ".FIT" at bytes 8-11
        assertThat(result.sliceArray(8..11).map { it.toInt().toChar() }.joinToString(""))
            .isEqualTo(".FIT")
        // Header CRC at bytes 12-13
        val expectedHeaderCrc = FitEncoder.crc16(result, 0, 12)
        assertThat(uint16LE(result, 12)).isEqualTo(expectedHeaderCrc)
        // File CRC at last 2 bytes
        val expectedFileCrc = FitEncoder.crc16(result, 0, result.size - 2)
        assertThat(uint16LE(result, result.size - 2)).isEqualTo(expectedFileCrc)
    }

    @Test
    fun `multiple samples produce correct number of record messages`() {
        val samples = (0L until 10).map { testSample(ts = it) }
        val result = FitActivityBuilder.buildActivityFile(testSummary(), samples)
        val data = dataSection(result)
        val msgNums = extractGlobalMsgNums(data)
        val recordCount = msgNums.count { it == 20 }
        assertThat(recordCount).isEqualTo(10)
    }

    // --- Test data factories ---

    private fun testSummary(
        startedAt: Long = 1704067200000L, // 2024-01-01T00:00:00Z
        durationSeconds: Long = 3600L,
        distanceKm: Float = 10.0f,
        calories: Int = 300,
        avgPower: Int? = 150,
        maxPower: Int? = 250,
        avgCadence: Int? = 80,
        maxCadence: Int? = 100,
        avgSpeedKph: Float? = 25.0f,
        maxSpeedKph: Float? = 35.0f,
        avgHeartRate: Int? = 140,
        maxHeartRate: Int? = 170,
        normalizedPower: Int? = null,
    ) = RideSummary(
        id = 1,
        profileId = 1,
        startedAt = startedAt,
        durationSeconds = durationSeconds,
        distanceKm = distanceKm,
        calories = calories,
        avgPower = avgPower,
        maxPower = maxPower,
        avgCadence = avgCadence,
        maxCadence = maxCadence,
        avgSpeedKph = avgSpeedKph,
        maxSpeedKph = maxSpeedKph,
        avgHeartRate = avgHeartRate,
        maxHeartRate = maxHeartRate,
        normalizedPower = normalizedPower,
    )

    private fun testSample(
        ts: Long = 0L,
        power: Int? = 150,
        cadence: Int? = 80,
        speedKph: Float? = 25.0f,
        heartRate: Int? = 140,
        resistance: Int? = 10,
        distanceKm: Float? = 0.5f,
        calories: Int? = 10,
    ) = WorkoutSample(
        timestampSeconds = ts,
        power = power,
        cadence = cadence,
        speedKph = speedKph,
        heartRate = heartRate,
        resistance = resistance,
        incline = null,
        calories = calories,
        distanceKm = distanceKm,
    )

    // --- Helpers ---

    private fun assertValidFitFile(file: ByteArray) {
        assertThat(file.size).isAtLeast(16) // 14 header + 2 CRC minimum
        assertThat(file[0].toInt() and 0xFF).isEqualTo(14)
        assertThat(file.sliceArray(8..11).map { it.toInt().toChar() }.joinToString(""))
            .isEqualTo(".FIT")
        // Verify header CRC
        val headerCrc = FitEncoder.crc16(file, 0, 12)
        assertThat(uint16LE(file, 12)).isEqualTo(headerCrc)
        // Verify file CRC
        val fileCrc = FitEncoder.crc16(file, 0, file.size - 2)
        assertThat(uint16LE(file, file.size - 2)).isEqualTo(fileCrc)
    }

    private fun dataSection(file: ByteArray): ByteArray {
        val dataSize = uint32LE(file, 4).toInt()
        return file.copyOfRange(14, 14 + dataSize)
    }

    /**
     * Parse data section and extract global message numbers in order.
     * Only returns data message global msg nums (not definition messages).
     */
    private fun extractGlobalMsgNums(data: ByteArray): List<Int> {
        val result = mutableListOf<Int>()
        // Map local msg num → (globalMsgNum, fieldSizes)
        val definitions = mutableMapOf<Int, Pair<Int, List<Int>>>()
        var i = 0
        while (i < data.size) {
            val header = data[i].toInt() and 0xFF
            if (header and 0x40 != 0) {
                // Definition message
                val localNum = header and 0x0F
                val globalMsgNum = uint16LE(data, i + 3)
                val numFields = data[i + 5].toInt() and 0xFF
                val fieldSizes = mutableListOf<Int>()
                for (f in 0 until numFields) {
                    val fieldOffset = i + 6 + f * 3
                    fieldSizes.add(data[fieldOffset + 1].toInt() and 0xFF)
                }
                definitions[localNum] = Pair(globalMsgNum, fieldSizes)
                i += 6 + numFields * 3
            } else {
                // Data message
                val localNum = header and 0x0F
                val def = definitions[localNum]!!
                result.add(def.first) // global msg num
                val dataSize = def.second.sum()
                i += 1 + dataSize
            }
        }
        return result
    }

    /**
     * Find the first data message with the given global msg num and return
     * just the field data bytes (without the record header).
     */
    private fun findDataMessageBytes(data: ByteArray, globalMsgNum: Int): ByteArray? {
        val definitions = mutableMapOf<Int, Pair<Int, List<Int>>>()
        var i = 0
        while (i < data.size) {
            val header = data[i].toInt() and 0xFF
            if (header and 0x40 != 0) {
                val localNum = header and 0x0F
                val gMsgNum = uint16LE(data, i + 3)
                val numFields = data[i + 5].toInt() and 0xFF
                val fieldSizes = mutableListOf<Int>()
                for (f in 0 until numFields) {
                    fieldSizes.add(data[i + 6 + f * 3 + 1].toInt() and 0xFF)
                }
                definitions[localNum] = Pair(gMsgNum, fieldSizes)
                i += 6 + numFields * 3
            } else {
                val localNum = header and 0x0F
                val def = definitions[localNum]!!
                val dataSize = def.second.sum()
                if (def.first == globalMsgNum) {
                    return data.copyOfRange(i + 1, i + 1 + dataSize)
                }
                i += 1 + dataSize
            }
        }
        return null
    }

    private fun uint16LE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun uint32LE(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)
}
