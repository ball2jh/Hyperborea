package com.nettarion.hyperborea.hardware.fitpro.session

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.test.buildDeviceInfo
import com.nettarion.hyperborea.hardware.fitpro.v2.V2Codec
import com.nettarion.hyperborea.hardware.fitpro.v2.V2FeatureId
import com.nettarion.hyperborea.hardware.fitpro.v2.V2Message
import com.nettarion.hyperborea.hardware.fitpro.v2.V2Session
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
            transport, logger, scope.backgroundScope, buildDeviceInfo(maxResistance = 24),
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

    // --- Double start ---

    @Test
    fun `double start is no-op when already streaming`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()
        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)

        val packetCountBefore = transport.writtenPackets.size
        session.start()
        advanceUntilIdle()

        assertThat(transport.writtenPackets.size).isEqualTo(packetCountBefore)
        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
    }

    // --- writeFeature for all command types ---

    @Test
    fun `writeFeature SetTargetPower sends GOAL_WATTS`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        val countBefore = transport.writtenPackets.size
        session.writeFeature(DeviceCommand.SetTargetPower(250))

        val written = transport.writtenPackets.drop(countBefore)
        assertThat(written).hasSize(1)
        assertThat(written[0][3]).isEqualTo(V2FeatureId.GOAL_WATTS.wireLo)
    }

    @Test
    fun `writeFeature SetTargetSpeed sends TARGET_KPH`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        val countBefore = transport.writtenPackets.size
        session.writeFeature(DeviceCommand.SetTargetSpeed(30.0f))

        val written = transport.writtenPackets.drop(countBefore)
        assertThat(written).hasSize(1)
        assertThat(written[0][3]).isEqualTo(V2FeatureId.TARGET_KPH.wireLo)
    }

    @Test
    fun `writeFeature AdjustIncline sends TARGET_GRADE`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        val countBefore = transport.writtenPackets.size
        session.writeFeature(DeviceCommand.AdjustIncline(increase = true))

        val written = transport.writtenPackets.drop(countBefore)
        assertThat(written).hasSize(1)
        assertThat(written[0][3]).isEqualTo(V2FeatureId.TARGET_GRADE.wireLo)
    }

    @Test
    fun `writeFeature AdjustSpeed sends TARGET_KPH`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        val countBefore = transport.writtenPackets.size
        session.writeFeature(DeviceCommand.AdjustSpeed(increase = true))

        val written = transport.writtenPackets.drop(countBefore)
        assertThat(written).hasSize(1)
        assertThat(written[0][3]).isEqualTo(V2FeatureId.TARGET_KPH.wireLo)
    }

    @Test
    fun `writeFeature PauseWorkout sends SYSTEM_MODE PAUSE`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        val countBefore = transport.writtenPackets.size
        session.writeFeature(DeviceCommand.PauseWorkout)

        val written = transport.writtenPackets.drop(countBefore)
        assertThat(written).hasSize(1)
        assertThat(written[0][3]).isEqualTo(V2FeatureId.SYSTEM_MODE.wireLo)
    }

    @Test
    fun `writeFeature ResumeWorkout sends SYSTEM_MODE RUNNING`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        val countBefore = transport.writtenPackets.size
        session.writeFeature(DeviceCommand.ResumeWorkout)

        val written = transport.writtenPackets.drop(countBefore)
        assertThat(written).hasSize(1)
        assertThat(written[0][3]).isEqualTo(V2FeatureId.SYSTEM_MODE.wireLo)
    }

    // --- Multiple events ---

    @Test
    fun `multiple events update multiple fields`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        transport.emitIncoming(buildEventPacket(V2FeatureId.WATTS, 200.0f))
        transport.emitIncoming(buildEventPacket(V2FeatureId.RPM, 95.0f))
        transport.emitIncoming(buildEventPacket(V2FeatureId.CURRENT_KPH, 30.0f))
        runCurrent()

        val data = session.exerciseData.value
        assertThat(data).isNotNull()
        assertThat(data!!.power).isEqualTo(200)
        assertThat(data.cadence).isEqualTo(95)
        assertThat(data.speed).isEqualTo(30.0f)
    }

    @Test
    fun `speed event updates exerciseData speed`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        transport.emitIncoming(buildEventPacket(V2FeatureId.CURRENT_KPH, 25.5f))
        runCurrent()

        assertThat(session.exerciseData.value!!.speed).isEqualTo(25.5f)
    }

    @Test
    fun `heart rate event updates exerciseData`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        transport.emitIncoming(buildEventPacket(V2FeatureId.PULSE, 140.0f))
        runCurrent()

        assertThat(session.exerciseData.value!!.heartRate).isEqualTo(140)
    }

    @Test
    fun `incline event updates exerciseData`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        transport.emitIncoming(buildEventPacket(V2FeatureId.CURRENT_GRADE, 5.0f))
        runCurrent()

        assertThat(session.exerciseData.value!!.incline).isEqualTo(5.0f)
    }

    // --- Stop sends cleanup packets ---

    @Test
    fun `stop sends IDLE mode and Unsubscribe`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        val countBefore = transport.writtenPackets.size
        session.stop()
        advanceUntilIdle()

        // Should have sent IDLE mode WriteFeature + Unsubscribe
        val stopPackets = transport.writtenPackets.drop(countBefore)
        assertThat(stopPackets.size).isAtLeast(2)
    }

    // --- Transport close during receive loop ---

    @Test
    fun `transport close transitions to Disconnected`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()
        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)

        transport.closeIncoming()
        // Give time for channel close to propagate through receive loop
        advanceTimeBy(1000)
        runCurrent()
        advanceUntilIdle()

        assertThat(session.sessionState.value).isEqualTo(SessionState.Disconnected)
    }

    // --- Error during start ---

    @Test
    fun `start transitions to Error when transport fails`() = runTest {
        val failingTransport = object : com.nettarion.hyperborea.hardware.fitpro.transport.HidTransport {
            override val isOpen: Boolean = false
            override suspend fun open() { throw java.io.IOException("USB disconnected") }
            override suspend fun close() {}
            override suspend fun write(data: ByteArray) {}
            override suspend fun readPacket(): ByteArray? = null
            override suspend fun clearBuffer() {}
            override fun incoming(): Flow<ByteArray> = emptyFlow()
        }
        val session = V2Session(failingTransport, logger, backgroundScope, buildDeviceInfo(maxResistance = 24))
        session.start()
        advanceUntilIdle()

        assertThat(session.sessionState.value).isInstanceOf(SessionState.Error::class.java)
    }

    private fun buildEventPacket(feature: V2FeatureId, value: Float): ByteArray {
        val payload = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .put(feature.wireLo).put(feature.wireHi).putFloat(value).array()
        // source=0x02|type=EVENT(0x05) = 0x25
        return byteArrayOf(0x02, 0x25, payload.size.toByte(), *payload)
    }
}
