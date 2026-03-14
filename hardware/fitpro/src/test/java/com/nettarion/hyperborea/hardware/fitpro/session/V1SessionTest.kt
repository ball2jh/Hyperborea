package com.nettarion.hyperborea.hardware.fitpro.session

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.test.TestAppLogger
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
    private val logger = TestAppLogger()

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
    fun `start sends SupportedDevices then Connect`() = runTest {
        val session = createSession(this)

        backgroundScope.launch {
            respondToHandshake()
        }

        session.start()
        advanceUntilIdle()

        assertThat(transport.writtenPackets).isNotEmpty()
        // First packet is SupportedDevices (0x80)
        assertThat(transport.writtenPackets[0][2]).isEqualTo(0x80.toByte())
        // Second packet is Connect (0x04)
        assertThat(transport.writtenPackets[1][2]).isEqualTo(0x04)
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
        transport.emitIncoming(buildSupportedDevicesResponse())
        transport.emitIncoming(buildConnectAck())
        transport.emitIncoming(buildDeviceInfoResponse())
        transport.emitIncoming(buildSystemInfoResponse())
        transport.emitIncoming(buildVersionInfoResponse())
        transport.emitIncoming(buildSecurityUnlockedResponse())
        transport.emitIncoming(buildCapabilityResponse())
    }

    private suspend fun respondWithDataResponse() {
        transport.emitIncoming(buildDataResponsePacket(wattsValue = 180))
    }

    private fun buildSupportedDevicesResponse(vararg deviceIds: Int = intArrayOf(2, 7, 0x42)): ByteArray {
        // device=2, len=TBD, cmd=0x80, status=0x02, count, deviceIds...
        val count = deviceIds.size
        val totalLen = 4 + 1 + count + 1 // header + count + ids + checksum
        val data = byteArrayOf(
            0x02, totalLen.toByte(), 0x80.toByte(), 0x02,
            count.toByte(),
        ) + deviceIds.map { it.toByte() }.toByteArray()
        return data + V1Codec.checksum(data)
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
            transport.emitIncoming(buildSupportedDevicesResponse())
            transport.emitIncoming(buildConnectAck())
            transport.emitIncoming(buildDeviceInfoResponseWithSw(75))
            transport.emitIncoming(buildSystemInfoResponse())
            transport.emitIncoming(buildVersionInfoResponse())
            // No security response needed
            transport.emitIncoming(buildCapabilityResponse())
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

    @Test
    fun `rower fields in data response populate exerciseData`() = runTest {
        val session = startStreamingSession()

        backgroundScope.launch {
            transport.emitIncoming(buildDataResponseWithRowerFields(
                strokes = 42, strokesPerMinute = 28, splitTime = 120, avgSplitTime = 115,
            ))
        }
        advanceTimeBy(200)
        advanceUntilIdle()

        val data = session.exerciseData.value
        assertThat(data).isNotNull()
        assertThat(data!!.strokeCount).isEqualTo(42)
        assertThat(data.strokeRate).isEqualTo(28)
        assertThat(data.splitTime).isEqualTo(120)
        assertThat(data.avgSplitTime).isEqualTo(115)
    }

    // --- SUPPORTED_DEVICES ---

    @Test
    fun `handshake detects treadmill from supported devices`() = runTest {
        val session = createSession(this)

        backgroundScope.launch {
            transport.emitIncoming(buildSupportedDevicesResponse(2, 4, 0x42)) // MAIN, TREADMILL, GRADE
            transport.emitIncoming(buildConnectAck())
            transport.emitIncoming(buildDeviceInfoResponse())
            transport.emitIncoming(buildSystemInfoResponse())
            transport.emitIncoming(buildVersionInfoResponse())
            transport.emitIncoming(buildSecurityUnlockedResponse())
            transport.emitIncoming(buildCapabilityResponse())
        }

        session.start()
        advanceUntilIdle()

        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
        assertThat(session.capabilities).isNotNull()
        assertThat(session.capabilities!!.equipmentDeviceId).isEqualTo(4)
    }

    @Test
    fun `handshake defaults to FITNESS_BIKE when no equipment device in list`() = runTest {
        val session = createSession(this)

        backgroundScope.launch {
            transport.emitIncoming(buildSupportedDevicesResponse(2, 3, 0x42)) // MAIN, PORTAL, GRADE
            transport.emitIncoming(buildConnectAck())
            transport.emitIncoming(buildDeviceInfoResponse())
            transport.emitIncoming(buildSystemInfoResponse())
            transport.emitIncoming(buildVersionInfoResponse())
            transport.emitIncoming(buildSecurityUnlockedResponse())
            transport.emitIncoming(buildCapabilityResponse())
        }

        session.start()
        advanceUntilIdle()

        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
        assertThat(session.capabilities!!.equipmentDeviceId).isEqualTo(V1Message.DEVICE_FITNESS_BIKE)
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

    private fun buildCapabilityResponse(): ByteArray {
        // Capability fields sorted by fieldIndex:
        // MAX_GRADE(27,2), MIN_GRADE(28,2), MAX_KPH(30,2), MIN_KPH(31,2),
        // MAX_RESISTANCE_LEVEL(42,1), MOTOR_TOTAL_DISTANCE(69,4), TOTAL_TIME(70,4)
        // Total = 17 bytes
        val fieldData = ByteArray(17)
        val totalLen = 4 + fieldData.size + 1
        val header = byteArrayOf(0x08, totalLen.toByte(), 0x02, 0x02) // status=DONE
        val withoutChecksum = header + fieldData
        return withoutChecksum + V1Codec.checksum(withoutChecksum)
    }

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
        // 35 periodicReadFields sorted by fieldIndex = 87 bytes total
        // RPM is at offset 12 (after KPH=2, GRADE=2, RESISTANCE=2, WATTS=2, CURRENT_DISTANCE=4)
        val fieldData = ByteArray(87)
        fieldData[12] = (rpm and 0xFF).toByte()
        fieldData[13] = ((rpm shr 8) and 0xFF).toByte()

        val totalLen = 4 + fieldData.size + 1
        val header = byteArrayOf(0x07, totalLen.toByte(), 0x02, 0x02)
        val withoutChecksum = header + fieldData
        return withoutChecksum + V1Codec.checksum(withoutChecksum)
    }

    private fun buildDisconnectAck(): ByteArray {
        val data = byteArrayOf(0x07, 0x04, 0x05)
        return data + V1Codec.checksum(data)
    }

    /**
     * Build a DataResponse packet with known field values.
     * 35 periodicReadFields sorted by fieldIndex = 87 bytes total.
     * Offsets: WATTS=6, RPM=12, WORKOUT_MODE=33.
     */
    private fun buildDataResponsePacket(wattsValue: Int = 100, rpmValue: Int = 0, workoutMode: Int = 0): ByteArray {
        val fieldData = ByteArray(87)
        // WATTS at offset 6 (after KPH=2, GRADE=2, RESISTANCE=2)
        fieldData[6] = (wattsValue and 0xFF).toByte()
        fieldData[7] = ((wattsValue shr 8) and 0xFF).toByte()
        // RPM at offset 12 (after WATTS=2, CURRENT_DISTANCE=4)
        fieldData[12] = (rpmValue and 0xFF).toByte()
        fieldData[13] = ((rpmValue shr 8) and 0xFF).toByte()
        // WORKOUT_MODE at offset 33 (after KEY_OBJECT=14, VOLUME=1, PULSE=4)
        fieldData[33] = workoutMode.toByte()

        val totalLen = 4 + fieldData.size + 1
        val header = byteArrayOf(0x07, totalLen.toByte(), 0x02, 0x02) // status=DONE
        val withoutChecksum = header + fieldData
        return withoutChecksum + V1Codec.checksum(withoutChecksum)
    }

    /**
     * Build a DataResponse with specific speed and resistance values.
     * 35 periodicReadFields sorted by fieldIndex = 87 bytes total.
     * Key offsets: RESISTANCE=4, WATTS=6, ACTUAL_KPH=36.
     * ACTUAL_KPH uses SPEED converter (raw / 100 → kph).
     */
    private fun buildDataResponseWithSpeedAndResistance(
        wattsValue: Int = 0,
        speedRaw: Int = 0,        // ACTUAL_KPH raw value (kph × 100, e.g., 2000 = 20.0 kph)
        resistanceValue: Int = 0, // raw resistance (converted by resistanceRawToLevel)
    ): ByteArray {
        val fieldData = ByteArray(87)
        // RESISTANCE at offset 4 (after KPH=2, GRADE=2)
        fieldData[4] = (resistanceValue and 0xFF).toByte()
        fieldData[5] = ((resistanceValue shr 8) and 0xFF).toByte()
        // WATTS at offset 6
        fieldData[6] = (wattsValue and 0xFF).toByte()
        fieldData[7] = ((wattsValue shr 8) and 0xFF).toByte()
        // ACTUAL_KPH at offset 36 (after KEY_OBJECT=14B, VOLUME=1, PULSE=4, WORKOUT_MODE=1, LAP_TIME=2)
        fieldData[36] = (speedRaw and 0xFF).toByte()
        fieldData[37] = ((speedRaw shr 8) and 0xFF).toByte()

        val totalLen = 4 + fieldData.size + 1
        val header = byteArrayOf(0x07, totalLen.toByte(), 0x02, 0x02) // status=DONE
        val withoutChecksum = header + fieldData
        return withoutChecksum + V1Codec.checksum(withoutChecksum)
    }

    private fun buildDataResponseWithRowerFields(
        strokes: Int = 0,
        strokesPerMinute: Int = 0,
        splitTime: Int = 0,
        avgSplitTime: Int = 0,
    ): ByteArray {
        val fieldData = ByteArray(87)
        // STROKES at offset 78, 2 bytes LE
        fieldData[78] = (strokes and 0xFF).toByte()
        fieldData[79] = ((strokes shr 8) and 0xFF).toByte()
        // STROKES_PER_MINUTE at offset 80, 1 byte
        fieldData[80] = strokesPerMinute.toByte()
        // FIVE_HUNDRED_SPLIT at offset 81, 2 bytes LE
        fieldData[81] = (splitTime and 0xFF).toByte()
        fieldData[82] = ((splitTime shr 8) and 0xFF).toByte()
        // AVG_FIVE_HUNDRED_SPLIT at offset 83, 2 bytes LE
        fieldData[83] = (avgSplitTime and 0xFF).toByte()
        fieldData[84] = ((avgSplitTime shr 8) and 0xFF).toByte()

        val totalLen = 4 + fieldData.size + 1
        val header = byteArrayOf(0x07, totalLen.toByte(), 0x02, 0x02) // status=DONE
        val withoutChecksum = header + fieldData
        return withoutChecksum + V1Codec.checksum(withoutChecksum)
    }

    // --- Power estimation ---

    @Test
    fun `WATTS greater than zero used as-is without estimation`() = runTest {
        val session = startStreamingSession()

        backgroundScope.launch {
            // WATTS=180 → used directly, no estimation
            transport.emitIncoming(buildDataResponsePacket(wattsValue = 180))
        }
        advanceTimeBy(200)
        advanceUntilIdle()

        assertThat(session.exerciseData.value).isNotNull()
        assertThat(session.exerciseData.value!!.power).isEqualTo(180)
    }

    @Test
    fun `WATTS zero with speed and resistance estimates power`() = runTest {
        val session = startStreamingSession()

        backgroundScope.launch {
            // WATTS=0, speed=20kph (raw=2000), resistance raw=5000 → power estimated via fallback
            transport.emitIncoming(buildDataResponseWithSpeedAndResistance(
                wattsValue = 0, speedRaw = 2000, resistanceValue = 5000,
            ))
        }
        advanceTimeBy(200)
        advanceUntilIdle()

        val data = session.exerciseData.value
        assertThat(data).isNotNull()
        // Estimated power should be > 0 (fallback formula with speed=20kph, resistance~12)
        assertThat(data!!.power).isGreaterThan(0)
    }

    @Test
    fun `WATTS zero with speed zero keeps power at zero`() = runTest {
        val session = startStreamingSession()

        backgroundScope.launch {
            // WATTS=0, speed=0 → estimator returns null, power stays 0
            transport.emitIncoming(buildDataResponseWithSpeedAndResistance(
                wattsValue = 0, speedRaw = 0, resistanceValue = 5000,
            ))
        }
        advanceTimeBy(200)
        advanceUntilIdle()

        val data = session.exerciseData.value
        assertThat(data).isNotNull()
        assertThat(data!!.power).isEqualTo(0)
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
    fun `commandToFields SetTargetPower maps to WATT_GOAL and enables ERG`() {
        val session = createUnstartedSession()
        val fields = session.commandToFields(DeviceCommand.SetTargetPower(200))
        assertThat(fields).containsExactly(
            V1DataField.WATT_GOAL, 200f,
            V1DataField.IS_CONSTANT_WATTS_MODE, 1f,
        )
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

    @Test
    fun `commandToFields SetFanSpeed maps to FAN_STATE`() {
        val session = createUnstartedSession()
        val fields = session.commandToFields(DeviceCommand.SetFanSpeed(3))
        assertThat(fields).containsExactly(V1DataField.FAN_STATE, 3f)
    }

    @Test
    fun `commandToFields SetVolume maps to VOLUME`() {
        val session = createUnstartedSession()
        val fields = session.commandToFields(DeviceCommand.SetVolume(5))
        assertThat(fields).containsExactly(V1DataField.VOLUME, 5f)
    }

    @Test
    fun `commandToFields SetGear maps to GEAR`() {
        val session = createUnstartedSession()
        val fields = session.commandToFields(DeviceCommand.SetGear(3))
        assertThat(fields).containsExactly(V1DataField.GEAR, 3f)
    }

    @Test
    fun `commandToFields SetDistanceGoal maps to DISTANCE_GOAL`() {
        val session = createUnstartedSession()
        val fields = session.commandToFields(DeviceCommand.SetDistanceGoal(5000))
        assertThat(fields).containsExactly(V1DataField.DISTANCE_GOAL, 5000f)
    }

    @Test
    fun `commandToFields SetWarmupTimeout maps to WARMUP_TIMEOUT`() {
        val session = createUnstartedSession()
        val fields = session.commandToFields(DeviceCommand.SetWarmupTimeout(300))
        assertThat(fields).containsExactly(V1DataField.WARMUP_TIMEOUT, 300f)
    }

    @Test
    fun `commandToFields SetCooldownTimeout maps to COOLDOWN_TIMEOUT`() {
        val session = createUnstartedSession()
        val fields = session.commandToFields(DeviceCommand.SetCooldownTimeout(180))
        assertThat(fields).containsExactly(V1DataField.COOLDOWN_TIMEOUT, 180f)
    }

    @Test
    fun `commandToFields SetPauseTimeout maps to PAUSE_TIMEOUT`() {
        val session = createUnstartedSession()
        val fields = session.commandToFields(DeviceCommand.SetPauseTimeout(60))
        assertThat(fields).containsExactly(V1DataField.PAUSE_TIMEOUT, 60f)
    }

    @Test
    fun `commandToFields SetWarmUpMode maps to WORKOUT_MODE 10`() {
        val session = createUnstartedSession()
        val fields = session.commandToFields(DeviceCommand.SetWarmUpMode(true))
        assertThat(fields).containsExactly(V1DataField.WORKOUT_MODE, 10f)
    }

    @Test
    fun `commandToFields SetCoolDownMode maps to WORKOUT_MODE 11`() {
        val session = createUnstartedSession()
        val fields = session.commandToFields(DeviceCommand.SetCoolDownMode(true))
        assertThat(fields).containsExactly(V1DataField.WORKOUT_MODE, 11f)
    }

    @Test
    fun `commandToFields SetErgMode enable maps to IS_CONSTANT_WATTS_MODE 1`() {
        val session = createUnstartedSession()
        val fields = session.commandToFields(DeviceCommand.SetErgMode(true))
        assertThat(fields).containsExactly(V1DataField.IS_CONSTANT_WATTS_MODE, 1f)
    }

    @Test
    fun `commandToFields SetErgMode disable maps to IS_CONSTANT_WATTS_MODE 0`() {
        val session = createUnstartedSession()
        val fields = session.commandToFields(DeviceCommand.SetErgMode(false))
        assertThat(fields).containsExactly(V1DataField.IS_CONSTANT_WATTS_MODE, 0f)
    }
}
