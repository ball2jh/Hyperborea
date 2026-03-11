package com.nettarion.hyperborea.core.fit

import com.nettarion.hyperborea.core.model.Profile
import com.nettarion.hyperborea.core.model.RideSummary
import com.nettarion.hyperborea.core.model.WorkoutSample

/**
 * Maps domain types ([RideSummary], [WorkoutSample]) to a FIT activity file.
 * Stateless — all state lives in [FitEncoder].
 */
object FitActivityBuilder {

    fun buildActivityFile(
        summary: RideSummary,
        samples: List<WorkoutSample>,
        profile: Profile? = null,
    ): ByteArray {
        val encoder = FitEncoder()
        val startTs = unixToFitTimestamp(summary.startedAt)
        val endTs = startTs + summary.durationSeconds

        encoder.write(buildFileId(startTs))
        encoder.write(buildEventStart(startTs))
        for (sample in samples) {
            encoder.write(buildRecord(startTs, sample))
        }
        encoder.write(buildEventStop(endTs))
        encoder.write(buildLap(summary, startTs, endTs))
        encoder.write(buildSession(summary, startTs, endTs))
        encoder.write(buildActivity(summary, endTs))

        return encoder.build()
    }

    // --- Message builders ---

    private fun buildFileId(startTs: Long): FitMessage = FitMessage(MESG_FILE_ID, listOf(
        FitField(FIELD_FILE_ID_TYPE, FitValue.Enum8(FILE_TYPE_ACTIVITY)),
        FitField(FIELD_FILE_ID_MANUFACTURER, FitValue.Uint16(MANUFACTURER_DEVELOPMENT)),
        FitField(FIELD_FILE_ID_PRODUCT, FitValue.Uint16(0)),
        FitField(FIELD_FILE_ID_SERIAL_NUMBER, FitValue.Uint32(1L)),
        FitField(FIELD_FILE_ID_TIME_CREATED, FitValue.Uint32(startTs)),
    ))

    private fun buildEventStart(ts: Long): FitMessage = FitMessage(MESG_EVENT, listOf(
        FitField(FIELD_TIMESTAMP, FitValue.Uint32(ts)),
        FitField(FIELD_EVENT_EVENT, FitValue.Enum8(EVENT_TIMER)),
        FitField(FIELD_EVENT_EVENT_TYPE, FitValue.Enum8(EVENT_TYPE_START)),
        FitField(FIELD_EVENT_DATA, FitValue.Uint32(0L)),
    ))

    private fun buildEventStop(ts: Long): FitMessage = FitMessage(MESG_EVENT, listOf(
        FitField(FIELD_TIMESTAMP, FitValue.Uint32(ts)),
        FitField(FIELD_EVENT_EVENT, FitValue.Enum8(EVENT_TIMER)),
        FitField(FIELD_EVENT_EVENT_TYPE, FitValue.Enum8(EVENT_TYPE_STOP_ALL)),
        FitField(FIELD_EVENT_DATA, FitValue.Uint32(0L)),
    ))

    private fun buildRecord(startTs: Long, sample: WorkoutSample): FitMessage {
        val ts = startTs + sample.timestampSeconds
        return FitMessage(MESG_RECORD, listOf(
            FitField(FIELD_TIMESTAMP, FitValue.Uint32(ts)),
            FitField(FIELD_RECORD_HEART_RATE, FitValue.Uint8(sample.heartRate)),
            FitField(FIELD_RECORD_CADENCE, FitValue.Uint8(sample.cadence)),
            FitField(FIELD_RECORD_DISTANCE, FitValue.Uint32(sample.distanceKm?.let { kmToFitDistance(it) })),
            FitField(FIELD_RECORD_SPEED, FitValue.Uint16(sample.speedKph?.let { kphToFitSpeed(it) })),
            FitField(FIELD_RECORD_POWER, FitValue.Uint16(sample.power)),
            FitField(FIELD_RECORD_RESISTANCE, FitValue.Uint16(sample.resistance)),
            FitField(FIELD_RECORD_CALORIES, FitValue.Uint16(sample.calories)),
            FitField(FIELD_RECORD_GRADE, FitValue.Sint16(sample.incline?.let { (it * 100).toInt() })),
        ))
    }

