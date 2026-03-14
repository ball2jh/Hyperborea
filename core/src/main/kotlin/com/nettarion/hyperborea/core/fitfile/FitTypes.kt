package com.nettarion.hyperborea.core.fitfile

/** A typed FIT field value. Each variant knows its base type and wire size. */
sealed interface FitValue {
    val baseType: Byte
    val size: Int

    data class Uint8(val value: Int?) : FitValue {
        override val baseType: Byte get() = 0x02
        override val size: Int get() = 1
    }

    data class Uint16(val value: Int?) : FitValue {
        override val baseType: Byte get() = 0x84.toByte()
        override val size: Int get() = 2
    }

    data class Uint32(val value: Long?) : FitValue {
        override val baseType: Byte get() = 0x86.toByte()
        override val size: Int get() = 4
    }

    data class Sint16(val value: Int?) : FitValue {
        override val baseType: Byte get() = 0x83.toByte()
        override val size: Int get() = 2
    }

    data class Sint32(val value: Int?) : FitValue {
        override val baseType: Byte get() = 0x85.toByte()
        override val size: Int get() = 4
    }

    data class Enum8(val value: Int?) : FitValue {
        override val baseType: Byte get() = 0x00
        override val size: Int get() = 1
    }

    data class StringVal(val value: String, val maxLen: Int) : FitValue {
        override val baseType: Byte get() = 0x07
        override val size: Int get() = maxLen
    }
}

/** A single field in a FIT message. */
data class FitField(val defNum: Int, val value: FitValue)

/** A complete FIT message ready for encoding. */
data class FitMessage(val globalMsgNum: Int, val fields: List<FitField>)
