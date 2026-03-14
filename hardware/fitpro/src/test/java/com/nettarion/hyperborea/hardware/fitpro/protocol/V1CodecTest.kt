package com.nettarion.hyperborea.hardware.fitpro.protocol

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.hardware.fitpro.v1.V1Codec
import com.nettarion.hyperborea.hardware.fitpro.v1.V1DataField
import com.nettarion.hyperborea.hardware.fitpro.v1.V1Message
import org.junit.Test

class V1CodecTest {

    @Test
    fun `encode SupportedDevices produces correct packet`() {
        val packets = V1Codec.encode(V1Message.Outgoing.SupportedDevices())
        assertThat(packets).hasSize(1)
        assertThat(packets[0][0]).isEqualTo(0x02) // DEVICE_MAIN
        assertThat(packets[0][2]).isEqualTo(0x80.toByte()) // CMD_SUPPORTED_DEVICES
        assertThat(V1Codec.verifyChecksum(packets[0])).isTrue()
    }

    @Test
    fun `decode SupportedDevicesResponse parses device IDs`() {
        // device=2, len=TBD, cmd=0x80, status=0x02, count=3, ids=[2, 7, 0x42]
        val data = byteArrayOf(
            0x02, 0x09, 0x80.toByte(), 0x02,
            0x03, // count
            0x02, 0x07, 0x42, // device IDs
        )
        val packet = data + V1Codec.checksum(data)
        val decoded = V1Codec.decodeSingle(packet)
        assertThat(decoded).isInstanceOf(V1Message.Incoming.SupportedDevicesResponse::class.java)
        val response = decoded as V1Message.Incoming.SupportedDevicesResponse
        assertThat(response.deviceIds).containsExactly(2, 7, 0x42).inOrder()
    }

    @Test
    fun `decode SupportedDevicesResponse with zero count`() {
        val data = byteArrayOf(
            0x02, 0x06, 0x80.toByte(), 0x02,
            0x00, // count=0
        )
        val packet = data + V1Codec.checksum(data)
        val decoded = V1Codec.decodeSingle(packet) as V1Message.Incoming.SupportedDevicesResponse
        assertThat(decoded.deviceIds).isEmpty()
    }

    @Test
    fun `encode Connect produces packet with correct command ID`() {
        val packets = V1Codec.encode(V1Message.Outgoing.Connect())
        assertThat(packets).hasSize(1)
        val packet = packets[0]
        assertThat(packet[0]).isEqualTo(0x07) // FITNESS_BIKE device ID
        assertThat(packet[2]).isEqualTo(0x04) // CMD_CONNECT
    }

    @Test
    fun `encode Connect packet has valid checksum`() {
        val packets = V1Codec.encode(V1Message.Outgoing.Connect())
        assertThat(V1Codec.verifyChecksum(packets[0])).isTrue()
    }

    @Test
    fun `encode Disconnect produces packet with correct command ID`() {
        val packets = V1Codec.encode(V1Message.Outgoing.Disconnect())
        assertThat(packets).hasSize(1)
        assertThat(packets[0][2]).isEqualTo(0x05) // CMD_DISCONNECT
    }

    @Test
    fun `encode DeviceInfo produces correct packet`() {
        val packets = V1Codec.encode(V1Message.Outgoing.DeviceInfo())
        assertThat(packets).hasSize(1)
        assertThat(packets[0][0]).isEqualTo(0x02) // DEVICE_MAIN
        assertThat(packets[0][2]).isEqualTo(0x81.toByte()) // CMD_DEVICE_INFO
        assertThat(V1Codec.verifyChecksum(packets[0])).isTrue()
    }

    @Test
    fun `checksum is unsigned byte sum`() {
        val data = byteArrayOf(0x07, 0x04, 0x04) // device=7, len=4, cmd=4
        val checksum = V1Codec.checksum(data)
        assertThat(checksum).isEqualTo(0x0F.toByte()) // 7 + 4 + 4 = 15
    }

