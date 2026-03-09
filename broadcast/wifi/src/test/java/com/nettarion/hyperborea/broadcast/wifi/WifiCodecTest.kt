package com.nettarion.hyperborea.broadcast.wifi

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import org.junit.Test

class WifiCodecTest {

    // --- UUID encoding ---

    @Test
    fun `encodeUuidBlob produces 16-byte BLE base UUID with short UUID at bytes 2-3`() {
        val blob = WifiCodec.encodeUuidBlob(ShortUuid(0x1826))
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
        val blob = WifiCodec.encodeUuidBlob(ShortUuid(0x2AD2))
        val decoded = WifiCodec.decodeShortUuid(blob, 0)
        assertThat(decoded).isEqualTo(ShortUuid(0x2AD2))
    }

    @Test
    fun `roundtrip UUID encoding`() {
        val uuids = listOf(0x1826, 0x1818, 0x2ACC, 0x2AD9, 0xE005, 0x2A63)
        for (value in uuids) {
            val uuid = ShortUuid(value)
            val blob = WifiCodec.encodeUuidBlob(uuid)
            val decoded = WifiCodec.decodeShortUuid(blob, 0)
            assertThat(decoded).isEqualTo(uuid)
        }
    }

    // --- Response encoding ---

    @Test
    fun `encodeResponse produces correct header`() {
        val resp = WifiCodec.encodeResponse(0x01, 0x05, WifiCodec.RESP_SUCCESS, byteArrayOf(0x10, 0x20))
        assertThat(resp.size).isEqualTo(8) // 6 header + 2 payload
        assertThat(resp[0]).isEqualTo(WifiCodec.VERSION)
        assertThat(resp[1]).isEqualTo(0x01.toByte())
        assertThat(resp[2]).isEqualTo(0x05.toByte())
        assertThat(resp[3]).isEqualTo(WifiCodec.RESP_SUCCESS)
        // Length = 2 big-endian
        assertThat(resp[4]).isEqualTo(0x00.toByte())
        assertThat(resp[5]).isEqualTo(0x02.toByte())
        assertThat(resp[6]).isEqualTo(0x10.toByte())
        assertThat(resp[7]).isEqualTo(0x20.toByte())
    }

    @Test
    fun `encodeResponse with empty payload has zero length`() {
        val resp = WifiCodec.encodeResponse(0x01, 0x00, WifiCodec.RESP_SUCCESS)
        assertThat(resp.size).isEqualTo(6)
        assertThat(resp[4]).isEqualTo(0x00.toByte())
        assertThat(resp[5]).isEqualTo(0x00.toByte())
    }

    // --- Notification encoding ---

    @Test
    fun `encodeNotification uses type 0x06 and sequence 0x00`() {
        val notif = WifiCodec.encodeNotification(ShortUuid(0x2AD2), byteArrayOf(0x01))
        assertThat(notif[0]).isEqualTo(WifiCodec.VERSION)
        assertThat(notif[1]).isEqualTo(WifiCodec.ID_NOTIFICATION)
        assertThat(notif[2]).isEqualTo(0x00.toByte()) // sequence
        assertThat(notif[3]).isEqualTo(WifiCodec.RESP_SUCCESS)
    }

    // --- Request reading ---

    @Test
    fun `readRequest parses DiscoverServices`() {
        val packet = WifiCodec.encodeResponse(WifiCodec.ID_DISCOVER_SERVICES, 0x01, 0x00)
        val request = WifiCodec.readRequest(ByteArrayInputStream(packet))
        assertThat(request).isInstanceOf(WifiMessage.DiscoverServices::class.java)
        assertThat((request as WifiMessage.DiscoverServices).sequence).isEqualTo(0x01.toByte())
    }

    @Test
    fun `readRequest parses DiscoverCharacteristics`() {
        val uuidBlob = WifiCodec.encodeUuidBlob(ShortUuid(0x1826))
        val packet = WifiCodec.encodeResponse(WifiCodec.ID_DISCOVER_CHARACTERISTICS, 0x02, 0x00, uuidBlob)
        val request = WifiCodec.readRequest(ByteArrayInputStream(packet))
        assertThat(request).isInstanceOf(WifiMessage.DiscoverCharacteristics::class.java)
        val msg = request as WifiMessage.DiscoverCharacteristics
        assertThat(msg.sequence).isEqualTo(0x02.toByte())
        assertThat(msg.serviceUuid).isEqualTo(ShortUuid(0x1826))
    }

    @Test
    fun `readRequest parses WriteCharacteristic`() {
        val uuidBlob = WifiCodec.encodeUuidBlob(ShortUuid(0x2AD9))
        val payload = uuidBlob + byteArrayOf(0x04, 0x64, 0x00)
        val packet = WifiCodec.encodeResponse(WifiCodec.ID_WRITE_CHARACTERISTIC, 0x03, 0x00, payload)
        val request = WifiCodec.readRequest(ByteArrayInputStream(packet))
        assertThat(request).isInstanceOf(WifiMessage.WriteCharacteristic::class.java)
        val msg = request as WifiMessage.WriteCharacteristic
        assertThat(msg.charUuid).isEqualTo(ShortUuid(0x2AD9))
        assertThat(msg.value).isEqualTo(byteArrayOf(0x04, 0x64, 0x00))
    }

    @Test
    fun `readRequest parses EnableNotifications`() {
        val uuidBlob = WifiCodec.encodeUuidBlob(ShortUuid(0x2AD2))
        val payload = uuidBlob + byteArrayOf(0x01)
        val packet = WifiCodec.encodeResponse(WifiCodec.ID_ENABLE_NOTIFICATIONS, 0x04, 0x00, payload)
        val request = WifiCodec.readRequest(ByteArrayInputStream(packet))
        assertThat(request).isInstanceOf(WifiMessage.EnableNotifications::class.java)
        val msg = request as WifiMessage.EnableNotifications
        assertThat(msg.charUuid).isEqualTo(ShortUuid(0x2AD2))
        assertThat(msg.enable).isTrue()
    }

    @Test
    fun `readRequest returns null for wrong version`() {
        val packet = byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00, 0x00)
        val request = WifiCodec.readRequest(ByteArrayInputStream(packet))
        assertThat(request).isNull()
    }

    @Test
    fun `readRequest returns null for empty stream`() {
        val request = WifiCodec.readRequest(ByteArrayInputStream(ByteArray(0)))
        assertThat(request).isNull()
    }

    @Test
    fun `readRequest parses UnknownCompat`() {
        val packet = WifiCodec.encodeResponse(WifiCodec.ID_UNKNOWN_COMPAT, 0x05, 0x00)
        val request = WifiCodec.readRequest(ByteArrayInputStream(packet))
        assertThat(request).isInstanceOf(WifiMessage.UnknownCompat::class.java)
    }
}
