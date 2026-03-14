package com.nettarion.hyperborea.hardware.fitpro.v1

sealed interface V1Message {

    sealed interface Outgoing : V1Message {
        data class SupportedDevices(val deviceId: Int = DEVICE_MAIN) : Outgoing
        data class Connect(val deviceId: Int = DEVICE_FITNESS_BIKE) : Outgoing
        data class Disconnect(val deviceId: Int = DEVICE_FITNESS_BIKE) : Outgoing
        data class DeviceInfo(val deviceId: Int = DEVICE_MAIN) : Outgoing
        data class SystemInfo(val deviceId: Int = DEVICE_MAIN) : Outgoing
        data class VersionInfo(val deviceId: Int = DEVICE_MAIN) : Outgoing
        data class VerifySecurity(
            val deviceId: Int = DEVICE_MAIN,
            val hash: ByteArray,
            val secretKey: Int,
        ) : Outgoing {
            override fun equals(other: Any?) = other is VerifySecurity &&
                deviceId == other.deviceId && hash.contentEquals(other.hash) && secretKey == other.secretKey
            override fun hashCode() = 31 * (31 * deviceId + hash.contentHashCode()) + secretKey
        }
        data class ReadWriteData(
            val deviceId: Int = DEVICE_MAIN,
            val writeFields: Map<V1DataField, Float> = emptyMap(),
            val readFields: Set<V1DataField> = emptySet(),
        ) : Outgoing
        data class Calibrate(val deviceId: Int = DEVICE_GRADE) : Outgoing
    }

    sealed interface Incoming : V1Message {
        data class SupportedDevicesResponse(val deviceIds: List<Int>) : Incoming
        data class ConnectAck(val deviceId: Int) : Incoming
        data class DisconnectAck(val deviceId: Int) : Incoming
        data class DeviceInfoResponse(
            val deviceId: Int,
            val softwareVersion: Int,
            val hardwareVersion: Int,
            val serialNumber: Int,
            val raw: ByteArray,
        ) : Incoming {
            override fun equals(other: Any?) = other is DeviceInfoResponse &&
                deviceId == other.deviceId && raw.contentEquals(other.raw)
            override fun hashCode() = 31 * deviceId + raw.contentHashCode()
        }
        data class SystemInfoResponse(
            val partNumber: Int,
            val model: Int,
        ) : Incoming
        data class VersionInfoResponse(
            val masterLibraryVersion: Int,
            val masterLibraryBuild: Int,
        ) : Incoming
        data class SecurityResponse(
            val unlockedKey: Int,
            val isUnlocked: Boolean,
        ) : Incoming
        data class DataResponse(
            val status: Int,
            val fields: Map<V1DataField, Float>,
        ) : Incoming
        data class GenericResponse(val commandId: Int, val status: Int, val payload: ByteArray) : Incoming {
            override fun equals(other: Any?) = other is GenericResponse &&
                commandId == other.commandId && payload.contentEquals(other.payload)
            override fun hashCode() = 31 * commandId + payload.contentHashCode()
        }
        data class Unknown(val raw: ByteArray) : Incoming {
            override fun equals(other: Any?) = other is Unknown && raw.contentEquals(other.raw)
            override fun hashCode() = raw.contentHashCode()
        }
    }

    companion object {
        const val DEVICE_MAIN = 0x02
        const val DEVICE_PORTAL = 0x03
        const val DEVICE_TREADMILL = 0x04
        const val DEVICE_INCLINE_TRAINER = 0x05
        const val DEVICE_ELLIPTICAL = 0x06
        const val DEVICE_FITNESS_BIKE = 0x07
        const val DEVICE_SPIN_BIKE = 0x08
        const val DEVICE_VERTICAL_ELLIPTICAL = 0x09
        const val DEVICE_FREE_STRIDER = 0x13
        const val DEVICE_ROWER = 0x14
        const val DEVICE_GRADE = 0x42

        /** Equipment device IDs that represent actual exercise machines. */
        val EQUIPMENT_DEVICE_IDS = setOf(
            DEVICE_TREADMILL, DEVICE_INCLINE_TRAINER, DEVICE_ELLIPTICAL,
            DEVICE_FITNESS_BIKE, DEVICE_SPIN_BIKE, DEVICE_VERTICAL_ELLIPTICAL,
            DEVICE_FREE_STRIDER, DEVICE_ROWER,
        )

        const val STATUS_DONE = 0x02
        const val STATUS_IN_PROGRESS = 0x03
        const val STATUS_SECURITY_BLOCK = 0x08
    }
}

/**
 * V1 BitField definitions with correct IDs, sizes, and converter types.
 * Source: GlassOS glassos.bitfield.BitField
 *
 * The fieldIndex determines the section-based bitmask position:
 *   section = fieldIndex / 8
 *   bit     = fieldIndex % 8
 */