    @Test
    fun `verifyChecksum returns true for valid packet`() {
        val data = byteArrayOf(0x07, 0x04, 0x04)
        val packet = data + V1Codec.checksum(data)
        assertThat(V1Codec.verifyChecksum(packet)).isTrue()
    }

    @Test
    fun `verifyChecksum returns false for corrupted packet`() {
        val packet = byteArrayOf(0x07, 0x04, 0x04, 0x08) // wrong checksum
        assertThat(V1Codec.verifyChecksum(packet)).isFalse()
    }

    @Test
    fun `decode ConnectAck`() {
        val data = byteArrayOf(0x07, 0x04, 0x04)
        val packet = data + V1Codec.checksum(data)
        val decoded = V1Codec.decodeSingle(packet)
        assertThat(decoded).isInstanceOf(V1Message.Incoming.ConnectAck::class.java)
        assertThat((decoded as V1Message.Incoming.ConnectAck).deviceId).isEqualTo(7)
    }

    @Test
    fun `decode DisconnectAck`() {
        val data = byteArrayOf(0x07, 0x04, 0x05)
        val checksum = V1Codec.checksum(data)
        val packet = data + checksum
        val decoded = V1Codec.decodeSingle(packet)
        assertThat(decoded).isInstanceOf(V1Message.Incoming.DisconnectAck::class.java)
    }

    @Test
    fun `decode DataResponse with status DONE`() {
        // Build response: device=7, len=TBD, cmd=0x02, status=0x02 (DONE)
        // Then payload: WATTS value (2 bytes, 200 = 0xC8, 0x00)
        val wattsBytes = byteArrayOf(0xC8.toByte(), 0x00)
        // For the decodeDataResponse, payload starts after byte 3 (header=4 bytes)
        // The full packet is: [device, len, cmd, status, ...fieldData, checksum]
        val fieldData = wattsBytes // Just WATTS (2 bytes)
        val totalLen = 4 + fieldData.size + 1 // header + data + checksum
        val packetWithoutChecksum = byteArrayOf(
            0x07, totalLen.toByte(), 0x02, 0x02, // device, len, cmd, status=DONE
        ) + fieldData
        val packet = packetWithoutChecksum + V1Codec.checksum(packetWithoutChecksum)

        val decoded = V1Codec.decodeSingle(packet)
        assertThat(decoded).isInstanceOf(V1Message.Incoming.DataResponse::class.java)
        val response = decoded as V1Message.Incoming.DataResponse
        assertThat(response.status).isEqualTo(V1Message.STATUS_DONE)
    }

    @Test
    fun `decode DataResponse with status SECURITY_BLOCK has empty fields`() {
        val packetWithoutChecksum = byteArrayOf(0x07, 0x05, 0x02, 0x08) // status=8
        val packet = packetWithoutChecksum + V1Codec.checksum(packetWithoutChecksum)
        val decoded = V1Codec.decodeSingle(packet)
        assertThat(decoded).isInstanceOf(V1Message.Incoming.DataResponse::class.java)
        val response = decoded as V1Message.Incoming.DataResponse
        assertThat(response.status).isEqualTo(V1Message.STATUS_SECURITY_BLOCK)
        assertThat(response.fields).isEmpty()
    }

    @Test
    fun `encode ReadWriteData without write fields produces single packet`() {
        val packets = V1Codec.encode(V1Message.Outgoing.ReadWriteData())
        assertThat(packets).hasSize(1)
        assertThat(packets[0][2]).isEqualTo(0x02) // CMD_READ_WRITE_DATA
        assertThat(V1Codec.verifyChecksum(packets[0])).isTrue()
    }

    @Test
    fun `isMultiPacketHeader detects multi-packet header`() {
        val header = byteArrayOf(0xFE.toByte(), 0x02, 0x20, 0x03)
        assertThat(V1Codec.isMultiPacketHeader(header)).isTrue()
    }

    @Test
    fun `isMultiPacketHeader returns false for normal packet`() {
        val packet = byteArrayOf(0x07, 0x04, 0x04, 0x0F)
        assertThat(V1Codec.isMultiPacketHeader(packet)).isFalse()
    }

