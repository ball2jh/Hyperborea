package com.nettarion.hyperborea.hardware.fitpro.protocol

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.hardware.fitpro.v2.V2Codec
import com.nettarion.hyperborea.hardware.fitpro.v2.V2FeatureId
import com.nettarion.hyperborea.hardware.fitpro.v2.V2Message
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

class V2CodecTest {

    @Test
    fun `encode QueryFeatures produces correct header`() {
        val encoded = V2Codec.encode(V2Message.Outgoing.QueryFeatures())
        assertThat(encoded[0]).isEqualTo(0x02) // comm type
        assertThat(encoded[2]).isEqualTo(0x00) // empty payload
    }

    @Test
    fun `encode QueryFeatures type nibble is SUPPORTED_FEATURES`() {
        val encoded = V2Codec.encode(V2Message.Outgoing.QueryFeatures())
        val typeNibble = encoded[1].toInt() and 0x0F
        assertThat(typeNibble).isEqualTo(0x06)
    }

    @Test
    fun `encode Subscribe with single feature`() {
        val msg = V2Message.Outgoing.Subscribe(listOf(V2FeatureId.RPM))
        val encoded = V2Codec.encode(msg)

        assertThat(encoded[0]).isEqualTo(0x02) // comm type
        val typeNibble = encoded[1].toInt() and 0x0F
        assertThat(typeNibble).isEqualTo(0x01) // SUBSCRIBE
        assertThat(encoded[2]).isEqualTo(0x02) // payload length = 2 bytes (one feature ID)
        assertThat(encoded[3]).isEqualTo(0x42) // RPM lo byte
        assertThat(encoded[4]).isEqualTo(0x01) // RPM hi byte
    }

    @Test
    fun `encode WriteFeature produces correct payload`() {
        val msg = V2Message.Outgoing.WriteFeature(V2FeatureId.TARGET_RESISTANCE, 15.0f)
        val encoded = V2Codec.encode(msg)

        assertThat(encoded[0]).isEqualTo(0x02)
        val typeNibble = encoded[1].toInt() and 0x0F
        assertThat(typeNibble).isEqualTo(0x02) // WRITE
        assertThat(encoded[2]).isEqualTo(0x06) // 2 bytes feature + 4 bytes float

        // Feature ID bytes
        assertThat(encoded[3]).isEqualTo(V2FeatureId.TARGET_RESISTANCE.wireLo)
        assertThat(encoded[4]).isEqualTo(V2FeatureId.TARGET_RESISTANCE.wireHi)

        // Float value
        val floatBytes = encoded.copyOfRange(5, 9)
        val value = ByteBuffer.wrap(floatBytes).order(ByteOrder.LITTLE_ENDIAN).float
        assertThat(value).isEqualTo(15.0f)
    }

    @Test
    fun `decode Event packet`() {
        val featureId = V2FeatureId.WATTS
        val value = 150.0f
        val payload = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .put(featureId.wireLo).put(featureId.wireHi).putFloat(value).array()

        // Build packet: comm_type, source|EVENT, length, payload
        val packet = byteArrayOf(0x02, 0x25, payload.size.toByte(), *payload)

        val decoded = V2Codec.decode(packet)
        assertThat(decoded).isInstanceOf(V2Message.Incoming.Event::class.java)
        val event = decoded as V2Message.Incoming.Event
        assertThat(event.feature).isEqualTo(V2FeatureId.WATTS)
        assertThat(event.value).isEqualTo(150.0f)
    }

    @Test
    fun `decode SupportedFeatures packet`() {
        val features = listOf(V2FeatureId.RPM, V2FeatureId.WATTS)
        val payload = byteArrayOf(
            V2FeatureId.RPM.wireLo, V2FeatureId.RPM.wireHi,
            V2FeatureId.WATTS.wireLo, V2FeatureId.WATTS.wireHi,
        )

        // CONTROLLER_1(0x20) | FEATURES response (type=0x01)
        val packet = byteArrayOf(0x02, 0x21, payload.size.toByte(), *payload)

        val decoded = V2Codec.decode(packet)
        assertThat(decoded).isInstanceOf(V2Message.Incoming.SupportedFeatures::class.java)
        val msg = decoded as V2Message.Incoming.SupportedFeatures
        assertThat(msg.features).containsExactlyElementsIn(features)
    }

    @Test
    fun `decode Acknowledge packet`() {
        val packet = byteArrayOf(0x02, 0x23, 0x00) // type=ACK
        val decoded = V2Codec.decode(packet)
        assertThat(decoded).isInstanceOf(V2Message.Incoming.Acknowledge::class.java)
    }

    @Test
    fun `decode Error packet`() {
        val packet = byteArrayOf(0x02, 0x24, 0x01, 0x42) // type=ERROR, code=0x42
        val decoded = V2Codec.decode(packet)
        assertThat(decoded).isInstanceOf(V2Message.Incoming.Error::class.java)
        assertThat((decoded as V2Message.Incoming.Error).code).isEqualTo(0x42)
    }

    @Test
    fun `decode returns null for too-short packet`() {
        assertThat(V2Codec.decode(byteArrayOf(0x02, 0x23))).isNull()
    }

    @Test
    fun `decode returns null for wrong comm type`() {
        assertThat(V2Codec.decode(byteArrayOf(0x03, 0x23, 0x00))).isNull()
    }

    @Test
    fun `decode unknown type returns Unknown`() {
        val packet = byteArrayOf(0x02, 0x2F, 0x00) // type=0x0F is not defined
        val decoded = V2Codec.decode(packet)
        assertThat(decoded).isInstanceOf(V2Message.Incoming.Unknown::class.java)
    }

    @Test
    fun `encode Unsubscribe uses correct type nibble`() {
        val msg = V2Message.Outgoing.Unsubscribe(listOf(V2FeatureId.RPM))
        val encoded = V2Codec.encode(msg)
        val typeNibble = encoded[1].toInt() and 0x0F
        assertThat(typeNibble).isEqualTo(0x07)
    }

    @Test
    fun `encode-decode round trip for WriteFeature`() {
        val original = V2Message.Outgoing.WriteFeature(V2FeatureId.TARGET_GRADE, -3.5f)
        val encoded = V2Codec.encode(original)

        // Simulate receiving this as an Event (change type to EVENT)
        val asEvent = encoded.copyOf()
        asEvent[1] = ((asEvent[1].toInt() and 0xF0) or 0x05).toByte() // type=EVENT
        val decoded = V2Codec.decode(asEvent)

        assertThat(decoded).isInstanceOf(V2Message.Incoming.Event::class.java)
        val event = decoded as V2Message.Incoming.Event
        assertThat(event.feature).isEqualTo(V2FeatureId.TARGET_GRADE)
        assertThat(event.value).isEqualTo(-3.5f)
    }
}
