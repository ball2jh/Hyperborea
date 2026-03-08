package com.nettarion.hyperborea.core.ftms

object ByteUtils {

    fun uint16LE(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), (value shr 8).toByte())

    fun sint16LE(value: Int): ByteArray {
        val clamped = value.coerceIn(-32768, 32767)
        return byteArrayOf((clamped and 0xFF).toByte(), (clamped shr 8).toByte())
    }

    fun uint24LE(value: Long): ByteArray =
        byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
        )

    fun putUint32LE(dest: ByteArray, offset: Int, value: Long) {
        dest[offset] = (value and 0xFF).toByte()
        dest[offset + 1] = ((value shr 8) and 0xFF).toByte()
        dest[offset + 2] = ((value shr 16) and 0xFF).toByte()
        dest[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
