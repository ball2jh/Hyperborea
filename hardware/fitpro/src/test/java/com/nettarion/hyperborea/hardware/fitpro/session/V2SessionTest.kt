package com.nettarion.hyperborea.hardware.fitpro.session

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.test.TestAppLogger
import com.nettarion.hyperborea.core.test.buildDeviceInfo
import com.nettarion.hyperborea.hardware.fitpro.v2.V2Codec
import com.nettarion.hyperborea.hardware.fitpro.v2.V2FeatureId
import com.nettarion.hyperborea.hardware.fitpro.v2.V2Message
import com.nettarion.hyperborea.hardware.fitpro.v2.V2Session
import com.nettarion.hyperborea.hardware.fitpro.v2.V2WorkoutMode
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
    private val logger = TestAppLogger()

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
    fun `start brings the console up to RUNNING and clears degraded when it confirms`() = runTest {
        val session = createSession(this)
        // Console reports its workout state as we drive it: NONE → WARM_UP → RUNNING.
        transport.emitIncoming(buildEventPacket(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.WARM_UP.raw))
        transport.emitIncoming(buildEventPacket(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.RUNNING.raw))

        session.start()
        advanceUntilIdle()

        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
        assertThat(session.degradedReason.value).isNull()
        // Both WORKOUT_STATE writes (WARM_UP then RUNNING) went out — not a single jump to RUNNING.
        val workoutStateWrites = transport.writtenPackets.count { it.size > 3 && it[3] == V2FeatureId.WORKOUT_STATE.wireLo }
        assertThat(workoutStateWrites).isAtLeast(2)
    }

    @Test
    fun `start still reaches Streaming but flags degraded when the console never confirms the workout`() = runTest {
        val session = createSession(this)
        // No WORKOUT_STATE events — the confirmation reads time out.
        session.start()
        advanceUntilIdle()

        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
        assertThat(session.degradedReason.value).isNotNull()
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
    fun `writeFeature PauseWorkout sends WORKOUT_STATE PAUSED`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        val countBefore = transport.writtenPackets.size
        session.writeFeature(DeviceCommand.PauseWorkout)

        val written = transport.writtenPackets.drop(countBefore)
        assertThat(written).hasSize(1)
        assertThat(written[0][3]).isEqualTo(V2FeatureId.WORKOUT_STATE.wireLo)
    }

    @Test
    fun `writeFeature ResumeWorkout sends WORKOUT_STATE RUNNING`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        val countBefore = transport.writtenPackets.size
        session.writeFeature(DeviceCommand.ResumeWorkout)

        val written = transport.writtenPackets.drop(countBefore)
        assertThat(written).hasSize(1)
        assertThat(written[0][3]).isEqualTo(V2FeatureId.WORKOUT_STATE.wireLo)
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

    // --- Device type detection + treadmill path ---

    @Test
    fun `treadmill features write WARM_UP but never RUNNING and clear degraded`() = runTest {
        // Treadmill profile: belt speed + grade, no flywheel resistance.
        val session = createSession(this)
        transport.emitIncoming(buildSupportedFeaturesPacket(
            V2FeatureId.TARGET_KPH, V2FeatureId.CURRENT_KPH,
            V2FeatureId.TARGET_GRADE, V2FeatureId.CURRENT_GRADE,
            V2FeatureId.WORKOUT_STATE,
        ))
        // Only WARM_UP confirmation arrives — the MCU is waiting on the physical Start key, no
        // RUNNING event ever comes.
        transport.emitIncoming(buildEventPacket(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.WARM_UP.raw))

        session.start()
        advanceUntilIdle()

        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
        assertThat(session.detectedDeviceType).isEqualTo(DeviceType.TREADMILL)
        // Critical: WARM_UP was written, RUNNING was not — the user finishes the start handshake.
        val workoutStateWrites = transport.writtenPackets.mapNotNull { it.workoutStateWriteValue() }
        assertThat(workoutStateWrites).contains(V2WorkoutMode.WARM_UP.raw)
        assertThat(workoutStateWrites).doesNotContain(V2WorkoutMode.RUNNING.raw)
        // And no spurious "degraded" — this is the expected armed state.
        assertThat(session.degradedReason.value).isNull()
    }

    @Test
    fun `bike features still drive the full WARM_UP then RUNNING transition`() = runTest {
        // Bike profile: resistance features present.
        val session = createSession(this)
        transport.emitIncoming(buildSupportedFeaturesPacket(
            V2FeatureId.TARGET_RESISTANCE, V2FeatureId.MAX_RESISTANCE,
            V2FeatureId.RPM, V2FeatureId.WATTS, V2FeatureId.WORKOUT_STATE,
        ))
        transport.emitIncoming(buildEventPacket(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.WARM_UP.raw))
        transport.emitIncoming(buildEventPacket(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.RUNNING.raw))

        session.start()
        advanceUntilIdle()

        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
        assertThat(session.detectedDeviceType).isEqualTo(DeviceType.BIKE)
        val workoutStateWrites = transport.writtenPackets.mapNotNull { it.workoutStateWriteValue() }
        assertThat(workoutStateWrites).containsAtLeast(V2WorkoutMode.WARM_UP.raw, V2WorkoutMode.RUNNING.raw).inOrder()
        assertThat(session.degradedReason.value).isNull()
    }

    @Test
    fun `WORKOUT_STATE events update exerciseData workoutMode using V1 numbering`() = runTest {
        // The orchestrator's workout-mode monitor uses V1 codes; V2 must translate so the same
        // monitor reacts uniformly to both protocols.
        val session = createSession(this)
        transport.emitIncoming(buildSupportedFeaturesPacket(
            V2FeatureId.TARGET_RESISTANCE, V2FeatureId.WORKOUT_STATE,
        ))
        transport.emitIncoming(buildEventPacket(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.WARM_UP.raw))
        transport.emitIncoming(buildEventPacket(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.RUNNING.raw))

        session.start()
        advanceUntilIdle()

        // V2 RUNNING (3) must be reported to the accumulator as V1 RUNNING (2).
        assertThat(session.exerciseData.value?.workoutMode).isEqualTo(2)

        // Now a PAUSED event should translate to V1 PAUSE (3).
        transport.emitIncoming(buildEventPacket(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.PAUSED.raw))
        runCurrent()
        assertThat(session.exerciseData.value?.workoutMode).isEqualTo(3)

        // OFF_MACHINE → V1 DMK (8): the orchestrator's safety-pause path keys off this.
        transport.emitIncoming(buildEventPacket(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.OFF_MACHINE.raw))
        runCurrent()
        assertThat(session.exerciseData.value?.workoutMode).isEqualTo(8)
    }

    /**
     * Returns the raw [V2WorkoutMode] float carried by this outgoing packet if it's a
     * WriteFeature targeting [V2FeatureId.WORKOUT_STATE]; otherwise null. Packet layout:
     * `[0x02, sourceType, len, featureLo, featureHi, f32 LE]`.
     */
    private fun ByteArray.workoutStateWriteValue(): Float? {
        if (size < 9 || this[0] != 0x02.toByte()) return null
        val type = this[1].toInt() and 0x0F
        if (type != 0x02) return null // CMD_WRITE
        if (this[3] != V2FeatureId.WORKOUT_STATE.wireLo || this[4] != V2FeatureId.WORKOUT_STATE.wireHi) return null
        return ByteBuffer.wrap(this, 5, 4).order(ByteOrder.LITTLE_ENDIAN).float
    }

    /**
     * Build a SupportedFeatures response packet. Source = device (0x02), type nibble = RSP_FEATURES
     * (0x01), so sourceType byte = 0x21. Payload = list of (lo, hi) pairs.
     */
    private fun buildSupportedFeaturesPacket(vararg features: V2FeatureId): ByteArray {
        val payload = ByteArray(features.size * 2)
        features.forEachIndexed { i, feature ->
            payload[i * 2] = feature.wireLo
            payload[i * 2 + 1] = feature.wireHi
        }
        return byteArrayOf(0x02, 0x21, payload.size.toByte(), *payload)
    }

    private fun buildEventPacket(feature: V2FeatureId, value: Float): ByteArray {
        val payload = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .put(feature.wireLo).put(feature.wireHi).putFloat(value).array()
        // source=0x02|type=EVENT(0x05) = 0x25
        return byteArrayOf(0x02, 0x25, payload.size.toByte(), *payload)
    }
}
