package com.nettarion.hyperborea.hardware.fitpro.session

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.DeviceCommand
import com.nettarion.hyperborea.hardware.fitpro.v2.V2Codec
import com.nettarion.hyperborea.hardware.fitpro.v2.V2FeatureId
import com.nettarion.hyperborea.hardware.fitpro.v2.V2Message
import com.nettarion.hyperborea.hardware.fitpro.v2.V2Session
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class V2SessionTest {

    private val transport = FakeHidTransport()
    private val logger = FakeAppLogger()

    private fun createSession(scope: TestScope): V2Session =
        V2Session(
            transport, logger, scope.backgroundScope, EquipmentProfiles.S22I,
        )

    @Test
    fun `start transitions through CONNECTING to STREAMING`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
    }

    @Test
    fun `start opens transport`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        assertThat(transport.isOpen).isTrue()
    }

    @Test
    fun `start sends QueryFeatures and Subscribe packets`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        // Should have written at least: QueryFeatures + Subscribe batches + first heartbeat
        assertThat(transport.writtenPackets.size).isAtLeast(2)

        // First packet should be QueryFeatures (type nibble = 0x06)
        val first = transport.writtenPackets[0]
        assertThat(first[0]).isEqualTo(0x02)
        val typeNibble = first[1].toInt() and 0x0F
        assertThat(typeNibble).isEqualTo(0x06)
    }

    @Test
    fun `incoming Event updates exerciseData`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        // Send a WATTS event
        val wattsEvent = buildEventPacket(V2FeatureId.WATTS, 150.0f)
        transport.emitIncoming(wattsEvent)
        runCurrent()

        assertThat(session.exerciseData.value).isNotNull()
        assertThat(session.exerciseData.value!!.power).isEqualTo(150)
    }

    @Test
    fun `incoming RPM Event updates cadence`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        transport.emitIncoming(buildEventPacket(V2FeatureId.RPM, 85.0f))
        runCurrent()

        assertThat(session.exerciseData.value).isNotNull()
        assertThat(session.exerciseData.value!!.cadence).isEqualTo(85)
    }

    @Test
    fun `heartbeat sends periodically`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        val countBefore = transport.writtenPackets.size
        advanceTimeBy(720 + 50)

        val heartbeats = transport.writtenPackets.drop(countBefore)
        assertThat(heartbeats).isNotEmpty()
    }

    @Test
    fun `writeFeature sends WriteFeature for SetResistance`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        val countBefore = transport.writtenPackets.size
        session.writeFeature(DeviceCommand.SetResistance(10))

        val written = transport.writtenPackets.drop(countBefore)
        assertThat(written).hasSize(1)

        // Verify it's a WRITE with TARGET_RESISTANCE
        val packet = written[0]
        val typeNibble = packet[1].toInt() and 0x0F
        assertThat(typeNibble).isEqualTo(0x02) // WRITE
        assertThat(packet[3]).isEqualTo(V2FeatureId.TARGET_RESISTANCE.wireLo)
    }

    @Test
    fun `writeFeature sends WriteFeature for SetIncline`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        val countBefore = transport.writtenPackets.size
        session.writeFeature(DeviceCommand.SetIncline(5.0f))

        val written = transport.writtenPackets.drop(countBefore)
        assertThat(written).hasSize(1)

        val packet = written[0]
        assertThat(packet[3]).isEqualTo(V2FeatureId.TARGET_GRADE.wireLo)
    }

    @Test
    fun `stop transitions to DISCONNECTED`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        session.stop()
        advanceUntilIdle()

        assertThat(session.sessionState.value).isEqualTo(SessionState.Disconnected)
        assertThat(session.exerciseData.value).isNull()
    }

    @Test
    fun `deviceIdentity is non-null with all-null fields after subscribe`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        val identity = session.deviceIdentity.value
        assertThat(identity).isNotNull()
        assertThat(identity!!.serialNumber).isNull()
        assertThat(identity.firmwareVersion).isNull()
        assertThat(identity.hardwareVersion).isNull()
        assertThat(identity.model).isNull()
        assertThat(identity.partNumber).isNull()
    }

    @Test
    fun `deviceIdentity is null before start`() = runTest {
        val session = createSession(this)
        assertThat(session.deviceIdentity.value).isNull()
    }

    @Test
    fun `deviceIdentity is cleared on stop`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        assertThat(session.deviceIdentity.value).isNotNull()

        session.stop()
        advanceUntilIdle()

        assertThat(session.deviceIdentity.value).isNull()
    }

    @Test
    fun `writeFeature is no-op when not streaming`() = runTest {
        val session = createSession(this)
        // Not started
        session.writeFeature(DeviceCommand.SetResistance(10))
        assertThat(transport.writtenPackets).isEmpty()
    }

    private fun buildEventPacket(feature: V2FeatureId, value: Float): ByteArray {
        val payload = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .put(feature.wireLo).put(feature.wireHi).putFloat(value).array()
        // source=0x02|type=EVENT(0x05) = 0x25
        return byteArrayOf(0x02, 0x25, payload.size.toByte(), *payload)
    }
}
