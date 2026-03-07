package com.nettarion.hyperborea.hardware.fitpro.v1

import java.nio.ByteBuffer
import java.nio.ByteOrder

object V1Codec {

    private const val CMD_READ_WRITE_DATA: Byte = 0x02
    private const val CMD_CONNECT: Byte = 0x04
    private const val CMD_DISCONNECT: Byte = 0x05
    private const val CMD_DEVICE_INFO: Byte = 0x81.toByte()
    private const val CMD_SYSTEM_INFO: Byte = 0x82.toByte()
    private const val CMD_VERSION_INFO: Byte = 0x84.toByte()
    private const val CMD_VERIFY_SECURITY: Byte = 0x90.toByte()

    private const val HEADER_SIZE = 4 // device, length, command, status/payload[0]

    private const val MULTI_PACKET_HEADER: Byte = 0xFE.toByte()
    private const val MULTI_PACKET_MARKER: Byte = 0x02
    private const val MAX_CHUNK_DATA: Int = 18
    private const val LAST_CHUNK_INDEX: Byte = 0xFF.toByte()

    fun encode(message: V1Message.Outgoing): List<ByteArray> = when (message) {
        is V1Message.Outgoing.Connect -> listOf(
            encodeSimple(message.deviceId, CMD_CONNECT, byteArrayOf())
        )
        is V1Message.Outgoing.Disconnect -> listOf(
            encodeSimple(message.deviceId, CMD_DISCONNECT, byteArrayOf())
        )
        is V1Message.Outgoing.DeviceInfo -> listOf(
            encodeSimple(message.deviceId, CMD_DEVICE_INFO, byteArrayOf())
        )
        is V1Message.Outgoing.SystemInfo -> listOf(
            encodeSimple(message.deviceId, CMD_SYSTEM_INFO, byteArrayOf(0x00, 0x00, 0x00))
        )
        is V1Message.Outgoing.VersionInfo -> listOf(
            encodeSimple(message.deviceId, CMD_VERSION_INFO, byteArrayOf(0x00, 0x00))
        )
        is V1Message.Outgoing.VerifySecurity -> {
            val payload = ByteArray(36)
            message.hash.copyInto(payload, 0, 0, 32)
            val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(message.secretKey)
            buf.array().copyInto(payload, 32)
            listOf(encodeSimple(message.deviceId, CMD_VERIFY_SECURITY, payload))
        }
        is V1Message.Outgoing.ReadWriteData -> encodeReadWriteData(message)
    }

    fun decode(packets: List<ByteArray>): V1Message.Incoming? {
        if (packets.isEmpty()) return null

        val data = reassemble(packets) ?: return null
        if (data.size < 3) return null

        val deviceId = data[0].toInt() and 0xFF
        val commandId = data[2].toInt() and 0xFF
        val status = if (data.size > 3) data[3].toInt() and 0xFF else 0
        val payload = if (data.size > HEADER_SIZE) data.copyOfRange(HEADER_SIZE, data.size - 1) else byteArrayOf()

        return when (commandId.toByte()) {
            CMD_CONNECT -> V1Message.Incoming.ConnectAck(deviceId)
            CMD_DISCONNECT -> V1Message.Incoming.DisconnectAck(deviceId)
            CMD_READ_WRITE_DATA -> decodeDataResponse(status, payload)
            CMD_DEVICE_INFO -> decodeDeviceInfoResponse(deviceId, data)
            CMD_SYSTEM_INFO -> decodeSystemInfoResponse(data)
            CMD_VERSION_INFO -> decodeVersionInfoResponse(data)
            CMD_VERIFY_SECURITY -> V1Message.Incoming.SecurityResponse(
                unlockedKey = if (data.size > 4) data[4].toInt() and 0xFF else 0,
                isUnlocked = status == V1Message.STATUS_DONE,
            )
            else -> V1Message.Incoming.GenericResponse(commandId, status, payload)
        }
    }