    private fun buildLap(summary: RideSummary, startTs: Long, endTs: Long): FitMessage {
        val elapsed = secondsToFitTime(summary.durationSeconds)
        return FitMessage(MESG_LAP, listOf(
            FitField(FIELD_TIMESTAMP, FitValue.Uint32(endTs)),
            FitField(FIELD_LAP_START_TIME, FitValue.Uint32(startTs)),
            FitField(FIELD_LAP_TOTAL_ELAPSED_TIME, FitValue.Uint32(elapsed)),
            FitField(FIELD_LAP_TOTAL_TIMER_TIME, FitValue.Uint32(elapsed)),
            FitField(FIELD_LAP_EVENT, FitValue.Enum8(EVENT_LAP)),
            FitField(FIELD_LAP_EVENT_TYPE, FitValue.Enum8(EVENT_TYPE_STOP)),
            FitField(FIELD_LAP_SPORT, FitValue.Enum8(SPORT_CYCLING)),
            FitField(FIELD_LAP_TOTAL_DISTANCE, FitValue.Uint32(kmToFitDistance(summary.distanceKm))),
            FitField(FIELD_LAP_TOTAL_CALORIES, FitValue.Uint16(summary.calories)),
            FitField(FIELD_LAP_AVG_SPEED, FitValue.Uint16(summary.avgSpeedKph?.let { kphToFitSpeed(it) })),
            FitField(FIELD_LAP_MAX_SPEED, FitValue.Uint16(summary.maxSpeedKph?.let { kphToFitSpeed(it) })),
            FitField(FIELD_LAP_AVG_HEART_RATE, FitValue.Uint8(summary.avgHeartRate)),
            FitField(FIELD_LAP_MAX_HEART_RATE, FitValue.Uint8(summary.maxHeartRate)),
            FitField(FIELD_LAP_AVG_CADENCE, FitValue.Uint8(summary.avgCadence)),
            FitField(FIELD_LAP_MAX_CADENCE, FitValue.Uint8(summary.maxCadence)),
            FitField(FIELD_LAP_AVG_POWER, FitValue.Uint16(summary.avgPower)),
            FitField(FIELD_LAP_MAX_POWER, FitValue.Uint16(summary.maxPower)),
            FitField(FIELD_LAP_TOTAL_ASCENT, FitValue.Uint16(summary.totalElevationGainMeters?.toInt())),
            FitField(FIELD_LAP_TRIGGER, FitValue.Enum8(LAP_TRIGGER_SESSION_END)),
        ))
    }

    private fun buildSession(summary: RideSummary, startTs: Long, endTs: Long): FitMessage {
        val elapsed = secondsToFitTime(summary.durationSeconds)
        return FitMessage(MESG_SESSION, listOf(
            FitField(FIELD_TIMESTAMP, FitValue.Uint32(endTs)),
            FitField(FIELD_SESSION_START_TIME, FitValue.Uint32(startTs)),
            FitField(FIELD_SESSION_TOTAL_ELAPSED_TIME, FitValue.Uint32(elapsed)),
            FitField(FIELD_SESSION_TOTAL_TIMER_TIME, FitValue.Uint32(elapsed)),
            FitField(FIELD_SESSION_SPORT, FitValue.Enum8(SPORT_CYCLING)),
            FitField(FIELD_SESSION_SUB_SPORT, FitValue.Enum8(SUB_SPORT_INDOOR_CYCLING)),
            FitField(FIELD_SESSION_TOTAL_DISTANCE, FitValue.Uint32(kmToFitDistance(summary.distanceKm))),
            FitField(FIELD_SESSION_TOTAL_CALORIES, FitValue.Uint16(summary.calories)),
            FitField(FIELD_SESSION_AVG_SPEED, FitValue.Uint16(summary.avgSpeedKph?.let { kphToFitSpeed(it) })),
            FitField(FIELD_SESSION_MAX_SPEED, FitValue.Uint16(summary.maxSpeedKph?.let { kphToFitSpeed(it) })),
            FitField(FIELD_SESSION_AVG_HEART_RATE, FitValue.Uint8(summary.avgHeartRate)),
            FitField(FIELD_SESSION_MAX_HEART_RATE, FitValue.Uint8(summary.maxHeartRate)),
            FitField(FIELD_SESSION_AVG_CADENCE, FitValue.Uint8(summary.avgCadence)),
            FitField(FIELD_SESSION_MAX_CADENCE, FitValue.Uint8(summary.maxCadence)),
            FitField(FIELD_SESSION_AVG_POWER, FitValue.Uint16(summary.avgPower)),
            FitField(FIELD_SESSION_MAX_POWER, FitValue.Uint16(summary.maxPower)),
            FitField(FIELD_SESSION_TOTAL_ASCENT, FitValue.Uint16(summary.totalElevationGainMeters?.toInt())),
            FitField(FIELD_SESSION_NP, FitValue.Uint16(summary.normalizedPower)),
            FitField(FIELD_SESSION_TSS, FitValue.Uint16(summary.trainingStressScore?.let { (it * 10).toInt() })),
            FitField(FIELD_SESSION_IF, FitValue.Uint16(summary.intensityFactor?.let { (it * 1000).toInt() })),
            FitField(FIELD_SESSION_EVENT, FitValue.Enum8(EVENT_LAP)),
            FitField(FIELD_SESSION_EVENT_TYPE, FitValue.Enum8(EVENT_TYPE_STOP)),
            FitField(FIELD_SESSION_TRIGGER, FitValue.Enum8(SESSION_TRIGGER_ACTIVITY_END)),
        ))
    }

