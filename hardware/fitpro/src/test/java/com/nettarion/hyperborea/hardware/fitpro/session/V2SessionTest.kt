package com.nettarion.hyperborea.hardware.fitpro.session

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.model.ConsoleKey
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
import kotlinx.coroutines.launch
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
    fun `no periodic keepalive packets while streaming`() = runTest {
        // Init may include one-shot configuration writes (heartbeat interval, idle lock), but
        // nothing is ever written unprompted after bring-up — the stock stack doesn't either.
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        val countBefore = transport.writtenPackets.size
        advanceTimeBy(30_000)
        runCurrent()

        assertThat(transport.writtenPackets.size).isEqualTo(countBefore)
    }

    @Test
    fun `subscribe only requests console-supported features`() = runTest {
        val session = createSession(this)
        emitSupportedFeatures(
            V2FeatureId.TARGET_RESISTANCE, V2FeatureId.WATTS, V2FeatureId.WORKOUT_STATE,
        )

        session.start()
        advanceUntilIdle()

        assertThat(subscribedFeatures()).containsExactly(
            V2FeatureId.WORKOUT_STATE, V2FeatureId.TARGET_RESISTANCE, V2FeatureId.WATTS,
        )
    }

    @Test
    fun `subscriptions are cleared before subscribing`() = runTest {
        val session = createSession(this)
        emitSupportedFeatures(
            V2FeatureId.TARGET_RESISTANCE, V2FeatureId.WORKOUT_STATE,
        )

        session.start()
        advanceUntilIdle()

        // An empty Unsubscribe (type 0x07, zero-length payload) must precede the first Subscribe.
        val clearIndex = transport.writtenPackets.indexOfFirst {
            (it[1].toInt() and 0x0F) == 0x07 && it[2].toInt() == 0
        }
        val firstSubscribeIndex = transport.writtenPackets.indexOfFirst {
            (it[1].toInt() and 0x0F) == 0x01
        }
        assertThat(clearIndex).isAtLeast(0)
        assertThat(firstSubscribeIndex).isGreaterThan(clearIndex)
    }

    @Test
    fun `subscribe falls back to the full wanted list when the console never declares features`() = runTest {
        val session = createSession(this)
        // No SupportedFeatures reply at all.
        session.start()
        advanceUntilIdle()

        assertThat(subscribedFeatures()).containsExactlyElementsIn(V2FeatureId.subscribable)
    }

    @Test
    fun `init writes heartbeat interval and idle unlock when the console declares them`() = runTest {
        // Mirrors the stock service's one-shot connect configuration (HEART_BEAT_INTERVAL=720,
        // IDLE_SYSTEM_MODE_LOCK=UNLOCKED) — written once, only when declared.
        val session = createSession(this)
        emitSupportedFeatures(
            V2FeatureId.HEART_BEAT_INTERVAL, V2FeatureId.IDLE_SYSTEM_MODE_LOCK,
            V2FeatureId.TARGET_RESISTANCE, V2FeatureId.WORKOUT_STATE,
        )
        session.start()
        advanceUntilIdle()

        val heartbeat = transport.writtenPackets.filter { it.isFeatureWrite(V2FeatureId.HEART_BEAT_INTERVAL) }
        val idleLock = transport.writtenPackets.filter { it.isFeatureWrite(V2FeatureId.IDLE_SYSTEM_MODE_LOCK) }
        assertThat(heartbeat).hasSize(1)
        assertThat(heartbeat[0].featureWriteValue()).isEqualTo(720f)
        assertThat(idleLock).hasSize(1)
        assertThat(idleLock[0].featureWriteValue()).isEqualTo(0f)
    }

    @Test
    fun `init configuration writes are skipped when the console doesn't declare them`() = runTest {
        val session = createSession(this)
        emitSupportedFeatures(V2FeatureId.TARGET_RESISTANCE, V2FeatureId.WORKOUT_STATE)
        session.start()
        advanceUntilIdle()

        assertThat(transport.writtenPackets.none { it.isFeatureWrite(V2FeatureId.HEART_BEAT_INTERVAL) }).isTrue()
        assertThat(transport.writtenPackets.none { it.isFeatureWrite(V2FeatureId.IDLE_SYSTEM_MODE_LOCK) }).isTrue()
    }

    @Test
    fun `multi-frame feature list is accumulated and detects a treadmill`() = runTest {
        // Real consoles stream the list as many small frames (some carrying only feature ids we
        // don't model) and finish with an empty terminator. The treadmill verdict requires the
        // union — its grade/speed features arrive in late frames.
        val session = createSession(this)
        transport.emitIncoming(buildSupportedFeaturesPacket(V2FeatureId.SYSTEM_MODE))
        transport.emitIncoming(buildSupportedFeaturesPacket(V2FeatureId.CURRENT_CALORIES))
        transport.emitIncoming(buildSupportedFeaturesPacket(V2FeatureId.PULSE, V2FeatureId.DISTANCE))
        transport.emitIncoming(buildSupportedFeaturesPacket(V2FeatureId.TARGET_KPH, V2FeatureId.CURRENT_KPH))
        transport.emitIncoming(buildSupportedFeaturesPacket(V2FeatureId.TARGET_GRADE, V2FeatureId.CURRENT_GRADE))
        transport.emitIncoming(buildSupportedFeaturesPacket(V2FeatureId.WORKOUT_STATE, V2FeatureId.RUNNING_TIME))
        // Frame carrying feature ids outside our enum — list content, NOT a terminator.
        // (0x03E7=999, 0x03E8=1000 — genuinely unmodelled codes.)
        transport.emitIncoming(byteArrayOf(0x02, 0x21, 0x04, 0xE7.toByte(), 0x03, 0xE8.toByte(), 0x03))
        transport.emitIncoming(buildSupportedFeaturesPacket()) // end of list

        session.start()
        advanceUntilIdle()

        assertThat(session.detectedDeviceType).isEqualTo(DeviceType.TREADMILL)
        // Subscriptions reflect the union, not any single frame.
        assertThat(subscribedFeatures()).containsExactly(
            V2FeatureId.SYSTEM_MODE, V2FeatureId.WORKOUT_STATE, V2FeatureId.CURRENT_CALORIES,
            V2FeatureId.PULSE, V2FeatureId.DISTANCE, V2FeatureId.TARGET_KPH, V2FeatureId.CURRENT_KPH,
            V2FeatureId.CURRENT_GRADE, V2FeatureId.RUNNING_TIME,
        )
        // Critical treadmill behaviour: never write the workout state at arm time — only a start
        // request (READY_TO_START report / physical Start key) may drive it.
        val workoutStateWrites = transport.writtenPackets.mapNotNull { it.workoutStateWriteValue() }
        assertThat(workoutStateWrites).isEmpty()
    }

    @Test
    fun `console-reported device type wins over the feature heuristic`() = runTest {
        val session = createSession(this)
        // Feature set says "bike" (resistance present) — but the console reports its own type.
        emitSupportedFeatures(
            V2FeatureId.DEVICE_TYPE, V2FeatureId.TARGET_RESISTANCE, V2FeatureId.WORKOUT_STATE,
        )
        transport.emitIncoming(buildEventPacket(V2FeatureId.DEVICE_TYPE, 4f)) // treadmill code

        session.start()
        advanceUntilIdle()

        assertThat(session.detectedDeviceType).isEqualTo(DeviceType.TREADMILL)
    }

    @Test
    fun `missing device-type event falls back to the feature heuristic`() = runTest {
        val session = createSession(this)
        // DEVICE_TYPE declared but its initial event never arrives; resistance → bike.
        emitSupportedFeatures(
            V2FeatureId.DEVICE_TYPE, V2FeatureId.TARGET_RESISTANCE, V2FeatureId.WORKOUT_STATE,
        )

        session.start()
        advanceUntilIdle()

        assertThat(session.detectedDeviceType).isEqualTo(DeviceType.BIKE)
    }

    @Test
    fun `feature frames without a terminator are still used after the wait times out`() = runTest {
        val session = createSession(this)
        // Frames arrive but the empty end-of-list frame never does.
        transport.emitIncoming(buildSupportedFeaturesPacket(V2FeatureId.TARGET_RESISTANCE, V2FeatureId.WATTS))
        transport.emitIncoming(buildSupportedFeaturesPacket(V2FeatureId.WORKOUT_STATE))

        session.start()
        advanceUntilIdle()

        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
        assertThat(subscribedFeatures()).containsExactly(
            V2FeatureId.WORKOUT_STATE, V2FeatureId.TARGET_RESISTANCE, V2FeatureId.WATTS,
        )
    }

    @Test
    fun `product info populates the device identity`() = runTest {
        val session = createSession(this)
        emitSupportedFeatures(V2FeatureId.TARGET_RESISTANCE, V2FeatureId.WORKOUT_STATE)
        transport.emitIncoming(buildProductInfoPacket(1, "1.4.2"))   // sw version
        transport.emitIncoming(buildProductInfoPacket(3, "430934")) // hw part number
        transport.emitIncoming(buildProductInfoPacket(4, "NTL12345"))
        transport.emitIncoming(buildProductInfoPacket(5, "SER-99"))
        transport.emitIncoming(buildProductInfoPacket(0, ""))        // end of list

        session.start()
        advanceUntilIdle()

        val identity = session.deviceIdentity.value
        assertThat(identity).isNotNull()
        assertThat(identity!!.firmwareVersion).isEqualTo("1.4.2")
        assertThat(identity.partNumber).isEqualTo("430934")
        assertThat(identity.model).isEqualTo("NTL12345")
        assertThat(identity.serialNumber).isEqualTo("SER-99")
    }

    @Test
    fun `console keypad events emit ConsoleKeys with edge detection`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        val keys = mutableListOf<ConsoleKey>()
        val collectJob = backgroundScope.launch { session.consoleKeyPresses.collect { keys.add(it) } }
        runCurrent()

        transport.emitIncoming(buildEventPacket(V2FeatureId.KEY_COOKED, 3f)) // speed up
        transport.emitIncoming(buildEventPacket(V2FeatureId.KEY_COOKED, 3f)) // repeat — ignored
        transport.emitIncoming(buildEventPacket(V2FeatureId.KEY_COOKED, 0f)) // release
        transport.emitIncoming(buildEventPacket(V2FeatureId.KEY_COOKED, 3f)) // pressed again
        transport.emitIncoming(buildEventPacket(V2FeatureId.KEY_COOKED, 1f)) // stop
        runCurrent()

        assertThat(keys).containsExactly(
            ConsoleKey.SPEED_UP, ConsoleKey.SPEED_UP, ConsoleKey.STOP,
        ).inOrder()
        collectJob.cancel()
    }

    @Test
    fun `writes to features the console did not declare are skipped`() = runTest {
        val session = createSession(this)
        // Treadmill-ish console: no resistance features declared.
        emitSupportedFeatures(V2FeatureId.TARGET_KPH, V2FeatureId.WORKOUT_STATE)
        session.start()
        advanceUntilIdle()

        val countBefore = transport.writtenPackets.size
        session.writeFeature(DeviceCommand.SetResistance(10))

        assertThat(transport.writtenPackets.size).isEqualTo(countBefore)
    }

    @Test
    fun `SetFanSpeed writes the fan feature when declared`() = runTest {
        val session = createSession(this)
        emitSupportedFeatures(V2FeatureId.FAN_STATE, V2FeatureId.TARGET_RESISTANCE, V2FeatureId.WORKOUT_STATE)
        session.start()
        advanceUntilIdle()

        val countBefore = transport.writtenPackets.size
        session.writeFeature(DeviceCommand.SetFanSpeed(3))

        val written = transport.writtenPackets.drop(countBefore)
        assertThat(written).hasSize(1)
        assertThat(written[0][3]).isEqualTo(V2FeatureId.FAN_STATE.wireLo)
    }

    @Test
    fun `lifetime stats events update the device identity`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        transport.emitIncoming(buildEventPacket(V2FeatureId.TOTAL_IN_USE_SECONDS, 7200f))
        transport.emitIncoming(buildEventPacket(V2FeatureId.TOTAL_MACHINE_DISTANCE, 250_000f))
        runCurrent()

        assertThat(session.deviceIdentity.value!!.equipmentHours).isEqualTo(7200L)
        assertThat(session.deviceIdentity.value!!.equipmentDistance).isEqualTo(250_000f)
    }

    /** Build one product-info field frame: extended response, class=2, then tag + UTF-8 text. */
    private fun buildProductInfoPacket(fieldType: Int, text: String): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val payload = byteArrayOf(0x02, fieldType.toByte(), *textBytes)
        return byteArrayOf(0x02, 0x2E, payload.size.toByte(), *payload)
    }

    /** Feature ids carried by all outgoing Subscribe packets (type nibble 0x01). */
    private fun subscribedFeatures(): List<V2FeatureId> =
        transport.writtenPackets
            .filter { (it[1].toInt() and 0x0F) == 0x01 }
            .flatMap { pkt ->
                val len = pkt[2].toInt() and 0xFF
                (0 until len / 2).mapNotNull { i ->
                    V2FeatureId.fromWireBytes(pkt[3 + i * 2], pkt[4 + i * 2])
                }
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
    fun `treadmill arms idle and writes no workout state until the user starts`() = runTest {
        // Treadmill profile: belt speed + grade, no flywheel resistance.
        val session = createSession(this)
        emitSupportedFeatures(
            V2FeatureId.TARGET_KPH, V2FeatureId.CURRENT_KPH,
            V2FeatureId.TARGET_GRADE, V2FeatureId.CURRENT_GRADE,
            V2FeatureId.WORKOUT_STATE,
        )

        session.start()
        advanceUntilIdle()

        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
        assertThat(session.detectedDeviceType).isEqualTo(DeviceType.TREADMILL)
        // Critical: NOTHING is written to WORKOUT_STATE at arm — writing WARM_UP would run the belt
        // before the user presses the physical Start. The MCU stays idle until then.
        val workoutStateWrites = transport.writtenPackets.mapNotNull { it.workoutStateWriteValue() }
        assertThat(workoutStateWrites).isEmpty()
        // And no spurious "degraded" — this is the expected armed state.
        assertThat(session.degradedReason.value).isNull()
    }

    @Test
    fun `treadmill belt speed is read from TARGET_KPH`() = runTest {
        // The V2 treadmill never sends CURRENT_KPH; the belt runs at the commanded TARGET_KPH, so
        // that is the actual speed and must reach exerciseData (and Zwift).
        val session = createSession(this)
        emitSupportedFeatures(
            V2FeatureId.TARGET_KPH, V2FeatureId.CURRENT_GRADE, V2FeatureId.WORKOUT_STATE,
        )
        session.start()
        advanceUntilIdle()
        assertThat(session.detectedDeviceType).isEqualTo(DeviceType.TREADMILL)

        transport.emitIncoming(buildEventPacket(V2FeatureId.TARGET_KPH, 6.5f))
        runCurrent()

        assertThat(session.exerciseData.value!!.speed).isEqualTo(6.5f)
    }

    @Test
    fun `START_REQUESTED ack lets the console self-drive without host workout-state writes`() = runTest {
        // When the console declares START_REQUESTED, the stock handshake is: ack the user's Start,
        // then the MCU drives ITSELF to RUNNING. We must not also write WARM_UP/RUNNING or command
        // an initial belt speed.
        val session = createSession(this)
        emitSupportedFeatures(
            V2FeatureId.TARGET_KPH, V2FeatureId.START_REQUESTED, V2FeatureId.WORKOUT_STATE,
        )
        session.start()
        advanceUntilIdle()
        val baseline = transport.writtenPackets.size

        transport.emitIncoming(buildEventPacket(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.READY_TO_START.raw))
        runCurrent()
        // The console drives itself to RUNNING; the ack wait resolves.
        transport.emitIncoming(buildEventPacket(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.RUNNING.raw))
        advanceUntilIdle()

        val written = transport.writtenPackets.drop(baseline)
        // START_REQUESTED=TRUE was written...
        val ack = written.filter { it.isFeatureWrite(V2FeatureId.START_REQUESTED) }
        assertThat(ack).hasSize(1)
        assertThat(ack[0].featureWriteValue()).isEqualTo(1f)
        // ...and no host WORKOUT_STATE writes, and no initial belt-speed command.
        assertThat(written.mapNotNull { it.workoutStateWriteValue() }).isEmpty()
        assertThat(written.none { it.isFeatureWrite(V2FeatureId.TARGET_KPH) }).isTrue()
    }

    @Test
    fun `console without START_REQUESTED is host-driven to RUNNING and the belt is commanded`() = runTest {
        // Older firmware (e.g. the iFIT-LargeX / T Series 9, fw 1.19.x) rejects START_REQUESTED, so
        // on the console's start signal the host drives WORKOUT_STATE WARM_UP → RUNNING and then
        // commands the minimum belt speed (the firmware won't move the belt on its own).
        val session = createSession(this)
        emitSupportedFeatures(V2FeatureId.TARGET_KPH, V2FeatureId.WORKOUT_STATE)
        session.start()
        advanceUntilIdle()
        val baseline = transport.writtenPackets.size

        transport.emitIncoming(buildEventPacket(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.READY_TO_START.raw))
        runCurrent()                 // WARM_UP written, parked at the write gap
        advanceTimeBy(200)
        runCurrent()                 // gap elapsed: RUNNING written
        transport.emitIncoming(buildEventPacket(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.RUNNING.raw))
        runCurrent()                 // confirm completes → initial belt speed write

        val written = transport.writtenPackets.drop(baseline)
        // Host-drove the workout state, did NOT write START_REQUESTED.
        assertThat(written.mapNotNull { it.workoutStateWriteValue() })
            .containsExactly(V2WorkoutMode.WARM_UP.raw, V2WorkoutMode.RUNNING.raw).inOrder()
        assertThat(written.none { it.isFeatureWrite(V2FeatureId.START_REQUESTED) }).isTrue()
        val speed = written.filter { it.isFeatureWrite(V2FeatureId.TARGET_KPH) }
        assertThat(speed).hasSize(1)
        assertThat(speed[0].featureWriteValue()).isEqualTo(0.5f)
    }

    @Test
    fun `physical Start key host-drives the workout on a console without START_REQUESTED`() = runTest {
        // Covers firmware that forwards the Start key without also reporting READY_TO_START.
        val session = createSession(this)
        emitSupportedFeatures(V2FeatureId.TARGET_KPH, V2FeatureId.WORKOUT_STATE)
        session.start()
        advanceUntilIdle()
        val baseline = transport.writtenPackets.size

        transport.emitIncoming(buildEventPacket(V2FeatureId.KEY_COOKED, 2f)) // START
        runCurrent()
        advanceTimeBy(200)
        runCurrent()

        val writes = transport.writtenPackets.drop(baseline).mapNotNull { it.workoutStateWriteValue() }
        assertThat(writes).containsExactly(V2WorkoutMode.WARM_UP.raw, V2WorkoutMode.RUNNING.raw).inOrder()
    }

    @Test
    fun `host-driven console routes speed incline and Stop keys while RUNNING`() = runTest {
        // The console forwards keys but drives nothing itself, so the host answers them.
        val session = createSession(this)
        emitSupportedFeatures(V2FeatureId.TARGET_KPH, V2FeatureId.TARGET_GRADE, V2FeatureId.WORKOUT_STATE)
        session.start()
        advanceUntilIdle()
        transport.emitIncoming(buildEventPacket(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.RUNNING.raw))
        runCurrent()
        val baseline = transport.writtenPackets.size

        transport.emitIncoming(buildEventPacket(V2FeatureId.KEY_COOKED, 3f)) // speed up
        runCurrent()
        transport.emitIncoming(buildEventPacket(V2FeatureId.KEY_COOKED, 0f)) // release
        transport.emitIncoming(buildEventPacket(V2FeatureId.KEY_COOKED, 5f)) // incline up
        runCurrent()

        val writes = transport.writtenPackets.drop(baseline)
        assertThat(writes.filter { it.isFeatureWrite(V2FeatureId.TARGET_KPH) }).hasSize(1)
        assertThat(writes.filter { it.isFeatureWrite(V2FeatureId.TARGET_GRADE) }).hasSize(1)
    }

    @Test
    fun `self-driving console that declares START_REQUESTED leaves keys observe-only`() = runTest {
        // Newer firmware acts on its own keys; routing them too would double-step the belt.
        val session = createSession(this)
        emitSupportedFeatures(
            V2FeatureId.TARGET_KPH, V2FeatureId.TARGET_GRADE,
            V2FeatureId.START_REQUESTED, V2FeatureId.WORKOUT_STATE,
        )
        session.start()
        advanceUntilIdle()
        transport.emitIncoming(buildEventPacket(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.RUNNING.raw))
        runCurrent()
        val baseline = transport.writtenPackets.size

        transport.emitIncoming(buildEventPacket(V2FeatureId.KEY_COOKED, 3f)) // speed up
        runCurrent()
        transport.emitIncoming(buildEventPacket(V2FeatureId.KEY_COOKED, 0f))
        transport.emitIncoming(buildEventPacket(V2FeatureId.KEY_COOKED, 5f)) // incline up
        runCurrent()

        val writes = transport.writtenPackets.drop(baseline)
        assertThat(writes.none { it.isFeatureWrite(V2FeatureId.TARGET_KPH) }).isTrue()
        assertThat(writes.none { it.isFeatureWrite(V2FeatureId.TARGET_GRADE) }).isTrue()
    }

    @Test
    fun `start that never reaches RUNNING flags then clears a degraded reason`() = runTest {
        val session = createSession(this)
        emitSupportedFeatures(V2FeatureId.TARGET_KPH, V2FeatureId.WORKOUT_STATE)
        session.start()
        advanceUntilIdle()
        assertThat(session.degradedReason.value).isNull()

        transport.emitIncoming(buildEventPacket(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.READY_TO_START.raw))
        runCurrent()
        // No RUNNING event arrives within the confirm window → surface a degraded reason.
        advanceTimeBy(6_000)
        runCurrent()
        assertThat(session.degradedReason.value).isNotNull()

        // The console starts late after all → the warning clears.
        transport.emitIncoming(buildEventPacket(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.RUNNING.raw))
        runCurrent()
        assertThat(session.degradedReason.value).isNull()
    }

    @Test
    fun `overlapping start triggers collapse to one drive and re-presses while RUNNING are ignored`() = runTest {
        val session = createSession(this)
        emitSupportedFeatures(V2FeatureId.TARGET_KPH, V2FeatureId.WORKOUT_STATE)
        session.start()
        advanceUntilIdle()
        val baseline = transport.writtenPackets.size

        // Firmware that both reports READY_TO_START and forwards the key — one drive, not two.
        transport.emitIncoming(buildEventPacket(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.READY_TO_START.raw))
        transport.emitIncoming(buildEventPacket(V2FeatureId.KEY_COOKED, 2f))
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        transport.emitIncoming(buildEventPacket(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.RUNNING.raw))
        runCurrent()

        assertThat(
            transport.writtenPackets.drop(baseline).mapNotNull { it.workoutStateWriteValue() },
        ).containsExactly(V2WorkoutMode.WARM_UP.raw, V2WorkoutMode.RUNNING.raw).inOrder()

        // Pressing Start again while RUNNING is a no-op.
        val afterRunning = transport.writtenPackets.size
        transport.emitIncoming(buildEventPacket(V2FeatureId.KEY_COOKED, 0f)) // release
        transport.emitIncoming(buildEventPacket(V2FeatureId.KEY_COOKED, 2f)) // press again
        runCurrent()
        advanceTimeBy(6_000)
        runCurrent()
        assertThat(transport.writtenPackets.drop(afterRunning)).isEmpty()
    }

    @Test
    fun `MCU-reported limits are captured into deviceCapabilities`() = runTest {
        val session = createSession(this)
        emitSupportedFeatures(
            V2FeatureId.TARGET_KPH, V2FeatureId.MAX_KPH, V2FeatureId.MIN_GRADE_PERCENT,
            V2FeatureId.MAX_GRADE_PERCENT, V2FeatureId.MAX_RESISTANCE, V2FeatureId.MAX_WATTS,
            V2FeatureId.WORKOUT_STATE,
        )
        // The device reports its own physical bounds as events in the post-subscribe window.
        transport.emitIncoming(buildEventPacket(V2FeatureId.MAX_KPH, 20f))
        transport.emitIncoming(buildEventPacket(V2FeatureId.MIN_GRADE_PERCENT, 0f))
        transport.emitIncoming(buildEventPacket(V2FeatureId.MAX_GRADE_PERCENT, 12f))
        transport.emitIncoming(buildEventPacket(V2FeatureId.MAX_RESISTANCE, 22f))
        transport.emitIncoming(buildEventPacket(V2FeatureId.MAX_WATTS, 800f))
        session.start()
        advanceUntilIdle()

        val caps = session.deviceCapabilities
        assertThat(caps.maxSpeed).isEqualTo(20f)
        assertThat(caps.minIncline).isEqualTo(0f)
        assertThat(caps.maxIncline).isEqualTo(12f)
        assertThat(caps.maxResistance).isEqualTo(22)
        assertThat(caps.maxPower).isEqualTo(800)
    }

    @Test
    fun `unreported limits leave deviceCapabilities fields null`() = runTest {
        val session = createSession(this)
        emitSupportedFeatures(V2FeatureId.TARGET_KPH, V2FeatureId.WORKOUT_STATE)
        session.start()
        advanceUntilIdle()

        val caps = session.deviceCapabilities
        assertThat(caps.maxSpeed).isNull()
        assertThat(caps.maxIncline).isNull()
        assertThat(caps.maxResistance).isNull()
    }

    @Test
    fun `console PAUSED event pauses the ride clock without a host write`() = runTest {
        // The MCU self-pauses on the physical Stop and reports PAUSED; the host writes nothing but
        // mirrors the state to the accumulator (V1 PAUSE = 3).
        val session = createSession(this)
        emitSupportedFeatures(V2FeatureId.TARGET_KPH, V2FeatureId.WORKOUT_STATE)
        session.start()
        advanceUntilIdle()
        transport.emitIncoming(buildEventPacket(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.RUNNING.raw))
        runCurrent()
        val baseline = transport.writtenPackets.size

        transport.emitIncoming(buildEventPacket(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.PAUSED.raw))
        runCurrent()

        assertThat(transport.writtenPackets.drop(baseline)).isEmpty()
        assertThat(session.exerciseData.value?.workoutMode).isEqualTo(3)
    }

    @Test
    fun `DISTANCE events are meters on the wire and kilometers in ExerciseData`() = runTest {
        val session = createSession(this)
        session.start()
        advanceUntilIdle()

        transport.emitIncoming(buildEventPacket(V2FeatureId.DISTANCE, 8000f)) // 8 km in meters
        runCurrent()

        assertThat(session.exerciseData.value!!.distance).isEqualTo(8f)
    }

    @Test
    fun `READY_TO_START on a bike does not trigger a start drive`() = runTest {
        val session = createSession(this)
        emitSupportedFeatures(V2FeatureId.TARGET_RESISTANCE, V2FeatureId.WORKOUT_STATE)
        session.start()
        advanceUntilIdle()
        val baseline = transport.writtenPackets.size

        transport.emitIncoming(buildEventPacket(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.READY_TO_START.raw))
        runCurrent()
        advanceTimeBy(6_000)
        runCurrent()

        assertThat(
            transport.writtenPackets.drop(baseline).mapNotNull { it.workoutStateWriteValue() },
        ).isEmpty()
    }

    @Test
    fun `bike features still drive the full WARM_UP then RUNNING transition`() = runTest {
        // Bike profile: resistance features present.
        val session = createSession(this)
        emitSupportedFeatures(
            V2FeatureId.TARGET_RESISTANCE, V2FeatureId.MAX_RESISTANCE,
            V2FeatureId.RPM, V2FeatureId.WATTS, V2FeatureId.WORKOUT_STATE,
        )
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
        emitSupportedFeatures(
            V2FeatureId.TARGET_RESISTANCE, V2FeatureId.WORKOUT_STATE,
        )
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
    private fun ByteArray.isFeatureWrite(feature: V2FeatureId): Boolean {
        if (size < 9 || this[0] != 0x02.toByte()) return false
        if ((this[1].toInt() and 0x0F) != 0x02) return false // CMD_WRITE
        return this[3] == feature.wireLo && this[4] == feature.wireHi
    }

    private fun ByteArray.featureWriteValue(): Float =
        ByteBuffer.wrap(this, 5, 4).order(ByteOrder.LITTLE_ENDIAN).float

    private fun ByteArray.workoutStateWriteValue(): Float? {
        if (size < 9 || this[0] != 0x02.toByte()) return null
        val type = this[1].toInt() and 0x0F
        if (type != 0x02) return null // CMD_WRITE
        if (this[3] != V2FeatureId.WORKOUT_STATE.wireLo || this[4] != V2FeatureId.WORKOUT_STATE.wireHi) return null
        return ByteBuffer.wrap(this, 5, 4).order(ByteOrder.LITTLE_ENDIAN).float
    }

    /**
     * Build a SupportedFeatures response packet. Source = device (0x02), type nibble = RSP_FEATURES
     * (0x01), so sourceType byte = 0x21. Payload = list of (lo, hi) pairs. An empty packet is the
     * end-of-list terminator.
     */
    private fun buildSupportedFeaturesPacket(vararg features: V2FeatureId): ByteArray {
        val payload = ByteArray(features.size * 2)
        features.forEachIndexed { i, feature ->
            payload[i * 2] = feature.wireLo
            payload[i * 2 + 1] = feature.wireHi
        }
        return byteArrayOf(0x02, 0x21, payload.size.toByte(), *payload)
    }

    /** Emit the console's supported-features list the way real consoles send it: frames + empty terminator. */
    private suspend fun emitSupportedFeatures(vararg features: V2FeatureId) {
        transport.emitIncoming(buildSupportedFeaturesPacket(*features))
        transport.emitIncoming(buildSupportedFeaturesPacket())
    }

    private fun buildEventPacket(feature: V2FeatureId, value: Float): ByteArray {
        val payload = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .put(feature.wireLo).put(feature.wireHi).putFloat(value).array()
        // source=0x02|type=EVENT(0x05) = 0x25
        return byteArrayOf(0x02, 0x25, payload.size.toByte(), *payload)
    }
}