enum class V1DataField(val fieldIndex: Int, val sizeBytes: Int, val converter: V1Converter) {
    KPH(0, 2, V1Converter.SPEED),
    GRADE(1, 2, V1Converter.GRADE),
    RESISTANCE(2, 2, V1Converter.RESISTANCE),
    WATTS(3, 2, V1Converter.SHORT),
    CURRENT_DISTANCE(4, 4, V1Converter.INT),
    RPM(5, 2, V1Converter.SHORT),
    DISTANCE(6, 4, V1Converter.INT),
    KEY_OBJECT(7, 14, V1Converter.KEY_OBJECT),
    VOLUME(9, 1, V1Converter.BYTE),
    PULSE(10, 4, V1Converter.PULSE),
    RUNNING_TIME(11, 4, V1Converter.INT),
    WORKOUT_MODE(12, 1, V1Converter.BYTE),
    CALORIES(13, 4, V1Converter.CALORIES),
    LAP_TIME(15, 2, V1Converter.SHORT),
    ACTUAL_KPH(16, 2, V1Converter.SPEED),
    ACTUAL_INCLINE(17, 2, V1Converter.GRADE),
    CURRENT_TIME(20, 4, V1Converter.INT),
    CURRENT_CALORIES(21, 4, V1Converter.CALORIES),
    GOAL_TIME(22, 4, V1Converter.INT),
    GEAR(26, 1, V1Converter.BYTE),
    MAX_GRADE(27, 2, V1Converter.GRADE),
    MIN_GRADE(28, 2, V1Converter.GRADE),
    MAX_KPH(30, 2, V1Converter.SPEED),
    MIN_KPH(31, 2, V1Converter.SPEED),
    PAUSE_TIMEOUT(35, 2, V1Converter.SHORT),
    SYSTEM_UNITS(36, 1, V1Converter.BYTE),
    MAX_RESISTANCE_LEVEL(42, 1, V1Converter.BYTE),
    WARMUP_TIMEOUT(46, 2, V1Converter.SHORT),
    MAX_PULSE(49, 1, V1Converter.BYTE),
    AVERAGE_GRADE(52, 2, V1Converter.GRADE),
    AVERAGE_WATTS(54, 2, V1Converter.SHORT),
    MAX_RPM(57, 2, V1Converter.SHORT),
    WATT_GOAL(61, 2, V1Converter.SHORT),
    DISTANCE_GOAL(64, 4, V1Converter.INT),
    COOLDOWN_TIMEOUT(71, 2, V1Converter.SHORT),
    VERTICAL_METER_NET(75, 4, V1Converter.VERTICAL),
    VERTICAL_METER_GAIN(76, 4, V1Converter.VERTICAL),
    IDLE_MODE_LOCKOUT(95, 1, V1Converter.BYTE),
    START_REQUESTED(96, 1, V1Converter.BYTE),
    FAN_STATE(98, 1, V1Converter.BYTE),
    RECOVERABLE_PAUSED_TIME(103, 4, V1Converter.INT),
    REQUIRE_START_REQUESTED(108, 1, V1Converter.BYTE),
    STROKES(109, 2, V1Converter.SHORT),
    STROKES_PER_MINUTE(110, 1, V1Converter.BYTE),
    FIVE_HUNDRED_SPLIT(111, 2, V1Converter.SHORT),
    AVG_FIVE_HUNDRED_SPLIT(112, 2, V1Converter.SHORT),
    IS_READY_TO_DISCONNECT(116, 1, V1Converter.BYTE),
    IS_CONSTANT_WATTS_MODE(119, 1, V1Converter.BYTE),
    MOTOR_TOTAL_DISTANCE(69, 4, V1Converter.INT),
    TOTAL_TIME(70, 4, V1Converter.INT),
    ;

    companion object {
        private val byFieldIndex = entries.associateBy { it.fieldIndex }
        fun fromFieldIndex(index: Int): V1DataField? = byFieldIndex[index]

        /** Fields to read during the polling loop — matches GlassOS periodic set plus KEY_OBJECT. */
        val periodicReadFields: Set<V1DataField> = setOf(
            // Sensor data
            WATTS, RPM, PULSE, ACTUAL_KPH, ACTUAL_INCLINE,
            // Session tracking
            CURRENT_DISTANCE, CURRENT_CALORIES, CURRENT_TIME,
            GRADE, RESISTANCE, WORKOUT_MODE,
            LAP_TIME, AVERAGE_GRADE, AVERAGE_WATTS,
            VERTICAL_METER_NET, VERTICAL_METER_GAIN,
            START_REQUESTED, RECOVERABLE_PAUSED_TIME,
            // Safety key state (14 bytes, not processed)
            KEY_OBJECT,
            // Config/target readback (GlassOS periodic set)
            KPH, VOLUME, GEAR, GOAL_TIME,
            SYSTEM_UNITS, WARMUP_TIMEOUT, COOLDOWN_TIMEOUT,
            WATT_GOAL, FAN_STATE, IS_CONSTANT_WATTS_MODE,
            IS_READY_TO_DISCONNECT,
            // Rower data
            STROKES, STROKES_PER_MINUTE,
            FIVE_HUNDRED_SPLIT, AVG_FIVE_HUNDRED_SPLIT,
        )

        /** Fields read once at startup — device limits + equipment lifetime stats. */
        val startupReadFields: Set<V1DataField> = setOf(
            MAX_GRADE, MIN_GRADE, MAX_KPH, MIN_KPH, MAX_RESISTANCE_LEVEL,
            MOTOR_TOTAL_DISTANCE, TOTAL_TIME,
        )
    }
}

enum class V1Converter {
    SPEED,
    GRADE,
    RESISTANCE,
    SHORT,
    INT,
    BYTE,
    PULSE,
    CALORIES,
    VERTICAL,
    KEY_OBJECT,
}