    private fun buildActivity(summary: RideSummary, endTs: Long): FitMessage {
        val totalTime = secondsToFitTime(summary.durationSeconds)
        return FitMessage(MESG_ACTIVITY, listOf(
            FitField(FIELD_TIMESTAMP, FitValue.Uint32(endTs)),
            FitField(FIELD_ACTIVITY_TOTAL_TIMER_TIME, FitValue.Uint32(totalTime)),
            FitField(FIELD_ACTIVITY_NUM_SESSIONS, FitValue.Uint16(1)),
            FitField(FIELD_ACTIVITY_TYPE, FitValue.Enum8(ACTIVITY_TYPE_MANUAL)),
            FitField(FIELD_ACTIVITY_EVENT, FitValue.Enum8(EVENT_ACTIVITY)),
            FitField(FIELD_ACTIVITY_EVENT_TYPE, FitValue.Enum8(EVENT_TYPE_STOP)),
        ))
    }

    // --- Unit conversions ---

    /** km/h → FIT speed (m/s × 1000). */
    private fun kphToFitSpeed(kph: Float): Int = (kph / 3.6f * 1000).toInt()

    /** km → FIT distance (m × 100). */
    private fun kmToFitDistance(km: Float): Long = (km * 100_000).toLong()

    /** Unix epoch millis → FIT timestamp (seconds since FIT epoch 1989-12-31T00:00:00Z). */
    private fun unixToFitTimestamp(epochMillis: Long): Long =
        epochMillis / 1000 - FitEncoder.FIT_EPOCH_OFFSET

    /** Duration seconds → FIT time (seconds × 1000, stored as uint32). */
    private fun secondsToFitTime(seconds: Long): Long = seconds * 1000

    // --- Global message numbers ---

    private const val MESG_FILE_ID = 0
    private const val MESG_SESSION = 18
    private const val MESG_LAP = 19
    private const val MESG_RECORD = 20
    private const val MESG_EVENT = 21
    private const val MESG_ACTIVITY = 34

    // --- Common fields ---

    private const val FIELD_TIMESTAMP = 253

    // --- File ID fields ---

    private const val FIELD_FILE_ID_TYPE = 0
    private const val FIELD_FILE_ID_MANUFACTURER = 1
    private const val FIELD_FILE_ID_PRODUCT = 2
    private const val FIELD_FILE_ID_SERIAL_NUMBER = 3
    private const val FIELD_FILE_ID_TIME_CREATED = 4

    // --- Event fields ---

    private const val FIELD_EVENT_EVENT = 0
    private const val FIELD_EVENT_EVENT_TYPE = 1
    private const val FIELD_EVENT_DATA = 3

    // --- Record fields ---

