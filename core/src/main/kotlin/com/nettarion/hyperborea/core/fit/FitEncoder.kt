package com.nettarion.hyperborea.core.fit

import java.io.ByteArrayOutputStream

/**
 * Binary FIT protocol encoder. Takes [FitMessage] objects, auto-manages definition
 * messages, and produces a valid FIT file as [ByteArray].
 *
 * Usage:
 * ```
 * val encoder = FitEncoder()
 * encoder.write(fileIdMessage)
 * encoder.write(recordMessage)
 * val bytes = encoder.build()
 * ```
 */
class FitEncoder {

    private val dataBuffer = ByteArrayOutputStream()

    /** globalMsgNum → assigned local message number (0–15). */
    private val localMsgNums = mutableMapOf<Int, Int>()

    /**
     * Tracks the field structure (list of defNum+size+baseType triples) for each local
     * message number that has been defined. If a new message has the same global msg num
     * but different fields, we re-emit the definition.
     */
    private val definedStructures = mutableMapOf<Int, List<FieldDef>>()

    private var nextLocalMsgNum = 0

    fun write(message: FitMessage) {
        val localNum = localMsgNums.getOrPut(message.globalMsgNum) {
            (nextLocalMsgNum++).also {
                require(it <= 15) { "FIT supports at most 16 local message types" }
            }
        }

        val fieldDefs = message.fields.map { FieldDef(it.defNum, it.value.size, it.value.baseType) }

        // Emit definition if this is the first time or the field structure changed.
        if (definedStructures[localNum] != fieldDefs) {
            writeDefinition(localNum, message.globalMsgNum, fieldDefs)
            definedStructures[localNum] = fieldDefs
        }

        writeDataMessage(localNum, message.fields)
    }

    fun build(): ByteArray {
        val data = dataBuffer.toByteArray()

        val out = ByteArrayOutputStream(14 + data.size + 2)

        // --- File header (14 bytes) ---
        out.write(14)                                   // header size
        out.write(PROTOCOL_VERSION)                     // protocol version (2.0)
        out.write(PROFILE_VERSION and 0xFF)             // profile version LSB
        out.write((PROFILE_VERSION shr 8) and 0xFF)     // profile version MSB
        // data size (4 bytes LE)
        out.write(data.size and 0xFF)
        out.write((data.size shr 8) and 0xFF)
        out.write((data.size shr 16) and 0xFF)
        out.write((data.size shr 24) and 0xFF)
        // ".FIT" ASCII
        out.write('.'.code)
        out.write('F'.code)
        out.write('I'.code)
        out.write('T'.code)

        // Header CRC (bytes 0–11)
        val headerBytes = out.toByteArray() // 12 bytes so far
        val headerCrc = crc16(headerBytes, 0, 12)
        out.write(headerCrc and 0xFF)
        out.write((headerCrc shr 8) and 0xFF)

        // Data records
        out.write(data)

        // File CRC (covers header + data, i.e. everything so far)
        val allBytes = out.toByteArray()
        val fileCrc = crc16(allBytes, 0, allBytes.size)
        out.write(fileCrc and 0xFF)
        out.write((fileCrc shr 8) and 0xFF)

        return out.toByteArray()
    }

    // --- Private helpers ---

    private fun writeDefinition(localNum: Int, globalMsgNum: Int, fieldDefs: List<FieldDef>) {
        // Record header: bit6=1 (definition), bits 0–3 = local msg num
        dataBuffer.write(0x40 or (localNum and 0x0F))
        dataBuffer.write(0) // reserved
        dataBuffer.write(0) // architecture: 0 = little-endian
        // Global message number (2 bytes LE)
        dataBuffer.write(globalMsgNum and 0xFF)
        dataBuffer.write((globalMsgNum shr 8) and 0xFF)
        // Number of fields
        dataBuffer.write(fieldDefs.size)
        // Field definitions (3 bytes each)
        for (def in fieldDefs) {
            dataBuffer.write(def.defNum and 0xFF)
            dataBuffer.write(def.size and 0xFF)
            dataBuffer.write(def.baseType.toInt() and 0xFF)
        }
    }

    private fun writeDataMessage(localNum: Int, fields: List<FitField>) {
        // Record header: bit6=0 (data), bits 0–3 = local msg num
        dataBuffer.write(localNum and 0x0F)
        for (field in fields) {
            writeValue(field.value)
        }
    }

    private fun writeValue(value: FitValue) {
        when (value) {
            is FitValue.Uint8 -> {
                dataBuffer.write((value.value ?: 0xFF) and 0xFF)
            }
            is FitValue.Uint16 -> {
                val v = value.value ?: 0xFFFF
                dataBuffer.write(v and 0xFF)
                dataBuffer.write((v shr 8) and 0xFF)
            }
            is FitValue.Uint32 -> {
                val v = value.value ?: 0xFFFFFFFFL
                dataBuffer.write((v and 0xFF).toInt())
                dataBuffer.write(((v shr 8) and 0xFF).toInt())
                dataBuffer.write(((v shr 16) and 0xFF).toInt())
                dataBuffer.write(((v shr 24) and 0xFF).toInt())
            }
            is FitValue.Sint16 -> {
                val v = value.value ?: 0x7FFF
                dataBuffer.write(v and 0xFF)
                dataBuffer.write((v shr 8) and 0xFF)
            }
            is FitValue.Sint32 -> {
                val v = value.value ?: 0x7FFFFFFF
                dataBuffer.write(v and 0xFF)
                dataBuffer.write((v shr 8) and 0xFF)
                dataBuffer.write((v shr 16) and 0xFF)
                dataBuffer.write((v shr 24) and 0xFF)
            }
            is FitValue.Enum8 -> {
                dataBuffer.write((value.value ?: 0xFF) and 0xFF)
            }
            is FitValue.StringVal -> {
                val bytes = value.value.toByteArray(Charsets.UTF_8)
                val len = minOf(bytes.size, value.maxLen - 1) // leave room for null terminator
                dataBuffer.write(bytes, 0, len)
                // Pad remaining bytes with 0x00 (null)
                repeat(value.maxLen - len) { dataBuffer.write(0) }
            }
        }
    }

    private data class FieldDef(val defNum: Int, val size: Int, val baseType: Byte)

    companion object {
        internal const val FIT_EPOCH_OFFSET = 631065600L
        private const val PROTOCOL_VERSION = 0x20 // 2.0
        private const val PROFILE_VERSION = 21195 // 21.195

        private val CRC_TABLE = intArrayOf(
            0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
            0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
        )

        internal fun crc16(data: ByteArray, offset: Int, length: Int): Int {
            var crc = 0
            for (i in offset until offset + length) {
                val byte = data[i].toInt() and 0xFF
                // Lower nibble
                var tmp = CRC_TABLE[crc and 0xF]
                crc = (crc shr 4) and 0x0FFF
                crc = crc xor tmp xor CRC_TABLE[byte and 0xF]
                // Upper nibble
                tmp = CRC_TABLE[crc and 0xF]
                crc = (crc shr 4) and 0x0FFF
                crc = crc xor tmp xor CRC_TABLE[(byte shr 4) and 0xF]
            }
            return crc
        }
    }
}
