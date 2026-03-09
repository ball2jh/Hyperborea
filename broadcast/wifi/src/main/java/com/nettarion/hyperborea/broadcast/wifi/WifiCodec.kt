package com.nettarion.hyperborea.broadcast.wifi

import java.io.EOFException
import java.io.InputStream

object WifiCodec {

    const val HEADER_SIZE = 6
    const val VERSION: Byte = 0x01
    const val MAX_BODY_SIZE = 1024

    // Message identifiers
    const val ID_DISCOVER_SERVICES: Byte = 0x01
    const val ID_DISCOVER_CHARACTERISTICS: Byte = 0x02
    const val ID_READ_CHARACTERISTIC: Byte = 0x03
    const val ID_WRITE_CHARACTERISTIC: Byte = 0x04
    const val ID_ENABLE_NOTIFICATIONS: Byte = 0x05
    const val ID_NOTIFICATION: Byte = 0x06
    const val ID_UNKNOWN_COMPAT: Byte = 0x07

    // Response codes
    const val RESP_SUCCESS: Byte = 0x00
    const val RESP_UNKNOWN_TYPE: Byte = 0x01
    const val RESP_UNEXPECTED_ERROR: Byte = 0x02
    const val RESP_SERVICE_NOT_FOUND: Byte = 0x03
    const val RESP_CHAR_NOT_FOUND: Byte = 0x04
    const val RESP_OP_NOT_SUPPORTED: Byte = 0x05

    fun readRequest(input: InputStream): WifiMessage.Request? {
        val header = readExact(input, HEADER_SIZE) ?: return null
        val version = header[0]
        if (version != VERSION) return null

        val identifier = header[1]
        val sequence = header[2]
        val length = ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)

        if (length > MAX_BODY_SIZE) return null

        val body = if (length > 0) readExact(input, length) ?: return null else ByteArray(0)

        return when (identifier) {
            ID_DISCOVER_SERVICES -> WifiMessage.DiscoverServices(sequence)
            ID_DISCOVER_CHARACTERISTICS -> {
                if (body.size < 16) return null
                WifiMessage.DiscoverCharacteristics(sequence, decodeShortUuid(body, 0))
            }
            ID_READ_CHARACTERISTIC -> {
                if (body.size < 16) return null
                WifiMessage.ReadCharacteristic(sequence, decodeShortUuid(body, 0))
            }
            ID_WRITE_CHARACTERISTIC -> {
                if (body.size < 16) return null
                val uuid = decodeShortUuid(body, 0)
                val value = body.copyOfRange(16, body.size)
                WifiMessage.WriteCharacteristic(sequence, uuid, value)
            }
            ID_ENABLE_NOTIFICATIONS -> {
                if (body.size < 17) return null
                val uuid = decodeShortUuid(body, 0)
                val enable = body[16] != 0x00.toByte()
                WifiMessage.EnableNotifications(sequence, uuid, enable)
            }
            ID_UNKNOWN_COMPAT -> WifiMessage.UnknownCompat(sequence)
            else -> null
        }
    }

    fun encodeResponse(identifier: Byte, sequence: Byte, responseCode: Byte, payload: ByteArray = ByteArray(0)): ByteArray {
        val length = payload.size
        val result = ByteArray(HEADER_SIZE + length)
        result[0] = VERSION
        result[1] = identifier
        result[2] = sequence
        result[3] = responseCode
        result[4] = (length shr 8).toByte()
        result[5] = (length and 0xFF).toByte()
        payload.copyInto(result, HEADER_SIZE)
        return result
    }

    fun encodeNotification(charUuid: ShortUuid, value: ByteArray): ByteArray {
        val payload = encodeUuidBlob(charUuid) + value
        return encodeResponse(ID_NOTIFICATION, 0x00, RESP_SUCCESS, payload)
    }

    fun encodeUuidBlob(shortUuid: ShortUuid): ByteArray {
        val blob = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // bytes 0-3 (short UUID at 2-3)
            0x00, 0x00, 0x10, 0x00, // bytes 4-7
            0x80.toByte(), 0x00, 0x00, 0x80.toByte(), // bytes 8-11
            0x5F, 0x9B.toByte(), 0x34, 0xFB.toByte(), // bytes 12-15
        )
        blob[2] = (shortUuid.value shr 8).toByte()
        blob[3] = (shortUuid.value and 0xFF).toByte()
        return blob
    }

    fun decodeShortUuid(blob: ByteArray, offset: Int): ShortUuid {
        val hi = blob[offset + 2].toInt() and 0xFF
        val lo = blob[offset + 3].toInt() and 0xFF
        return ShortUuid((hi shl 8) or lo)
    }

    private fun readExact(input: InputStream, count: Int): ByteArray? {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(buf, offset, count - offset)
            if (n < 0) {
                if (offset == 0) return null
                throw EOFException("Unexpected end of stream after $offset/$count bytes")
            }
            offset += n
        }
        return buf
    }
}