    fun decodeSingle(packet: ByteArray): V1Message.Incoming? {
        // Trim to declared length (byte[1]) — USB always returns 64 bytes
        val trimmed = if (packet.size > 1) {
            val declaredLen = packet[1].toInt() and 0xFF
            if (declaredLen in 3..packet.size) packet.copyOf(declaredLen) else packet
        } else {
            packet
        }
        return decode(listOf(trimmed))
    }

    fun checksum(data: ByteArray): Byte {
        var sum = 0
        for (b in data) {
            sum += b.toInt() and 0xFF
        }
        return (sum and 0xFF).toByte()
    }

    fun verifyChecksum(packet: ByteArray): Boolean {
        if (packet.size < 2) return false
        val expected = checksum(packet.copyOfRange(0, packet.size - 1))
        return expected == packet.last()
    }

    fun isMultiPacketHeader(packet: ByteArray): Boolean =
        packet.size >= 2 && packet[0] == MULTI_PACKET_HEADER && packet[1] == MULTI_PACKET_MARKER

    fun expectedPacketCount(headerPacket: ByteArray): Int {
        if (!isMultiPacketHeader(headerPacket) || headerPacket.size < 4) return 1
        return headerPacket[3].toInt() and 0xFF
    }

    private fun encodeSimple(deviceId: Int, commandId: Byte, payload: ByteArray): ByteArray {
        val totalLength = HEADER_SIZE + payload.size
        val packet = ByteArray(totalLength)
        packet[0] = deviceId.toByte()
        packet[1] = totalLength.toByte()
        packet[2] = commandId
        payload.copyInto(packet, 3)
        packet[totalLength - 1] = checksum(packet.copyOfRange(0, totalLength - 1))
        return packet
    }

    /**
     * Encodes a ReadWriteData command using section-based bitmask format.
     *
     * Payload = [writePayload] + [readPayload]
     * Each payload = [numSections, sectionBitmask0, ..sectionBitmaskN, fieldData...]
     *
     * Section-based bitmask:
     *   - byte 0: number of sections (each section covers 8 field indices)
     *   - bytes 1..N: one bitmask byte per section, bit = fieldIndex % 8
     *   - remaining bytes: field data values in field index order
     *
     * Source: GlassOS ReadWriteDataCommand.buildBitFieldPayload
     */
    private fun encodeReadWriteData(message: V1Message.Outgoing.ReadWriteData): List<ByteArray> {
        val writePayload = buildBitFieldPayload(message.writeFields, includeData = true)
        val readPayload = buildBitFieldPayload(message.readFields, includeData = false)
        val payload = writePayload + readPayload

        if (payload.size + HEADER_SIZE <= 64) {
            return listOf(encodeSimple(message.deviceId, CMD_READ_WRITE_DATA, payload))
        }

        return encodeMultiPacket(byteArrayOf(CMD_READ_WRITE_DATA) + payload)
    }

    private fun buildBitFieldPayload(
        fields: Map<V1DataField, Float>,
        includeData: Boolean,
    ): ByteArray {
        if (fields.isEmpty()) return byteArrayOf(0)

        val sortedFields = fields.keys.sortedBy { it.fieldIndex }
        val maxFieldIndex = sortedFields.last().fieldIndex
        val numSections = (maxFieldIndex / 8) + 1

        // Build section bitmasks
        val sectionBitmasks = ByteArray(numSections)
        for (field in sortedFields) {
            val section = field.fieldIndex / 8
            val bit = field.fieldIndex % 8
            sectionBitmasks[section] = (sectionBitmasks[section].toInt() or (1 shl bit)).toByte()
        }

        // Calculate data size
        val dataSize = if (includeData) sortedFields.sumOf { it.sizeBytes } else 0

        val result = ByteArray(1 + numSections + dataSize)
        result[0] = numSections.toByte()
        sectionBitmasks.copyInto(result, 1)

        if (includeData) {
            var offset = 1 + numSections
            for (field in sortedFields) {
                val bytes = convertToBytes(field, fields[field]!!)
                bytes.copyInto(result, offset)
                offset += bytes.size
            }
        }

        return result
    }