    private const val FIELD_RECORD_HEART_RATE = 3
    private const val FIELD_RECORD_CADENCE = 4
    private const val FIELD_RECORD_DISTANCE = 5
    private const val FIELD_RECORD_SPEED = 6
    private const val FIELD_RECORD_POWER = 7
    private const val FIELD_RECORD_RESISTANCE = 10
    private const val FIELD_RECORD_CALORIES = 33
    private const val FIELD_RECORD_GRADE = 41

    // --- Lap fields ---

    private const val FIELD_LAP_START_TIME = 2
    private const val FIELD_LAP_TOTAL_ELAPSED_TIME = 7
    private const val FIELD_LAP_TOTAL_TIMER_TIME = 8
    private const val FIELD_LAP_EVENT = 0
    private const val FIELD_LAP_EVENT_TYPE = 1
    private const val FIELD_LAP_SPORT = 25
    private const val FIELD_LAP_TOTAL_DISTANCE = 9
    private const val FIELD_LAP_TOTAL_CALORIES = 11
    private const val FIELD_LAP_AVG_SPEED = 13
    private const val FIELD_LAP_MAX_SPEED = 14
    private const val FIELD_LAP_AVG_HEART_RATE = 15
    private const val FIELD_LAP_MAX_HEART_RATE = 16
    private const val FIELD_LAP_AVG_CADENCE = 17
    private const val FIELD_LAP_MAX_CADENCE = 18
    private const val FIELD_LAP_AVG_POWER = 19
    private const val FIELD_LAP_MAX_POWER = 20
    private const val FIELD_LAP_TOTAL_ASCENT = 21
    private const val FIELD_LAP_TRIGGER = 24

    // --- Session fields ---

    private const val FIELD_SESSION_EVENT = 0
    private const val FIELD_SESSION_EVENT_TYPE = 1
    private const val FIELD_SESSION_START_TIME = 2
    private const val FIELD_SESSION_SPORT = 5
    private const val FIELD_SESSION_SUB_SPORT = 6
    private const val FIELD_SESSION_TOTAL_ELAPSED_TIME = 7
    private const val FIELD_SESSION_TOTAL_TIMER_TIME = 8
    private const val FIELD_SESSION_TOTAL_DISTANCE = 9
    private const val FIELD_SESSION_TOTAL_CALORIES = 11
    private const val FIELD_SESSION_AVG_SPEED = 14
    private const val FIELD_SESSION_MAX_SPEED = 15
    private const val FIELD_SESSION_AVG_HEART_RATE = 16
    private const val FIELD_SESSION_MAX_HEART_RATE = 17
    private const val FIELD_SESSION_AVG_CADENCE = 18
    private const val FIELD_SESSION_MAX_CADENCE = 19
    private const val FIELD_SESSION_AVG_POWER = 20
    private const val FIELD_SESSION_MAX_POWER = 21
    private const val FIELD_SESSION_TOTAL_ASCENT = 22
    private const val FIELD_SESSION_TRIGGER = 28
    private const val FIELD_SESSION_NP = 34
    private const val FIELD_SESSION_TSS = 35
    private const val FIELD_SESSION_IF = 36

    // --- Activity fields ---

    private const val FIELD_ACTIVITY_TOTAL_TIMER_TIME = 0
    private const val FIELD_ACTIVITY_NUM_SESSIONS = 1
    private const val FIELD_ACTIVITY_TYPE = 2
    private const val FIELD_ACTIVITY_EVENT = 3
    private const val FIELD_ACTIVITY_EVENT_TYPE = 4

    // --- Enum values ---

    private const val FILE_TYPE_ACTIVITY = 4
    private const val MANUFACTURER_DEVELOPMENT = 255
    private const val SPORT_CYCLING = 2
    private const val SUB_SPORT_INDOOR_CYCLING = 6
    private const val EVENT_TIMER = 0
    private const val EVENT_LAP = 9
    private const val EVENT_ACTIVITY = 26
    private const val EVENT_TYPE_START = 0
    private const val EVENT_TYPE_STOP = 1
    private const val EVENT_TYPE_STOP_ALL = 4
    private const val LAP_TRIGGER_SESSION_END = 7
    private const val SESSION_TRIGGER_ACTIVITY_END = 0
    private const val ACTIVITY_TYPE_MANUAL = 0
}