    @Test
    fun `V1DataField fromFieldIndex round-trip`() {
        for (field in V1DataField.entries) {
            assertThat(V1DataField.fromFieldIndex(field.fieldIndex)).isEqualTo(field)
        }
    }

    @Test
    fun `V1DataField fromFieldIndex returns null for unknown index`() {
        assertThat(V1DataField.fromFieldIndex(99)).isNull()
    }

    @Test
    fun `decode returns null for empty input`() {
        assertThat(V1Codec.decode(emptyList())).isNull()
    }

    @Test
    fun `decode DataResponse with DONE parses field values from payload`() {
        // periodicReadFields sorted by fieldIndex — compute total size dynamically
        val readFields = V1DataField.periodicReadFields.sortedBy { it.fieldIndex }
        val totalDataSize = readFields.sumOf { it.sizeBytes }

        val fieldData = ByteArray(totalDataSize)
        // GRADE at offset 2 (after KPH=2): signed 16-bit LE -350 = grade -3.5%
        val grade = (-350).toShort()
        fieldData[2] = grade.toByte()
        fieldData[3] = (grade.toInt() shr 8).toByte()
        // WATTS at offset 6 (after KPH=2, GRADE=2, RESISTANCE=2): 200 LE
        fieldData[6] = 200.toByte()
        fieldData[7] = 0

        val totalLen = 4 + fieldData.size + 1
        val header = byteArrayOf(0x07, totalLen.toByte(), 0x02, 0x02)
        val withoutChecksum = header + fieldData
        val packet = withoutChecksum + V1Codec.checksum(withoutChecksum)

        val decoded = V1Codec.decodeSingle(packet) as V1Message.Incoming.DataResponse
        assertThat(decoded.status).isEqualTo(V1Message.STATUS_DONE)
        assertThat(decoded.fields[V1DataField.WATTS]).isEqualTo(200f)
        assertThat(decoded.fields[V1DataField.GRADE]).isWithin(0.01f).of(-3.5f)
    }

    @Test
    fun `encode SystemInfo produces correct packet`() {
        val packets = V1Codec.encode(V1Message.Outgoing.SystemInfo())
        assertThat(packets).hasSize(1)
        assertThat(packets[0][0]).isEqualTo(0x02) // DEVICE_MAIN
        assertThat(packets[0][2]).isEqualTo(0x82.toByte()) // CMD_SYSTEM_INFO
        // Payload is [0x00, 0x00, 0x00]
        assertThat(packets[0][3]).isEqualTo(0x00)
        assertThat(V1Codec.verifyChecksum(packets[0])).isTrue()
    }

    @Test
    fun `encode VersionInfo produces correct packet`() {
        val packets = V1Codec.encode(V1Message.Outgoing.VersionInfo())
        assertThat(packets).hasSize(1)
        assertThat(packets[0][0]).isEqualTo(0x02) // DEVICE_MAIN
        assertThat(packets[0][2]).isEqualTo(0x84.toByte()) // CMD_VERSION_INFO
        assertThat(V1Codec.verifyChecksum(packets[0])).isTrue()
    }

    @Test
    fun `encode VerifySecurity produces 36-byte payload`() {
        val hash = ByteArray(32) { it.toByte() }
        val secretKey = 200
        val packets = V1Codec.encode(V1Message.Outgoing.VerifySecurity(hash = hash, secretKey = secretKey))
        assertThat(packets).hasSize(1)
        val packet = packets[0]
        assertThat(packet[2]).isEqualTo(0x90.toByte()) // CMD_VERIFY_SECURITY
        // Payload starts at byte 3, hash bytes 0..31 then secretKey LE
        assertThat(packet[3]).isEqualTo(0x00) // hash[0]
        assertThat(packet[34]).isEqualTo(0x1F) // hash[31]
        // secretKey 200 = 0xC8 LE
        assertThat(packet[35].toInt() and 0xFF).isEqualTo(0xC8)
        assertThat(packet[36].toInt() and 0xFF).isEqualTo(0x00)
        assertThat(V1Codec.verifyChecksum(packet)).isTrue()
    }