    private fun buildBitFieldPayload(
        fields: Set<V1DataField>,
        includeData: Boolean,
    ): ByteArray {
        if (fields.isEmpty()) return byteArrayOf(0)
        return buildBitFieldPayload(fields.associateWith { 0f }, includeData = false)
    }

    private fun clampToShort(value: Int): Short =
        value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    private fun convertToBytes(field: V1DataField, value: Float): ByteArray = when (field.converter) {
        V1Converter.SPEED -> {
            // KPH * 100, stored as unsigned 16-bit LE
            val raw = clampToShort((value * 100).toInt())
            byteArrayOf(raw.toByte(), (raw.toInt() shr 8).toByte())
        }
        V1Converter.GRADE -> {
            // Grade * 100, stored as signed 16-bit LE
            val raw = clampToShort((value * 100).toInt())
            byteArrayOf(raw.toByte(), (raw.toInt() shr 8).toByte())
        }
        V1Converter.RESISTANCE -> {
            // Raw resistance level as 16-bit LE
            val raw = clampToShort(value.toInt())
            byteArrayOf(raw.toByte(), (raw.toInt() shr 8).toByte())
        }
        V1Converter.SHORT -> {
            val raw = clampToShort(value.toInt())
            byteArrayOf(raw.toByte(), (raw.toInt() shr 8).toByte())
        }
        V1Converter.BYTE -> {
            byteArrayOf(value.toInt().toByte())
        }
        V1Converter.INT -> {
            val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(value.toInt())
            buf.array()
        }
        V1Converter.CALORIES -> {
            // GlassOS CaloriesConverter: encoded = (calories * 100_000_000) / 1024
            val encoded = (value.toDouble() * 100_000_000 / 1024).toInt()
            val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(encoded)
            buf.array()
        }
        V1Converter.PULSE -> {
            // Pulse: [userPulse, avgPulse, ?, sourceType]
            byteArrayOf(value.toInt().toByte(), 0, 0, 0)
        }
    }

