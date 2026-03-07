package com.nettarion.hyperborea.hardware.fitpro.v1

sealed interface V1Message {

    sealed interface Outgoing : V1Message {
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
    }

    sealed interface Incoming : V1Message {
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
        const val DEVICE_FITNESS_BIKE = 0x07

        const val STATUS_DONE = 0x02
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
    PULSE(10, 4, V1Converter.PULSE),
    RUNNING_TIME(11, 4, V1Converter.INT),
    WORKOUT_MODE(12, 1, V1Converter.BYTE),
    CALORIES(13, 4, V1Converter.CALORIES),
    ACTUAL_KPH(16, 2, V1Converter.SPEED),
    ACTUAL_INCLINE(17, 2, V1Converter.GRADE),
    CURRENT_TIME(20, 4, V1Converter.INT),
    CURRENT_CALORIES(21, 4, V1Converter.CALORIES),
    MAX_RESISTANCE_LEVEL(42, 1, V1Converter.BYTE),
    WATT_GOAL(61, 2, V1Converter.SHORT),
    IDLE_MODE_LOCKOUT(95, 1, V1Converter.BYTE),
    REQUIRE_START_REQUESTED(108, 1, V1Converter.BYTE),
    ;

    companion object {
        private val byFieldIndex = entries.associateBy { it.fieldIndex }
        fun fromFieldIndex(index: Int): V1DataField? = byFieldIndex[index]

        /** Fields to read during the polling loop (sensors + state). */
        val periodicReadFields: Set<V1DataField> = setOf(
            WATTS, RPM, PULSE, ACTUAL_KPH, ACTUAL_INCLINE,
            RUNNING_TIME, DISTANCE, CALORIES,
            CURRENT_DISTANCE, CURRENT_CALORIES, CURRENT_TIME,
            GRADE, RESISTANCE, WORKOUT_MODE,
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
}
