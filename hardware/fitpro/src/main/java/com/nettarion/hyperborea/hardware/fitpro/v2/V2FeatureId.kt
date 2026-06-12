package com.nettarion.hyperborea.hardware.fitpro.v2

enum class V2FeatureId(val code: Int) {
    /** Equipment-type code reported by the console — see [V2Session.mapReportedDeviceType]. */
    DEVICE_TYPE(10),
    SYSTEM_MODE(102),
    /**
     * "Idle mode lockout" — written UNLOCKED (0) once at connect, mirroring the stock service's
     * bring-up (FitPro2Console). Write-only for us; not subscribed.
     */
    IDLE_SYSTEM_MODE_LOCK(103),
    /** Rider weight (kg) — written for the console's own calorie estimation. */
    USER_WEIGHT_KG(105),
    /** Console keypad presses — key-code values shared with the V1 keypad field. */
    KEY_COOKED(109),
    /** Equipment lifetime usage, seconds. */
    TOTAL_IN_USE_SECONDS(113),
    /** Console asks the client to release the link. */
    REQUEST_DISCONNECT(123),
    /** Console fan state/level. */
    FAN_STATE(129),
    /**
     * MCU heartbeat interval (ms) — a single CONFIGURATION write of 720 at connect, mirroring the
     * stock service (HeartbeatCoroutine writes it once; nothing re-writes it periodically — the
     * repo doc's "every 720ms" claim describes the stock app's internal 30s value-collection
     * cycle, not wire traffic). Write-only for us; not subscribed.
     */
    HEART_BEAT_INTERVAL(161),
    CURRENT_CALORIES(202),
    PULSE(222),
    DISTANCE(252),
    /** Equipment lifetime odometer, metres. */
    TOTAL_MACHINE_DISTANCE(256),
    TARGET_KPH(301),
    CURRENT_KPH(302),
    RPM(322),
    TARGET_GRADE(401),
    CURRENT_GRADE(402),
    TARGET_RESISTANCE(503),
    MAX_RESISTANCE(504),
    WATTS(522),
    GOAL_WATTS(523),
    /** The console workout state — its value is a [V2WorkoutMode] ordinal. This is the one to drive for start/pause/resume/stop. */
    WORKOUT_STATE(602),
    RUNNING_TIME(604),
    ;

    val wireLo: Byte get() = (code and 0xFF).toByte()
    val wireHi: Byte get() = ((code shr 8) and 0xFF).toByte()

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun fromCode(code: Int): V2FeatureId? = byCode[code]

        fun fromWireBytes(lo: Byte, hi: Byte): V2FeatureId? {
            val code = (lo.toInt() and 0xFF) or ((hi.toInt() and 0xFF) shl 8)
            return fromCode(code)
        }

        val subscribable: List<V2FeatureId> = listOf(
            DEVICE_TYPE, SYSTEM_MODE, WORKOUT_STATE, CURRENT_CALORIES, PULSE, DISTANCE,
            CURRENT_KPH, RPM, CURRENT_GRADE, TARGET_RESISTANCE, MAX_RESISTANCE, WATTS,
            RUNNING_TIME, KEY_COOKED, REQUEST_DISCONNECT, TOTAL_IN_USE_SECONDS,
            TOTAL_MACHINE_DISTANCE,
        )
    }
}