    @Test
    fun `decode DeviceInfoResponse parses fields`() {
        // Build: device=2, len=TBD, cmd=0x81, status=0x02,
        //   sw=80, hw=3, serial=0x01020304, rest doesn't matter
        val data = byteArrayOf(
            0x02, 0x10, 0x81.toByte(), 0x02,
            80, // sw version
            3,  // hw version
            0x04, 0x03, 0x02, 0x01, // serial LE = 0x01020304
            0, 0, // manufacturer
            1,    // sections
            0,    // bitmask
        )
        val packet = data + V1Codec.checksum(data)
        val decoded = V1Codec.decodeSingle(packet)
        assertThat(decoded).isInstanceOf(V1Message.Incoming.DeviceInfoResponse::class.java)
        val info = decoded as V1Message.Incoming.DeviceInfoResponse
        assertThat(info.softwareVersion).isEqualTo(80)
        assertThat(info.hardwareVersion).isEqualTo(3)
        assertThat(info.serialNumber).isEqualTo(0x01020304)
    }

    @Test
    fun `decode SystemInfoResponse parses model and partNumber`() {
        // Build: device=2, len=TBD, cmd=0x82, status=0x02,
        //   configSize(2), configuration(1), model(4), partNumber(4)
        val data = byteArrayOf(
            0x02, 0x14, 0x82.toByte(), 0x02,
            0x00, 0x00, // configSize
            0x01,       // configuration
            0x78, 0x56, 0x34, 0x12, // model LE = 0x12345678
            0xEF.toByte(), 0xCD.toByte(), 0xAB.toByte(), 0x00, // partNumber LE = 0x00ABCDEF
            0, 0, 0, 0, // padding
        )
        val packet = data + V1Codec.checksum(data)
        val decoded = V1Codec.decodeSingle(packet)
        assertThat(decoded).isInstanceOf(V1Message.Incoming.SystemInfoResponse::class.java)
        val info = decoded as V1Message.Incoming.SystemInfoResponse
        assertThat(info.model).isEqualTo(0x12345678)
        assertThat(info.partNumber).isEqualTo(0x00ABCDEF)
    }

    @Test
    fun `decode VersionInfoResponse parses masterLibraryVersion`() {
        // Build: device=2, len=TBD, cmd=0x84, status=0x02,
        //   masterLibraryVersion=96, masterLibraryBuild=0x0102
        val data = byteArrayOf(
            0x02, 0x08, 0x84.toByte(), 0x02,
            96,         // masterLibraryVersion
            0x02, 0x01, // masterLibraryBuild LE = 258
        )
        val packet = data + V1Codec.checksum(data)
        val decoded = V1Codec.decodeSingle(packet)
        assertThat(decoded).isInstanceOf(V1Message.Incoming.VersionInfoResponse::class.java)
        val info = decoded as V1Message.Incoming.VersionInfoResponse
        assertThat(info.masterLibraryVersion).isEqualTo(96)
        assertThat(info.masterLibraryBuild).isEqualTo(258)
    }

    @Test
    fun `decode SecurityResponse unlocked`() {
        val data = byteArrayOf(
            0x02, 0x06, 0x90.toByte(), 0x02, // status=DONE
            0x05, // unlockedKey
        )
        val packet = data + V1Codec.checksum(data)
        val decoded = V1Codec.decodeSingle(packet)
        assertThat(decoded).isInstanceOf(V1Message.Incoming.SecurityResponse::class.java)
        val sec = decoded as V1Message.Incoming.SecurityResponse
        assertThat(sec.isUnlocked).isTrue()
        assertThat(sec.unlockedKey).isEqualTo(5)
    }