    private fun convertFromBytes(field: V1DataField, data: ByteArray): Float = when (field.converter) {
        V1Converter.SPEED -> {
            // Unsigned 16-bit LE / 100
            val raw = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
            raw.toFloat() / 100f
        }
        V1Converter.GRADE -> {
            // Signed 16-bit LE / 100
            val raw = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
            raw.toShort().toFloat() / 100f
        }
        V1Converter.RESISTANCE -> {
            // Raw 16-bit LE value
            val raw = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
            raw.toFloat()
        }
        V1Converter.SHORT -> {
            val raw = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
            raw.toFloat()
        }
        V1Converter.BYTE -> {
            (data[0].toInt() and 0xFF).toFloat()
        }
        V1Converter.INT -> {
            ByteBuffer.wrap(data, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toFloat()
        }
        V1Converter.CALORIES -> {
            // GlassOS CaloriesConverter: decoded = (raw * 1024) / 100_000_000
            val raw = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            (raw.toLong() * 1024 / 100_000_000).toFloat()
        }
        V1Converter.PULSE -> {
            // userPulse is byte 0
            (data[0].toInt() and 0xFF).toFloat()
        }
    }

    private fun encodeMultiPacket(data: ByteArray): List<ByteArray> {
        val chunks = data.toList().chunked(MAX_CHUNK_DATA)
        val totalPackets = chunks.size
        val packets = mutableListOf<ByteArray>()

        packets.add(byteArrayOf(MULTI_PACKET_HEADER, MULTI_PACKET_MARKER, data.size.toByte(), totalPackets.toByte()))

        chunks.forEachIndexed { index, chunk ->
            val chunkIndex = if (index == chunks.lastIndex) LAST_CHUNK_INDEX else index.toByte()
            val chunkData = byteArrayOf(chunkIndex, chunk.size.toByte()) + chunk.toByteArray()
            packets.add(chunkData)
        }

        return packets
    }

    private fun reassemble(packets: List<ByteArray>): ByteArray? {
        if (packets.size == 1) return packets[0]

        val header = packets[0]
        if (!isMultiPacketHeader(header)) return packets[0]

        val dataPackets = packets.drop(1)
        val reassembled = mutableListOf<Byte>()
        for (chunk in dataPackets) {
            if (chunk.size < 2) continue
            val chunkSize = chunk[1].toInt() and 0xFF
            val chunkData = chunk.copyOfRange(2, minOf(2 + chunkSize, chunk.size))
            reassembled.addAll(chunkData.toList())
        }
        return reassembled.toByteArray()
    }

    private fun decodeDeviceInfoResponse(deviceId: Int, data: ByteArray): V1Message.Incoming.DeviceInfoResponse {
        val softwareVersion = if (data.size > 4) data[4].toInt() and 0xFF else 0
        val hardwareVersion = if (data.size > 5) data[5].toInt() and 0xFF else 0
        val serialNumber = if (data.size > 9) {
            (data[6].toInt() and 0xFF) or
                ((data[7].toInt() and 0xFF) shl 8) or
                ((data[8].toInt() and 0xFF) shl 16) or
                ((data[9].toInt() and 0xFF) shl 24)
        } else 0
        return V1Message.Incoming.DeviceInfoResponse(deviceId, softwareVersion, hardwareVersion, serialNumber, data.copyOf())
    }

    private fun decodeSystemInfoResponse(data: ByteArray): V1Message.Incoming.SystemInfoResponse {
        // bArr[7..10] = model (32-bit LE), bArr[11..14] = partNumber (32-bit LE)
        val model = if (data.size > 10) {
            (data[7].toInt() and 0xFF) or
                ((data[8].toInt() and 0xFF) shl 8) or
                ((data[9].toInt() and 0xFF) shl 16) or
                ((data[10].toInt() and 0xFF) shl 24)
        } else 0
        val partNumber = if (data.size > 14) {
            (data[11].toInt() and 0xFF) or
                ((data[12].toInt() and 0xFF) shl 8) or
                ((data[13].toInt() and 0xFF) shl 16) or
                ((data[14].toInt() and 0xFF) shl 24)
        } else 0
        return V1Message.Incoming.SystemInfoResponse(partNumber, model)
    }

    private fun decodeVersionInfoResponse(data: ByteArray): V1Message.Incoming.VersionInfoResponse {
        // bArr[4] = masterLibraryVersion, bArr[5..6] = masterLibraryBuild (16-bit LE)
        val masterLibraryVersion = if (data.size > 4) data[4].toInt() and 0xFF else 0
        val masterLibraryBuild = if (data.size > 6) {
            (data[5].toInt() and 0xFF) or ((data[6].toInt() and 0xFF) shl 8)
        } else 0
        return V1Message.Incoming.VersionInfoResponse(masterLibraryVersion, masterLibraryBuild)
    }

    /**
     * Decodes a ReadWriteData response.
     *
     * The response payload (after the 4-byte header) contains field data
     * in the same order as the read fields were requested. The codec reads
     * the fields sequentially using each field's converter byte size.
     *
     * For simplicity, we decode by trying all periodic read fields in order,
     * reading converter.sizeBytes at each offset until we run out of data.
     */
    fun decodeDataResponse(status: Int, payload: ByteArray): V1Message.Incoming {
        if (status != V1Message.STATUS_DONE) {
            return V1Message.Incoming.DataResponse(status, emptyMap())
        }

        val fields = mutableMapOf<V1DataField, Float>()
        var offset = 0
        val readFields = V1DataField.periodicReadFields.sortedBy { it.fieldIndex }
        val expectedSize = readFields.sumOf { it.sizeBytes }

        // If payload doesn't match expected total size, only decode if it's
        // a complete set — partial payloads could mean misaligned fields
        if (payload.size != expectedSize) {
            return V1Message.Incoming.DataResponse(status, emptyMap())
        }

        for (field in readFields) {
            val fieldData = payload.copyOfRange(offset, offset + field.sizeBytes)
            fields[field] = convertFromBytes(field, fieldData)
            offset += field.sizeBytes
        }

        return V1Message.Incoming.DataResponse(status, fields)
    }
}
