package com.nettarion.hyperborea.broadcast.wifi

@JvmInline
value class ShortUuid(val value: Int) {
    override fun toString(): String = "0x${value.toString(16).uppercase().padStart(4, '0')}"
}

sealed interface WifiMessage {
    sealed interface Request : WifiMessage {
        val sequence: Byte
    }

    data class DiscoverServices(override val sequence: Byte) : Request
    data class DiscoverCharacteristics(override val sequence: Byte, val serviceUuid: ShortUuid) : Request
    data class ReadCharacteristic(override val sequence: Byte, val charUuid: ShortUuid) : Request
    data class WriteCharacteristic(override val sequence: Byte, val charUuid: ShortUuid, val value: ByteArray) : Request {
        override fun equals(other: Any?): Boolean =
            other is WriteCharacteristic && sequence == other.sequence && charUuid == other.charUuid && value.contentEquals(other.value)
        override fun hashCode(): Int = 31 * (31 * sequence.hashCode() + charUuid.hashCode()) + value.contentHashCode()
    }
    data class EnableNotifications(override val sequence: Byte, val charUuid: ShortUuid, val enable: Boolean) : Request
    data class UnknownCompat(override val sequence: Byte) : Request

    data class Notification(val charUuid: ShortUuid, val value: ByteArray) : WifiMessage {
        override fun equals(other: Any?): Boolean =
            other is Notification && charUuid == other.charUuid && value.contentEquals(other.value)
        override fun hashCode(): Int = 31 * charUuid.hashCode() + value.contentHashCode()
    }
}
