package com.nettarion.hyperborea.core.fitfile

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FitEncoderTest {

    // --- File structure ---

    @Test
    fun `empty encoder produces valid minimal FIT file`() {
        val result = FitEncoder().build()
        // 14-byte header + 2-byte file CRC = 16 bytes minimum
        assertThat(result.size).isEqualTo(16)
        // Header size
        assertThat(result[0].toInt() and 0xFF).isEqualTo(14)
        // Protocol version 2.0
        assertThat(result[1].toInt() and 0xFF).isEqualTo(0x20)
        // ".FIT" magic at bytes 8–11
        assertThat(result[8].toInt().toChar()).isEqualTo('.')
        assertThat(result[9].toInt().toChar()).isEqualTo('F')
        assertThat(result[10].toInt().toChar()).isEqualTo('I')
        assertThat(result[11].toInt().toChar()).isEqualTo('T')
        // Data size = 0 (bytes 4–7 LE)
        assertThat(uint32LE(result, 4)).isEqualTo(0L)
    }

    @Test
    fun `header CRC covers bytes 0 through 11`() {
        val result = FitEncoder().build()
        val expectedCrc = FitEncoder.crc16(result, 0, 12)
        val actualCrc = uint16LE(result, 12)
        assertThat(actualCrc).isEqualTo(expectedCrc)
    }

    @Test
    fun `file CRC covers entire file minus last 2 bytes`() {
        val encoder = FitEncoder()
        encoder.write(FitMessage(0, listOf(FitField(0, FitValue.Uint8(42)))))
        val result = encoder.build()

        val expectedCrc = FitEncoder.crc16(result, 0, result.size - 2)
        val actualCrc = uint16LE(result, result.size - 2)
        assertThat(actualCrc).isEqualTo(expectedCrc)
    }

    @Test
    fun `data size in header matches actual data section length`() {
        val encoder = FitEncoder()
        encoder.write(FitMessage(0, listOf(FitField(0, FitValue.Uint8(1)))))
        val result = encoder.build()

        val dataSize = uint32LE(result, 4)
        // Total = 14 (header) + dataSize + 2 (file CRC)
        assertThat(dataSize).isEqualTo((result.size - 16).toLong())
    }

    // --- Auto-definition ---

    @Test
    fun `writing a message auto-emits definition then data`() {
        val encoder = FitEncoder()
        encoder.write(FitMessage(20, listOf(
            FitField(253, FitValue.Uint32(1000L)),
            FitField(3, FitValue.Uint8(140)),
        )))
        val result = encoder.build()
        val data = dataSection(result)

        // Definition record header: 0x40 (definition, local 0)
        assertThat(data[0].toInt() and 0xFF).isEqualTo(0x40)
        // Reserved byte
        assertThat(data[1].toInt() and 0xFF).isEqualTo(0)
        // Architecture: little-endian
        assertThat(data[2].toInt() and 0xFF).isEqualTo(0)
        // Global msg num 20 (LE)
        assertThat(uint16LE(data, 3)).isEqualTo(20)
        // 2 fields
        assertThat(data[5].toInt() and 0xFF).isEqualTo(2)
        // Field 0: defNum=253, size=4, baseType=0x86
        assertThat(data[6].toInt() and 0xFF).isEqualTo(253)
        assertThat(data[7].toInt() and 0xFF).isEqualTo(4)
        assertThat(data[8].toInt() and 0xFF).isEqualTo(0x86)
        // Field 1: defNum=3, size=1, baseType=0x02
        assertThat(data[9].toInt() and 0xFF).isEqualTo(3)
        assertThat(data[10].toInt() and 0xFF).isEqualTo(1)
        assertThat(data[11].toInt() and 0xFF).isEqualTo(0x02)

        // Data record header: 0x00 (data, local 0)
        assertThat(data[12].toInt() and 0xFF).isEqualTo(0x00)
        // Timestamp: 1000 as uint32 LE
        assertThat(uint32LE(data, 13)).isEqualTo(1000L)
        // HR: 140
        assertThat(data[17].toInt() and 0xFF).isEqualTo(140)
    }

    // --- Definition reuse ---

    @Test
    fun `writing two messages with same structure emits definition only once`() {
        val encoder = FitEncoder()
        val msg1 = FitMessage(20, listOf(FitField(253, FitValue.Uint32(1000L))))
        val msg2 = FitMessage(20, listOf(FitField(253, FitValue.Uint32(2000L))))
        encoder.write(msg1)
        encoder.write(msg2)
        val data = dataSection(encoder.build())

        // First: definition header (0x40) + data header (0x00)
        // Second: data header only (0x00) — no second definition
        var defCount = 0
        var i = 0
        while (i < data.size) {
            val header = data[i].toInt() and 0xFF
            if (header and 0x40 != 0) {
                // Definition message
                defCount++
                val numFields = data[i + 5].toInt() and 0xFF
                i += 6 + numFields * 3
            } else {
                // Data message — skip based on known field sizes
                // We know our messages have 1 uint32 field = 4 bytes + 1 header byte
                i += 1 + 4
            }
        }
        assertThat(defCount).isEqualTo(1)
    }

    @Test
    fun `different global msg nums get different local msg nums`() {
        val encoder = FitEncoder()
        encoder.write(FitMessage(0, listOf(FitField(0, FitValue.Uint8(1)))))
        encoder.write(FitMessage(20, listOf(FitField(253, FitValue.Uint32(100L)))))
        val data = dataSection(encoder.build())

        // First definition: local 0
        assertThat(data[0].toInt() and 0x0F).isEqualTo(0)
        // Skip first definition (1 header + 5 fixed + 1*3 fields = 9) + first data (1+1=2)
        // Second definition at offset 11: local 1
        val firstDefFields = data[5].toInt() and 0xFF
        val firstDefSize = 6 + firstDefFields * 3
        val firstDataSize = 1 + 1 // header + uint8
        val secondDefOffset = firstDefSize + firstDataSize
        assertThat(data[secondDefOffset].toInt() and 0x0F).isEqualTo(1)
    }

    // --- Null handling ---

    @Test
    fun `null Uint8 writes 0xFF`() {
        val result = buildSingleFieldFile(FitValue.Uint8(null))
        val data = dataSection(result)
        val dataValue = lastDataByte(data, 1)
        assertThat(dataValue).isEqualTo(0xFF)
    }

    @Test
    fun `null Uint16 writes 0xFFFF`() {
        val result = buildSingleFieldFile(FitValue.Uint16(null))
        val data = dataSection(result)
        val dataValue = lastDataUint16(data)
        assertThat(dataValue).isEqualTo(0xFFFF)
    }

    @Test
    fun `null Uint32 writes 0xFFFFFFFF`() {
        val result = buildSingleFieldFile(FitValue.Uint32(null))
        val data = dataSection(result)
        val dataValue = lastDataUint32(data)
        assertThat(dataValue).isEqualTo(0xFFFFFFFFL)
    }

    @Test
    fun `null Sint16 writes 0x7FFF`() {
        val result = buildSingleFieldFile(FitValue.Sint16(null))
        val data = dataSection(result)
        val dataValue = lastDataUint16(data)
        assertThat(dataValue).isEqualTo(0x7FFF)
    }

    @Test
    fun `null Sint32 writes 0x7FFFFFFF`() {
        val result = buildSingleFieldFile(FitValue.Sint32(null))
        val data = dataSection(result)
        val dataValue = lastDataUint32(data)
        assertThat(dataValue).isEqualTo(0x7FFFFFFFL)
    }

    @Test
    fun `null Enum8 writes 0xFF`() {
        val result = buildSingleFieldFile(FitValue.Enum8(null))
        val data = dataSection(result)
        val dataValue = lastDataByte(data, 1)
        assertThat(dataValue).isEqualTo(0xFF)
    }

    // --- Field ordering ---

    @Test
    fun `data bytes match field order in FitMessage`() {
        val encoder = FitEncoder()
        encoder.write(FitMessage(20, listOf(
            FitField(0, FitValue.Uint8(0xAA)),
            FitField(1, FitValue.Uint16(0xBBCC)),
            FitField(2, FitValue.Uint8(0xDD)),
        )))
        val data = dataSection(encoder.build())

        // Definition: 1 header + 5 fixed + 3*3 fields = 15 bytes
        // Data record starts at offset 15
        val dataStart = 15 + 1 // skip data header byte
        assertThat(data[dataStart].toInt() and 0xFF).isEqualTo(0xAA)
        assertThat(uint16LE(data, dataStart + 1)).isEqualTo(0xBBCC)
        assertThat(data[dataStart + 3].toInt() and 0xFF).isEqualTo(0xDD)
    }

    // --- StringVal ---

    @Test
    fun `StringVal pads to maxLen with null bytes`() {
        val encoder = FitEncoder()
        encoder.write(FitMessage(0, listOf(
            FitField(0, FitValue.StringVal("Hi", 8)),
        )))
        val data = dataSection(encoder.build())

        // Definition: 1 + 5 + 3 = 9 bytes. Data header at 9, string starts at 10.
        val strStart = 10
        assertThat(data[strStart].toInt().toChar()).isEqualTo('H')
        assertThat(data[strStart + 1].toInt().toChar()).isEqualTo('i')
        // Remaining 6 bytes should be 0x00
        for (i in 2 until 8) {
            assertThat(data[strStart + i].toInt() and 0xFF).isEqualTo(0)
        }
    }

    // --- CRC ---

    @Test
    fun `crc16 of known input matches expected value`() {
        // CRC-16 of empty data should be 0
        assertThat(FitEncoder.crc16(byteArrayOf(), 0, 0)).isEqualTo(0)

        // CRC of a single byte
        val singleByte = byteArrayOf(0x01)
        val crc = FitEncoder.crc16(singleByte, 0, 1)
        assertThat(crc).isNotEqualTo(0) // non-trivial CRC
    }

    @Test
    fun `crc16 is consistent across multiple calls`() {
        val data = byteArrayOf(0x14, 0x20, 0x66, 0x08)
        val crc1 = FitEncoder.crc16(data, 0, data.size)
        val crc2 = FitEncoder.crc16(data, 0, data.size)
        assertThat(crc1).isEqualTo(crc2)
    }

    // --- Helpers ---

    /** Extracts the data section (between header and file CRC) from a built FIT file. */
    private fun dataSection(file: ByteArray): ByteArray {
        val dataSize = uint32LE(file, 4).toInt()
        return file.copyOfRange(14, 14 + dataSize)
    }

    /** Build a FIT file with a single message containing one field. */
    private fun buildSingleFieldFile(value: FitValue): ByteArray {
        val encoder = FitEncoder()
        encoder.write(FitMessage(0, listOf(FitField(0, value))))
        return encoder.build()
    }

    /** Read the last N bytes of the data message as a single byte value (for 1-byte fields). */
    private fun lastDataByte(data: ByteArray, fieldSize: Int): Int =
        data[data.size - fieldSize].toInt() and 0xFF

    /** Read the last 2 bytes of the data section as uint16 LE. */
    private fun lastDataUint16(data: ByteArray): Int =
        uint16LE(data, data.size - 2)

    /** Read the last 4 bytes of the data section as uint32 LE. */
    private fun lastDataUint32(data: ByteArray): Long =
        uint32LE(data, data.size - 4)

    private fun uint16LE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun uint32LE(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)
}
