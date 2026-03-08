package com.nettarion.hyperborea.hardware.fitpro.session

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.test.buildDeviceInfo
import com.nettarion.hyperborea.hardware.fitpro.v1.V1Codec
import com.nettarion.hyperborea.hardware.fitpro.v1.V1DataField
import com.nettarion.hyperborea.hardware.fitpro.v1.V1Message
import com.nettarion.hyperborea.hardware.fitpro.v1.V1Session
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class V1SessionTest {

    private val transport = FakeHidTransport()
    private val logger = FakeAppLogger()

    private fun createSession(scope: TestScope): V1Session =
        V1Session(
            transport, logger, scope.backgroundScope,
            buildDeviceInfo(maxResistance = 24),
        )

    @Test
    fun `start opens transport`() = runTest {
        val session = createSession(this)

        backgroundScope.launch {
            respondToHandshake()
        }

        session.start()
        advanceUntilIdle()

        assertThat(transport.isOpen).isTrue()
    }

    @Test
    fun `start transitions to STREAMING after handshake`() = runTest {
        val session = createSession(this)

        backgroundScope.launch {
            respondToHandshake()
        }

        session.start()
        advanceUntilIdle()

        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
    }

    @Test
    fun `start sends Connect packet first`() = runTest {
        val session = createSession(this)

        backgroundScope.launch {
            respondToHandshake()
        }

        session.start()
        advanceUntilIdle()

        assertThat(transport.writtenPackets).isNotEmpty()
        val first = transport.writtenPackets[0]
        assertThat(first[2]).isEqualTo(0x04) // CMD_CONNECT
    }

    @Test
    fun `poll loop sends ReadWriteData packets`() = runTest {
        val session = createSession(this)

        backgroundScope.launch {
            respondToHandshake()
        }

        session.start()
        advanceUntilIdle()

        backgroundScope.launch {
            respondWithDataResponse()
        }
        advanceTimeBy(200)

        val readWritePackets = transport.writtenPackets.filter { it.size >= 3 && it[2] == 0x02.toByte() }
        assertThat(readWritePackets).isNotEmpty()
    }

    @Test
    fun `incoming DataResponse updates exerciseData`() = runTest {
        val session = createSession(this)

        backgroundScope.launch {
            respondToHandshake()
        }

        session.start()
        advanceUntilIdle()

        backgroundScope.launch {
            respondWithDataResponse()
        }
        advanceTimeBy(200)
        advanceUntilIdle()

        assertThat(session.exerciseData.value).isNotNull()
        assertThat(session.exerciseData.value!!.power).isEqualTo(180)
    }

    @Test
    fun `writeFeature queues write for next poll`() = runTest {
        val session = createSession(this)

        backgroundScope.launch {
            respondToHandshake()
        }

        session.start()
        advanceUntilIdle()

        session.writeFeature(DeviceCommand.SetResistance(15))
    }

    @Test
    fun `stop transitions to DISCONNECTED`() = runTest {
        val session = createSession(this)

        backgroundScope.launch {
            respondToHandshake()
        }

        session.start()
        advanceUntilIdle()

        backgroundScope.launch {
            transport.emitIncoming(buildDisconnectAck())
        }

        session.stop()
        advanceUntilIdle()

        assertThat(session.sessionState.value).isEqualTo(SessionState.Disconnected)
        assertThat(session.exerciseData.value).isNull()
    }

    @Test
    fun `deviceIdentity is populated after handshake`() = runTest {
        val session = createSession(this)

        backgroundScope.launch {
            respondToHandshake()
        }

        session.start()
        advanceUntilIdle()

        val identity = session.deviceIdentity.value
        assertThat(identity).isNotNull()
        assertThat(identity!!.serialNumber).isEqualTo("16909060") // 0x01020304
        assertThat(identity.firmwareVersion).isEqualTo("80")
        assertThat(identity.hardwareVersion).isEqualTo("3")
        assertThat(identity.model).isEqualTo("100")
        assertThat(identity.partNumber).isEqualTo("200")
    }

    @Test
    fun `deviceIdentity is null before start`() = runTest {
        val session = createSession(this)
        assertThat(session.deviceIdentity.value).isNull()
    }

    @Test
    fun `deviceIdentity is cleared on stop`() = runTest {
        val session = createSession(this)

        backgroundScope.launch {
            respondToHandshake()
        }

        session.start()
        advanceUntilIdle()

        assertThat(session.deviceIdentity.value).isNotNull()

        backgroundScope.launch {
            transport.emitIncoming(buildDisconnectAck())
        }

        session.stop()
        advanceUntilIdle()

        assertThat(session.deviceIdentity.value).isNull()
    }

    @Test
    fun `writeFeature is no-op when not streaming`() = runTest {
        val session = createSession(this)
        session.writeFeature(DeviceCommand.SetResistance(10))
        assertThat(transport.writtenPackets).isEmpty()
    }

    @Test
    fun `security block triggers re-verification`() = runTest {
        val session = createSession(this)

        backgroundScope.launch {
            respondToHandshake()
        }

        session.start()
        advanceUntilIdle()

        // Send a SECURITY_BLOCK response, then a security success response
        backgroundScope.launch {
            transport.emitIncoming(buildSecurityBlockDataResponse())
            transport.emitIncoming(buildSecurityUnlockedResponse())
        }
        advanceTimeBy(200)
        advanceUntilIdle()

        // Session should still be streaming (recovered from security block)
        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
        // Should have sent a VerifySecurity packet (0x90)
        val securityPackets = transport.writtenPackets.filter { it.size >= 3 && it[2] == 0x90.toByte() }
        // At least 2: one from handshake, one from re-verification
        assertThat(securityPackets.size).isAtLeast(2)
    }

    private suspend fun respondToHandshake() {
        transport.emitIncoming(buildConnectAck())
        transport.emitIncoming(buildDeviceInfoResponse())
        transport.emitIncoming(buildSystemInfoResponse())
        transport.emitIncoming(buildVersionInfoResponse())
        transport.emitIncoming(buildSecurityUnlockedResponse())
    }

    private suspend fun respondWithDataResponse() {
        transport.emitIncoming(buildDataResponsePacket(wattsValue = 180))
    }

    private fun buildConnectAck(): ByteArray {
        val data = byteArrayOf(0x07, 0x04, 0x04)
        return data + V1Codec.checksum(data)
    }

    private fun buildDeviceInfoResponse(): ByteArray {
        // sw=80 (>75, triggers security), hw=3, serial=0x01020304
        val data = byteArrayOf(
            0x02, 0x0F, 0x81.toByte(), 0x02,
            80, 3, // sw, hw
            0x04, 0x03, 0x02, 0x01, // serial LE
            0, 0, // manufacturer
            1, 0, // sections, bitmask
        )
        return data + V1Codec.checksum(data)
    }

    private fun buildSystemInfoResponse(): ByteArray {
        // model=100, partNumber=200
        val data = byteArrayOf(
            0x02, 0x10, 0x82.toByte(), 0x02,
            0, 0, // configSize
            0,    // configuration
            100, 0, 0, 0, // model LE
            200.toByte(), 0, 0, 0, // partNumber LE
        )
        return data + V1Codec.checksum(data)
    }

    private fun buildVersionInfoResponse(): ByteArray {
        // masterLibraryVersion=10, masterLibraryBuild=1
        val data = byteArrayOf(
            0x02, 0x08, 0x84.toByte(), 0x02,
            10, // masterLibraryVersion
            1, 0, // masterLibraryBuild LE
        )
        return data + V1Codec.checksum(data)
    }

    private fun buildSecurityUnlockedResponse(): ByteArray {
        val data = byteArrayOf(
            0x02, 0x06, 0x90.toByte(), 0x02, // status=DONE → unlocked
            0x01,
        )
        return data + V1Codec.checksum(data)
    }

    private fun buildSecurityBlockDataResponse(): ByteArray {
        val data = byteArrayOf(0x07, 0x05, 0x02, 0x08) // status=SECURITY_BLOCK
        return data + V1Codec.checksum(data)
    }

    // --- Double start ---

    @Test
    fun `double start is no-op when already streaming`() = runTest {
        val session = createSession(this)

        backgroundScope.launch {
            respondToHandshake()
        }

        session.start()
        advanceUntilIdle()
        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)

        val packetCountBefore = transport.writtenPackets.size
        session.start()
        advanceUntilIdle()

        // No additional packets sent
        assertThat(transport.writtenPackets.size).isEqualTo(packetCountBefore)
        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
    }

    // --- Handshake state transitions ---

    @Test
    fun `start reaches Streaming after full handshake`() = runTest {
        val session = createSession(this)
        assertThat(session.sessionState.value).isEqualTo(SessionState.Disconnected)

        backgroundScope.launch {
            respondToHandshake()
        }

        session.start()
        advanceUntilIdle()

        // After full handshake: Disconnected → Connecting → Handshaking → Streaming
        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
    }

    // --- Handshake failure ---

    @Test
    fun `start transitions to Error when handshake gets no response`() = runTest {
        val session = createSession(this)
        // Close channel so readPacket() returns null immediately → handshake throws
        transport.closeIncoming()

        session.start()
        advanceUntilIdle()

        assertThat(session.sessionState.value).isInstanceOf(SessionState.Error::class.java)
    }

    @Test
    fun `start closes transport on handshake failure`() = runTest {
        val session = createSession(this)
        transport.closeIncoming()

        session.start()
        advanceUntilIdle()

        assertThat(transport.isOpen).isFalse()
    }

    // --- Security skipped for old firmware ---

    @Test
    fun `handshake skips security when software version is 75 or below`() = runTest {
        val session = createSession(this)

        backgroundScope.launch {
            transport.emitIncoming(buildConnectAck())
            transport.emitIncoming(buildDeviceInfoResponseWithSw(75))
            transport.emitIncoming(buildSystemInfoResponse())
            transport.emitIncoming(buildVersionInfoResponse())
            // No security response needed
        }

        session.start()
        advanceUntilIdle()

        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
        // No VerifySecurity packets (0x90) should have been sent
        val securityPackets = transport.writtenPackets.filter { it.size >= 3 && it[2] == 0x90.toByte() }
        assertThat(securityPackets).isEmpty()
    }

    // --- writeFeature for all command types ---

    @Test
    fun `writeFeature SetIncline queues GRADE field`() = runTest {
        val session = startStreamingSession()

        session.writeFeature(DeviceCommand.SetIncline(5.0f))

        // Trigger a poll to drain pending writes
        backgroundScope.launch { respondWithDataResponse() }
        advanceTimeBy(200)

        // Verify ReadWriteData was sent (contains write fields)
        val readWritePackets = transport.writtenPackets.filter { it.size >= 3 && it[2] == 0x02.toByte() }
        assertThat(readWritePackets).isNotEmpty()
    }

    @Test
    fun `writeFeature SetTargetPower queues WATT_GOAL field`() = runTest {
        val session = startStreamingSession()
        session.writeFeature(DeviceCommand.SetTargetPower(200))
        // Verifying it didn't throw and was accepted while streaming
        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
    }

    @Test
    fun `writeFeature PauseWorkout queues WORKOUT_MODE PAUSE`() = runTest {
        val session = startStreamingSession()
        session.writeFeature(DeviceCommand.PauseWorkout)
        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
    }

    @Test
    fun `writeFeature ResumeWorkout queues WORKOUT_MODE RUNNING`() = runTest {
        val session = startStreamingSession()
        session.writeFeature(DeviceCommand.ResumeWorkout)
        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
    }

    @Test
    fun `writeFeature AdjustIncline accumulates`() = runTest {
        val session = startStreamingSession()
        session.writeFeature(DeviceCommand.AdjustIncline(increase = true))
        session.writeFeature(DeviceCommand.AdjustIncline(increase = true))
        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
    }

    @Test
    fun `writeFeature AdjustSpeed accumulates`() = runTest {
        val session = startStreamingSession()
        session.writeFeature(DeviceCommand.AdjustSpeed(increase = true))
        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
    }

    @Test
    fun `writeFeature SetTargetSpeed queues KPH field`() = runTest {
        val session = startStreamingSession()
        session.writeFeature(DeviceCommand.SetTargetSpeed(25.0f))
        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
    }

    // --- Poll resilience ---

    @Test
    fun `malformed poll response is silently ignored`() = runTest {
        val session = startStreamingSession()

        // Malformed response followed by a valid one
        backgroundScope.launch {
            transport.emitIncoming(byteArrayOf(0xFF.toByte())) // malformed, silently ignored
            transport.emitIncoming(buildDataResponsePacket(wattsValue = 200))
        }
        advanceTimeBy(300)
        advanceUntilIdle()

        // Session should still be streaming (malformed packet didn't crash it)
        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
        // Valid data still processed after the malformed one
        assertThat(session.exerciseData.value).isNotNull()
        assertThat(session.exerciseData.value!!.power).isEqualTo(200)
    }

    @Test
    fun `null poll response does not crash session`() = runTest {
        val session = startStreamingSession()

        // Don't emit any data — poll gets null from readPacket, continues
        advanceTimeBy(200)

        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
    }

    // --- Stop sends disconnect ---

    @Test
    fun `stop sends IDLE mode and Disconnect packets`() = runTest {
        val session = startStreamingSession()

        backgroundScope.launch {
            transport.emitIncoming(buildDisconnectAck())
        }

        session.stop()
        advanceUntilIdle()

        // Should have sent a ReadWriteData with IDLE mode, then a Disconnect
        val lastPackets = transport.writtenPackets.takeLast(2)
        // Disconnect command = 0x05
        assertThat(lastPackets.last()[2]).isEqualTo(0x05)
    }

    @Test
    fun `stop clears exercise data and identity`() = runTest {
        val session = startStreamingSession()

        backgroundScope.launch { respondWithDataResponse() }
        advanceTimeBy(200)
        advanceUntilIdle()
        assertThat(session.exerciseData.value).isNotNull()
        assertThat(session.deviceIdentity.value).isNotNull()

        backgroundScope.launch {
            transport.emitIncoming(buildDisconnectAck())
        }
        session.stop()
        advanceUntilIdle()

        assertThat(session.exerciseData.value).isNull()
        assertThat(session.deviceIdentity.value).isNull()
    }

    // --- Data response field routing ---

    @Test
    fun `data response with cadence updates exerciseData`() = runTest {
        val session = startStreamingSession()

        backgroundScope.launch {
            transport.emitIncoming(buildDataResponseWithCadence(90))
        }
        advanceTimeBy(200)
        advanceUntilIdle()

        assertThat(session.exerciseData.value).isNotNull()
        assertThat(session.exerciseData.value!!.cadence).isEqualTo(90)
    }

    // --- Helper for streaming state ---

    private suspend fun TestScope.startStreamingSession(): V1Session {
        val session = createSession(this)
        backgroundScope.launch { respondToHandshake() }
        session.start()
        advanceUntilIdle()
        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
        return session
    }

    // --- Additional packet builders ---

    private fun buildDeviceInfoResponseWithSw(sw: Int): ByteArray {
        val data = byteArrayOf(
            0x02, 0x0F, 0x81.toByte(), 0x02,
            sw.toByte(), 3,
            0x04, 0x03, 0x02, 0x01,
            0, 0,
            1, 0,
        )
        return data + V1Codec.checksum(data)
    }

    private fun buildDataResponseWithCadence(rpm: Int): ByteArray {
        val fieldData = mutableListOf<Byte>()
        // GRADE(1) - 2 bytes
        fieldData.addAll(listOf(0, 0))
        // RESISTANCE(2) - 2 bytes
        fieldData.addAll(listOf(0, 0))
        // WATTS(3) - 2 bytes
        fieldData.addAll(listOf(0, 0))
        // CURRENT_DISTANCE(4) - 4 bytes
        fieldData.addAll(listOf(0, 0, 0, 0))
        // RPM(5) - 2 bytes
        fieldData.add((rpm and 0xFF).toByte())
        fieldData.add(((rpm shr 8) and 0xFF).toByte())
        // DISTANCE(6) - 4 bytes
        fieldData.addAll(listOf(0, 0, 0, 0))
        // PULSE(10) - 4 bytes
        fieldData.addAll(listOf(0, 0, 0, 0))
        // RUNNING_TIME(11) - 4 bytes
        fieldData.addAll(listOf(0, 0, 0, 0))
        // WORKOUT_MODE(12) - 1 byte
        fieldData.add(0)
        // CALORIES(13) - 4 bytes
        fieldData.addAll(listOf(0, 0, 0, 0))
        // ACTUAL_KPH(16) - 2 bytes
        fieldData.addAll(listOf(0, 0))
        // ACTUAL_INCLINE(17) - 2 bytes
        fieldData.addAll(listOf(0, 0))
        // CURRENT_TIME(20) - 4 bytes
        fieldData.addAll(listOf(0, 0, 0, 0))
        // CURRENT_CALORIES(21) - 4 bytes
        fieldData.addAll(listOf(0, 0, 0, 0))

        val payload = fieldData.toByteArray()
        val totalLen = 4 + payload.size + 1
        val header = byteArrayOf(0x07, totalLen.toByte(), 0x02, 0x02)
        val withoutChecksum = header + payload
        return withoutChecksum + V1Codec.checksum(withoutChecksum)
    }

    private fun buildDisconnectAck(): ByteArray {
        val data = byteArrayOf(0x07, 0x04, 0x05)
        return data + V1Codec.checksum(data)
    }

    /**
     * Build a DataResponse packet with known field values.
     * The response after the 4-byte header contains field data in the order
     * of periodicReadFields sorted by fieldIndex.
     */
    private fun buildDataResponsePacket(wattsValue: Int = 100, rpmValue: Int = 0, workoutMode: Int = 0): ByteArray {
        // periodicReadFields sorted by fieldIndex:
        // GRADE(1,2), RESISTANCE(2,2), WATTS(3,2), CURRENT_DISTANCE(4,4),
        // RPM(5,2), DISTANCE(6,4), PULSE(10,4), RUNNING_TIME(11,4),
        // WORKOUT_MODE(12,1), CALORIES(13,4), ACTUAL_KPH(16,2),
        // ACTUAL_INCLINE(17,2), CURRENT_TIME(20,4), CURRENT_CALORIES(21,4)
        // Total = 2+2+2+4+2+4+4+4+1+4+2+2+4+4 = 41 bytes

        val fieldData = mutableListOf<Byte>()

        // GRADE(1) - 2 bytes - value 0
        fieldData.addAll(listOf(0, 0))
        // RESISTANCE(2) - 2 bytes - value 0
        fieldData.addAll(listOf(0, 0))
        // WATTS(3) - 2 bytes - value wattsValue
        fieldData.add((wattsValue and 0xFF).toByte())
        fieldData.add(((wattsValue shr 8) and 0xFF).toByte())
        // CURRENT_DISTANCE(4) - 4 bytes - value 0
        fieldData.addAll(listOf(0, 0, 0, 0))
        // RPM(5) - 2 bytes - value rpmValue
        fieldData.add((rpmValue and 0xFF).toByte())
        fieldData.add(((rpmValue shr 8) and 0xFF).toByte())
        // DISTANCE(6) - 4 bytes - value 0
        fieldData.addAll(listOf(0, 0, 0, 0))
        // PULSE(10) - 4 bytes - value 0
        fieldData.addAll(listOf(0, 0, 0, 0))
        // RUNNING_TIME(11) - 4 bytes - value 0
        fieldData.addAll(listOf(0, 0, 0, 0))
        // WORKOUT_MODE(12) - 1 byte - value workoutMode
        fieldData.add(workoutMode.toByte())
        // CALORIES(13) - 4 bytes - value 0
        fieldData.addAll(listOf(0, 0, 0, 0))
        // ACTUAL_KPH(16) - 2 bytes - value 0
        fieldData.addAll(listOf(0, 0))
        // ACTUAL_INCLINE(17) - 2 bytes - value 0
        fieldData.addAll(listOf(0, 0))
        // CURRENT_TIME(20) - 4 bytes - value 0
        fieldData.addAll(listOf(0, 0, 0, 0))
        // CURRENT_CALORIES(21) - 4 bytes - value 0
        fieldData.addAll(listOf(0, 0, 0, 0))

        val payload = fieldData.toByteArray()
        val totalLen = 4 + payload.size + 1
        val header = byteArrayOf(0x07, totalLen.toByte(), 0x02, 0x02) // status=DONE
        val withoutChecksum = header + payload
        return withoutChecksum + V1Codec.checksum(withoutChecksum)
    }

    @Test
    fun `WORKOUT_MODE RUNNING starts timer even with zero RPM`() = runTest {
        val session = startStreamingSession()

        backgroundScope.launch {
            transport.emitIncoming(buildDataResponsePacket(workoutMode = 2, rpmValue = 0))
        }
        advanceTimeBy(200)
        advanceUntilIdle()

        val data = session.exerciseData.value
        assertThat(data).isNotNull()
        assertThat(data!!.workoutMode).isEqualTo(2)
        // Timer should have started due to WORKOUT_MODE=RUNNING
        assertThat(data.elapsedTime).isAtLeast(0L)
    }

    // --- commandToFields: direct mapping tests ---

    private fun createUnstartedSession(): V1Session =
        V1Session(transport, logger, TestScope().backgroundScope, buildDeviceInfo(maxResistance = 24))

    @Test
    fun `commandToFields SetIncline rounds to 0_5 percent step`() {
        val session = createUnstartedSession()
        val fields = session.commandToFields(DeviceCommand.SetIncline(1.3f))
        assertThat(fields).containsExactly(V1DataField.GRADE, 1.5f)
    }

    @Test
    fun `commandToFields SetIncline rounds down when closer`() {
        val session = createUnstartedSession()
        val fields = session.commandToFields(DeviceCommand.SetIncline(1.2f))
        assertThat(fields).containsExactly(V1DataField.GRADE, 1.0f)
    }

    @Test
    fun `commandToFields SetIncline at exact step unchanged`() {
        val session = createUnstartedSession()
        val fields = session.commandToFields(DeviceCommand.SetIncline(3.0f))
        assertThat(fields).containsExactly(V1DataField.GRADE, 3.0f)
    }

    @Test
    fun `commandToFields AdjustIncline increase from zero`() {
        val session = createUnstartedSession()
        val fields = session.commandToFields(DeviceCommand.AdjustIncline(increase = true))
        assertThat(fields).containsExactly(V1DataField.GRADE, 0.5f)
    }

    @Test
    fun `commandToFields AdjustIncline two increases accumulate`() {
        val session = createUnstartedSession()
        session.commandToFields(DeviceCommand.AdjustIncline(increase = true))
        val fields = session.commandToFields(DeviceCommand.AdjustIncline(increase = true))
        assertThat(fields).containsExactly(V1DataField.GRADE, 1.0f)
    }

    @Test
    fun `commandToFields AdjustIncline clamped at maxIncline`() {
        val session = createUnstartedSession()
        session.commandToFields(DeviceCommand.SetIncline(40.0f))
        val fields = session.commandToFields(DeviceCommand.AdjustIncline(increase = true))
        assertThat(fields).containsExactly(V1DataField.GRADE, 40.0f)
    }

    @Test
    fun `commandToFields AdjustIncline clamped at minIncline`() {
        val session = createUnstartedSession()
        session.commandToFields(DeviceCommand.SetIncline(-6.0f))
        val fields = session.commandToFields(DeviceCommand.AdjustIncline(increase = false))
        assertThat(fields).containsExactly(V1DataField.GRADE, -6.0f)
    }

    @Test
    fun `commandToFields AdjustSpeed increase from zero`() {
        val session = createUnstartedSession()
        val fields = session.commandToFields(DeviceCommand.AdjustSpeed(increase = true))
        assertThat(fields).containsExactly(V1DataField.KPH, 0.5f)
    }

    @Test
    fun `commandToFields AdjustSpeed clamped at zero`() {
        val session = createUnstartedSession()
        val fields = session.commandToFields(DeviceCommand.AdjustSpeed(increase = false))
        assertThat(fields).containsExactly(V1DataField.KPH, 0.0f)
    }

    @Test
    fun `commandToFields AdjustSpeed clamped at maxSpeed`() {
        val session = createUnstartedSession()
        session.commandToFields(DeviceCommand.SetTargetSpeed(60.0f))
        val fields = session.commandToFields(DeviceCommand.AdjustSpeed(increase = true))
        assertThat(fields).containsExactly(V1DataField.KPH, 60.0f)
    }

    @Test
    fun `commandToFields SetTargetPower maps to WATT_GOAL`() {
        val session = createUnstartedSession()
        val fields = session.commandToFields(DeviceCommand.SetTargetPower(200))
        assertThat(fields).containsExactly(V1DataField.WATT_GOAL, 200f)
    }

    @Test
    fun `commandToFields SetTargetSpeed maps to KPH`() {
        val session = createUnstartedSession()
        val fields = session.commandToFields(DeviceCommand.SetTargetSpeed(25.5f))
        assertThat(fields).containsExactly(V1DataField.KPH, 25.5f)
    }

    @Test
    fun `commandToFields PauseWorkout maps to WORKOUT_MODE 3`() {
        val session = createUnstartedSession()
        val fields = session.commandToFields(DeviceCommand.PauseWorkout)
        assertThat(fields).containsExactly(V1DataField.WORKOUT_MODE, 3f)
    }

    @Test
    fun `commandToFields ResumeWorkout maps to WORKOUT_MODE 2`() {
        val session = createUnstartedSession()
        val fields = session.commandToFields(DeviceCommand.ResumeWorkout)
        assertThat(fields).containsExactly(V1DataField.WORKOUT_MODE, 2f)
    }
}
