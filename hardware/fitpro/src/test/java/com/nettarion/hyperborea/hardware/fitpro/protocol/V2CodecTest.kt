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
    fun `decode Error packet carries rejected command and reason code`() {
        // type=ERROR, payload = [rejected command, reason]
        val packet = byteArrayOf(0x02, 0x24, 0x02, 0x01, 0x03)
        val decoded = V2Codec.decode(packet)
        assertThat(decoded).isInstanceOf(V2Message.Incoming.Error::class.java)
        val error = decoded as V2Message.Incoming.Error
        assertThat(error.command).isEqualTo(0x01) // Subscribe
        assertThat(error.code).isEqualTo(0x03)
    }

    @Test
    fun `decode Error packet with missing reason byte defaults code to zero`() {
        val packet = byteArrayOf(0x02, 0x24, 0x01, 0x42)
        val decoded = V2Codec.decode(packet) as V2Message.Incoming.Error
        assertThat(decoded.command).isEqualTo(0x42)
        assertThat(decoded.code).isEqualTo(0)
        assertThat(decoded.featureCode).isNull()
        assertThat(decoded.value).isNull()
    }

    @Test
    fun `decode write-rejection Error carries the feature and refused value`() {
        // command=Write(0x02), reason=WRITE_VALUE_NOT_ALLOWED(7),
        // feature=WORKOUT_STATE(602=0x025A), value=2.0f LE
        val packet = byteArrayOf(
            0x02, 0x24, 0x08,
            0x02, 0x07, 0x5A, 0x02, 0x00, 0x00, 0x00, 0x40,
        )
        val decoded = V2Codec.decode(packet) as V2Message.Incoming.Error
        assertThat(decoded.command).isEqualTo(0x02)
        assertThat(decoded.code).isEqualTo(7)
        assertThat(decoded.featureCode).isEqualTo(602)
        assertThat(decoded.value).isEqualTo(2.0f)
        assertThat(decoded.describe()).isEqualTo("command=0x02 reason=WRITE_VALUE_NOT_ALLOWED feature=WORKOUT_STATE value=2.0")
    }

    @Test
    fun `decode SupportedFeatures keeps unknown feature ids as list content`() {
        // Two ids we don't model: 0x03E7 (999) and 0x03E8 (1000).
        val packet = byteArrayOf(0x02, 0x21, 0x04, 0xE7.toByte(), 0x03, 0xE8.toByte(), 0x03)
        val decoded = V2Codec.decode(packet) as V2Message.Incoming.SupportedFeatures
        assertThat(decoded.features).isEmpty()
        assertThat(decoded.unknownCodes).containsExactly(999, 1000)
        assertThat(decoded.isEndOfList).isFalse()
    }

    @Test
    fun `decode empty SupportedFeatures frame is the end-of-list terminator`() {
        val packet = byteArrayOf(0x02, 0x21, 0x00)
        val decoded = V2Codec.decode(packet) as V2Message.Incoming.SupportedFeatures
        assertThat(decoded.isEndOfList).isTrue()
    }

    @Test
    fun `encode QueryProductInfo is an extended command with the product-info class`() {
        val encoded = V2Codec.encode(V2Message.Outgoing.QueryProductInfo())
        assertThat(encoded[0]).isEqualTo(0x02)
        assertThat(encoded[1].toInt() and 0x0F).isEqualTo(0x0E)
        assertThat(encoded[2]).isEqualTo(1) // payload length
        assertThat(encoded[3]).isEqualTo(0x02) // product-info class
    }

    @Test
    fun `decode product-info field carries tag and text`() {
        // type=EXTENDED(0x0E), payload = [class=2, field=5(serial), "AB12"]
        val packet = byteArrayOf(0x02, 0x2E, 0x06, 0x02, 0x05, 0x41, 0x42, 0x31, 0x32)
        val decoded = V2Codec.decode(packet) as V2Message.Incoming.ProductInfoField
        assertThat(decoded.fieldType).isEqualTo(V2Codec.PRODUCT_INFO_SERIAL_NUMBER)
        assertThat(decoded.text).isEqualTo("AB12")
        assertThat(decoded.isEndOfList).isFalse()
    }

    @Test
    fun `decode product-info end-of-list field`() {
        val packet = byteArrayOf(0x02, 0x2E, 0x02, 0x02, 0x00)
        val decoded = V2Codec.decode(packet) as V2Message.Incoming.ProductInfoField
        assertThat(decoded.isEndOfList).isTrue()
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