    @Test
    fun `decode SecurityResponse blocked`() {
        val data = byteArrayOf(
            0x02, 0x06, 0x90.toByte(), 0x08, // status=SECURITY_BLOCK
            0x00,
        )
        val packet = data + V1Codec.checksum(data)
        val decoded = V1Codec.decodeSingle(packet)
        assertThat(decoded).isInstanceOf(V1Message.Incoming.SecurityResponse::class.java)
        val sec = decoded as V1Message.Incoming.SecurityResponse
        assertThat(sec.isUnlocked).isFalse()
    }

    @Test
    fun `encode ReadWriteData clamps extreme speed value to Short range`() {
        // 400 kph * 100 = 40000, which exceeds Short.MAX_VALUE (32767)
        // Should clamp to 32767 instead of wrapping
        val msg = V1Message.Outgoing.ReadWriteData(
            writeFields = mapOf(V1DataField.KPH to 400f),
        )
        val packets = V1Codec.encode(msg)
        assertThat(packets).isNotEmpty()
        // Decode the speed bytes from the packet — find write payload
        // The packet is: [device, len, cmd, numSections, sectionBitmask, speedLo, speedHi, ..., checksum]
        val packet = packets[0]
        // KPH is fieldIndex=0, section=0, bit=0 → sectionBitmask has bit 0 set
        // Layout: [0x02, len, 0x02, 1(numSections), 0x01(bitmask), speedLo, speedHi, 0(read numSections), checksum]
        val speedLo = packet[5].toInt() and 0xFF
        val speedHi = packet[6].toInt() and 0xFF
        val rawSpeed = (speedLo or (speedHi shl 8)).toShort()
        assertThat(rawSpeed).isEqualTo(Short.MAX_VALUE)
    }

    @Test
    fun `decodeDataResponse leniently decodes partial payload`() {
        // Payload shorter than expected → lenient decoder decodes complete fields that fit
        val shortPayload = ByteArray(10) // enough for KPH(2)+GRADE(2)+RESISTANCE(2)+WATTS(2)=8, not CURRENT_DISTANCE(4)
        val totalLen = 4 + shortPayload.size + 1
        val header = byteArrayOf(0x07, totalLen.toByte(), 0x02, 0x02)
        val withoutChecksum = header + shortPayload
        val packet = withoutChecksum + V1Codec.checksum(withoutChecksum)

        val decoded = V1Codec.decodeSingle(packet) as V1Message.Incoming.DataResponse
        assertThat(decoded.status).isEqualTo(V1Message.STATUS_DONE)
        assertThat(decoded.fields).hasSize(4)
        assertThat(decoded.fields).containsKey(V1DataField.KPH)
        assertThat(decoded.fields).containsKey(V1DataField.WATTS)
    }

    @Test
    fun `calories converter decodes encoded value correctly`() {
        // 100 calories encoded: (100 * 100_000_000) / 1024 = 9765625
        val encoded = 9765625

        // Compute payload size and CURRENT_CALORIES offset from periodicReadFields
        val readFields = V1DataField.periodicReadFields.sortedBy { it.fieldIndex }
        val totalDataSize = readFields.sumOf { it.sizeBytes }
        val caloriesOffset = readFields
            .takeWhile { it != V1DataField.CURRENT_CALORIES }
            .sumOf { it.sizeBytes }

        val fieldData = ByteArray(totalDataSize)
        fieldData[caloriesOffset] = (encoded and 0xFF).toByte()
        fieldData[caloriesOffset + 1] = ((encoded shr 8) and 0xFF).toByte()
        fieldData[caloriesOffset + 2] = ((encoded shr 16) and 0xFF).toByte()
        fieldData[caloriesOffset + 3] = ((encoded shr 24) and 0xFF).toByte()

        val totalLen = 4 + fieldData.size + 1
        val header = byteArrayOf(0x07, totalLen.toByte(), 0x02, 0x02)
        val withoutChecksum = header + fieldData
        val packet = withoutChecksum + V1Codec.checksum(withoutChecksum)

        val decoded = V1Codec.decodeSingle(packet) as V1Message.Incoming.DataResponse
        assertThat(decoded.fields[V1DataField.CURRENT_CALORIES]).isEqualTo(100f)
    }
}
