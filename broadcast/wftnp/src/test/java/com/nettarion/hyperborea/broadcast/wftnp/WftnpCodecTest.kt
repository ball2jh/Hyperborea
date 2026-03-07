package com.nettarion.hyperborea.broadcast.wftnp

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import org.junit.Test

class WftnpCodecTest {

    // --- UUID encoding ---

    @Test
    fun `encodeUuidBlob produces 16-byte BLE base UUID with short UUID at bytes 2-3`() {
        val blob = WftnpCodec.encodeUuidBlob(ShortUuid(0x1826))
        assertThat(blob.size).isEqualTo(16)
        assertThat(blob[2].toInt() and 0xFF).isEqualTo(0x18)
        assertThat(blob[3].toInt() and 0xFF).isEqualTo(0x26)
        // BLE base UUID suffix
        assertThat(blob[4]).isEqualTo(0x00)
        assertThat(blob[5]).isEqualTo(0x00)
        assertThat(blob[6]).isEqualTo(0x10)
        assertThat(blob[7]).isEqualTo(0x00)
    }

    @Test
    fun `decodeShortUuid extracts big-endian short UUID from bytes 2-3`() {
        val blob = WftnpCodec.encodeUuidBlob(ShortUuid(0x2AD2))
        val decoded = WftnpCodec.decodeShortUuid(blob, 0)
        assertThat(decoded).isEqualTo(ShortUuid(0x2AD2))
    }

    @Test
    fun `roundtrip UUID encoding`() {
        val uuids = listOf(0x1826, 0x1818, 0x2ACC, 0x2AD9, 0xE005, 0x2A63)
        for (value in uuids) {
            val uuid = ShortUuid(value)
            val blob = WftnpCodec.encodeUuidBlob(uuid)
            val decoded = WftnpCodec.decodeShortUuid(blob, 0)
            assertThat(decoded).isEqualTo(uuid)
        }
    }

    // --- Response encoding ---

    @Test
    fun `encodeResponse produces correct header`() {
        val resp = WftnpCodec.encodeResponse(0x01, 0x05, WftnpCodec.RESP_SUCCESS, byteArrayOf(0x10, 0x20))
        assertThat(resp.size).isEqualTo(8) // 6 header + 2 payload
        assertThat(resp[0]).isEqualTo(WftnpCodec.VERSION)
        assertThat(resp[1]).isEqualTo(0x01.toByte())
        assertThat(resp[2]).isEqualTo(0x05.toByte())
        assertThat(resp[3]).isEqualTo(WftnpCodec.RESP_SUCCESS)
        // Length = 2 big-endian
        assertThat(resp[4]).isEqualTo(0x00.toByte())
        assertThat(resp[5]).isEqualTo(0x02.toByte())
        assertThat(resp[6]).isEqualTo(0x10.toByte())
        assertThat(resp[7]).isEqualTo(0x20.toByte())
    }

    @Test
    fun `encodeResponse with empty payload has zero length`() {
        val resp = WftnpCodec.encodeResponse(0x01, 0x00, WftnpCodec.RESP_SUCCESS)
        assertThat(resp.size).isEqualTo(6)
        assertThat(resp[4]).isEqualTo(0x00.toByte())
        assertThat(resp[5]).isEqualTo(0x00.toByte())
    }

    // --- Notification encoding ---

    @Test
    fun `encodeNotification uses type 0x06 and sequence 0x00`() {
        val notif = WftnpCodec.encodeNotification(ShortUuid(0x2AD2), byteArrayOf(0x01))
        assertThat(notif[0]).isEqualTo(WftnpCodec.VERSION)
        assertThat(notif[1]).isEqualTo(WftnpCodec.ID_NOTIFICATION)
        assertThat(notif[2]).isEqualTo(0x00.toByte()) // sequence
        assertThat(notif[3]).isEqualTo(WftnpCodec.RESP_SUCCESS)
    }

    // --- Request reading ---

    @Test
    fun `readRequest parses DiscoverServices`() {
        val packet = WftnpCodec.encodeResponse(WftnpCodec.ID_DISCOVER_SERVICES, 0x01, 0x00)
        val request = WftnpCodec.readRequest(ByteArrayInputStream(packet))
        assertThat(request).isInstanceOf(WftnpMessage.DiscoverServices::class.java)
        assertThat((request as WftnpMessage.DiscoverServices).sequence).isEqualTo(0x01.toByte())
    }

    @Test
    fun `readRequest parses DiscoverCharacteristics`() {
        val uuidBlob = WftnpCodec.encodeUuidBlob(ShortUuid(0x1826))
        val packet = WftnpCodec.encodeResponse(WftnpCodec.ID_DISCOVER_CHARACTERISTICS, 0x02, 0x00, uuidBlob)
        val request = WftnpCodec.readRequest(ByteArrayInputStream(packet))
        assertThat(request).isInstanceOf(WftnpMessage.DiscoverCharacteristics::class.java)
        val msg = request as WftnpMessage.DiscoverCharacteristics
        assertThat(msg.sequence).isEqualTo(0x02.toByte())
        assertThat(msg.serviceUuid).isEqualTo(ShortUuid(0x1826))
    }

    @Test
    fun `readRequest parses WriteCharacteristic`() {
        val uuidBlob = WftnpCodec.encodeUuidBlob(ShortUuid(0x2AD9))
        val payload = uuidBlob + byteArrayOf(0x04, 0x64, 0x00)
        val packet = WftnpCodec.encodeResponse(WftnpCodec.ID_WRITE_CHARACTERISTIC, 0x03, 0x00, payload)
        val request = WftnpCodec.readRequest(ByteArrayInputStream(packet))
        assertThat(request).isInstanceOf(WftnpMessage.WriteCharacteristic::class.java)
        val msg = request as WftnpMessage.WriteCharacteristic
        assertThat(msg.charUuid).isEqualTo(ShortUuid(0x2AD9))
        assertThat(msg.value).isEqualTo(byteArrayOf(0x04, 0x64, 0x00))
    }

    @Test
    fun `readRequest parses EnableNotifications`() {
        val uuidBlob = WftnpCodec.encodeUuidBlob(ShortUuid(0x2AD2))
        val payload = uuidBlob + byteArrayOf(0x01)
        val packet = WftnpCodec.encodeResponse(WftnpCodec.ID_ENABLE_NOTIFICATIONS, 0x04, 0x00, payload)
        val request = WftnpCodec.readRequest(ByteArrayInputStream(packet))
        assertThat(request).isInstanceOf(WftnpMessage.EnableNotifications::class.java)
        val msg = request as WftnpMessage.EnableNotifications
        assertThat(msg.charUuid).isEqualTo(ShortUuid(0x2AD2))
        assertThat(msg.enable).isTrue()
    }

    @Test
    fun `readRequest returns null for wrong version`() {
        val packet = byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00, 0x00)
        val request = WftnpCodec.readRequest(ByteArrayInputStream(packet))
        assertThat(request).isNull()
    }

    @Test
    fun `readRequest returns null for empty stream`() {
        val request = WftnpCodec.readRequest(ByteArrayInputStream(ByteArray(0)))
        assertThat(request).isNull()
    }

    @Test
    fun `readRequest parses UnknownCompat`() {
        val packet = WftnpCodec.encodeResponse(WftnpCodec.ID_UNKNOWN_COMPAT, 0x05, 0x00)
        val request = WftnpCodec.readRequest(ByteArrayInputStream(packet))
        assertThat(request).isInstanceOf(WftnpMessage.UnknownCompat::class.java)
    }
}
