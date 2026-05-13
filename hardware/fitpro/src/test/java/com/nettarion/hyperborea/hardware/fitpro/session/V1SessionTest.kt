package com.nettarion.hyperborea.hardware.fitpro.session

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.test.TestAppLogger
import com.nettarion.hyperborea.core.model.ConsoleKey
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
    fun `start sends DeviceInfo then Connect`() = runTest {
        val session = createSession(this)

        backgroundScope.launch {
            respondToHandshake()
        }

        session.start()
        advanceUntilIdle()

        assertThat(transport.writtenPackets).isNotEmpty()
        // First packet is DeviceInfo (0x81) — its response reports the equipment device type
        assertThat(transport.writtenPackets[0][2]).isEqualTo(0x81.toByte())
        // Second packet is Connect (0x04) to that device
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
        transport.emitIncoming(buildDeviceInfoResponse())
        transport.emitIncoming(buildConnectAck())
        transport.emitIncoming(buildSupportedDevicesResponse())
        transport.emitIncoming(buildSystemInfoResponse())
        transport.emitIncoming(buildVersionInfoResponse())
        transport.emitIncoming(buildSecurityUnlockedResponse())
        transport.emitIncoming(buildCapabilityResponse())
        respondToConsoleStartup()
    }

    /**
     * Responses for the post-handshake part of start(): prepareConsole() writes one field
     * (REQUIRE_START_REQUESTED, since [buildDeviceInfoResponse] declares it), then transitionToRunning()
     * writes WORKOUT_MODE=WARM_UP and confirms, then WORKOUT_MODE=RUNNING and confirms.
     */
    private suspend fun respondToConsoleStartup() {
        transport.emitIncoming(buildReadWriteAck())         // prepareConsole: REQUIRE_START_REQUESTED write
        transport.emitIncoming(buildWorkoutModeAck(10))     // transitionToRunning: WARM_UP confirmed
        transport.emitIncoming(buildWorkoutModeAck(2))      // transitionToRunning: RUNNING confirmed
    }

    /** A minimal ReadWriteData response — status DONE, empty payload (the caller ignores the value). */
    private fun buildReadWriteAck(): ByteArray {
        val data = byteArrayOf(0x07, 0x05, 0x02, 0x02) // device, len, cmd=ReadWriteData, status=DONE
        return data + V1Codec.checksum(data)
    }

    /** A ReadWriteData response carrying a single 1-byte WORKOUT_MODE value. */
    private fun buildWorkoutModeAck(mode: Int): ByteArray {
        val data = byteArrayOf(0x07, 0x06, 0x02, 0x02, mode.toByte())
        return data + V1Codec.checksum(data)
    }

    /**
     * If [this] is an outgoing ReadWriteData packet that writes exactly WORKOUT_MODE (index 12 →
     * section 1, bit 4: writeNumSections=2, writeMask=[0x00, 0x10], then the data byte), return that
     * value; otherwise null. (Encoding: [deviceId, len, cmd=0x02, writeNumSections, writeMask…, data…, readPayload…].)
     */
    private fun ByteArray.workoutModeWriteValue(): Int? {
        if (size < 7 || this[2] != 0x02.toByte()) return null
        if (this[3] != 0x02.toByte() || this[4] != 0x00.toByte() || this[5] != 0x10.toByte()) return null
        return this[6].toInt() and 0xFF
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

    private fun buildDeviceInfoResponse(
        deviceId: Int = V1Message.DEVICE_FITNESS_BIKE,
        sw: Int = 80, // >75 → triggers security
        // Default: a fully-featured device that supports everything we'd ever poll for, plus
        // REQUIRE_START_REQUESTED for prepareConsole. Tests that want to simulate older firmware
        // (e.g., Argon treadmill that omits some fields) override this.
        supportedBitFields: Set<Int> =
            V1DataField.periodicReadFields.map { it.fieldIndex }.toSet() +
                V1DataField.REQUIRE_START_REQUESTED.fieldIndex,
    ): ByteArray {
        // byte0 = the device's own equipment type (the MCU echoes it here); hw=3; serial=0x01020304;
        // then [sectionCount, sectionCount mask bytes] declaring which bitfields the device supports.
        val sectionCount = supportedBitFields.maxOrNull()?.let { it / 8 + 1 } ?: 0
        val mask = ByteArray(sectionCount)
        for (idx in supportedBitFields) mask[idx / 8] = (mask[idx / 8].toInt() or (1 shl (idx % 8))).toByte()
        val body = byteArrayOf(
            deviceId.toByte(), 0, 0x81.toByte(), 0x02, // [1] = length, filled in below
            sw.toByte(), 3, // sw, hw
            0x04, 0x03, 0x02, 0x01, // serial LE
            0, 0, // manufacturer
            sectionCount.toByte(),
        ) + mask
        body[1] = (body.size + 1).toByte() // total length incl. checksum
        return body + V1Codec.checksum(body)
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

    @Test
    fun `start brings the console up through WARM_UP then RUNNING`() = runTest {
        val session = createSession(this)

        backgroundScope.launch { respondToHandshake() }

        session.start()
        advanceUntilIdle()

        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
        // The console-state transition must go IDLE → WARM_UP(10) → RUNNING(2), in that order —
        // not straight to RUNNING (newer console firmware rejects that).
        val workoutModeWrites = transport.writtenPackets.mapNotNull { it.workoutModeWriteValue() }
        assertThat(workoutModeWrites).containsExactly(10, 2).inOrder()
    }

    @Test
    fun `start does not cram REQUIRE_START_REQUESTED or IDLE_MODE_LOCKOUT into the RUNNING packet`() = runTest {
        val session = createSession(this)

        backgroundScope.launch { respondToHandshake() }

        session.start()
        advanceUntilIdle()

        // The WORKOUT_MODE=RUNNING write must be a packet that writes WORKOUT_MODE alone — those
        // init-only fields are written earlier (in IDLE), not alongside the workout transition.
        val runningPacket = transport.writtenPackets.first { it.workoutModeWriteValue() == 2 }
        // A WORKOUT_MODE-only write has writeNumSections=2 (WORKOUT_MODE is in section 1); a packet
        // that also wrote REQUIRE_START_REQUESTED (section 13) would have writeNumSections=14.
        assertThat(runningPacket[3]).isEqualTo(0x02.toByte())
    }

    @Test
    fun `start still reaches Streaming but flags degraded when the console never confirms the workout`() = runTest {
        val session = createSession(this)

        // Feed the handshake responses but NOT the console-startup ones, so the WORKOUT_MODE
        // confirmation reads all time out.
        backgroundScope.launch {
            transport.emitIncoming(buildDeviceInfoResponse())
            transport.emitIncoming(buildConnectAck())
            transport.emitIncoming(buildSupportedDevicesResponse())
            transport.emitIncoming(buildSystemInfoResponse())
            transport.emitIncoming(buildVersionInfoResponse())
            transport.emitIncoming(buildSecurityUnlockedResponse())
            transport.emitIncoming(buildCapabilityResponse())
        }

        session.start()
        advanceUntilIdle()

        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
        assertThat(session.degradedReason.value).isNotNull()
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
            transport.emitIncoming(buildDeviceInfoResponse(sw = 75))
            transport.emitIncoming(buildConnectAck())
            transport.emitIncoming(buildSupportedDevicesResponse())
            transport.emitIncoming(buildSystemInfoResponse())
            transport.emitIncoming(buildVersionInfoResponse())
            // No security response needed
            transport.emitIncoming(buildCapabilityResponse())
            respondToConsoleStartup()
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

    // --- Console keypad (KEY_OBJECT) ---

    @Test
    fun `console keypad press emits one ConsoleKey per fresh press`() = runTest {
        val session = startStreamingSession()
        val received = mutableListOf<ConsoleKey>()
        val collector = backgroundScope.launch { session.consoleKeyPresses.collect { received += it } }
        advanceUntilIdle() // let the collector subscribe before we emit

        // Fresh press: code goes 0 → 9 (a resistance/gear button) → one RESISTANCE_UP
        backgroundScope.launch { transport.emitIncoming(buildDataResponseWithKeyCode(9)) }
        advanceTimeBy(200)
        advanceUntilIdle()
        // Still held (code stays 9) → no further emit
        backgroundScope.launch { transport.emitIncoming(buildDataResponseWithKeyCode(9)) }
        advanceTimeBy(200)
        advanceUntilIdle()
        // Released (0) then pressed again → another RESISTANCE_UP
        backgroundScope.launch { transport.emitIncoming(buildDataResponseWithKeyCode(0)) }
        advanceTimeBy(200)
        advanceUntilIdle()
        backgroundScope.launch { transport.emitIncoming(buildDataResponseWithKeyCode(9)) }
        advanceTimeBy(200)
        advanceUntilIdle()

        collector.cancel()
        assertThat(received).containsExactly(ConsoleKey.RESISTANCE_UP, ConsoleKey.RESISTANCE_UP)
    }

    @Test
    fun `console keypad incline codes map to incline ConsoleKeys`() = runTest {
        val session = startStreamingSession()
        val received = mutableListOf<ConsoleKey>()
        val collector = backgroundScope.launch { session.consoleKeyPresses.collect { received += it } }
        advanceUntilIdle()

        backgroundScope.launch { transport.emitIncoming(buildDataResponseWithKeyCode(5)) } // INCLINE_UP
        advanceTimeBy(200)
        advanceUntilIdle()
        backgroundScope.launch { transport.emitIncoming(buildDataResponseWithKeyCode(0)) }
        advanceTimeBy(200)
        advanceUntilIdle()
        backgroundScope.launch { transport.emitIncoming(buildDataResponseWithKeyCode(6)) } // INCLINE_DOWN
        advanceTimeBy(200)
        advanceUntilIdle()

        collector.cancel()
        assertThat(received).containsExactly(ConsoleKey.INCLINE_UP, ConsoleKey.INCLINE_DOWN)
    }

    @Test
    fun `console keypad ignores unmapped key codes`() = runTest {
        val session = startStreamingSession()
        val received = mutableListOf<ConsoleKey>()
        val collector = backgroundScope.launch { session.consoleKeyPresses.collect { received += it } }
        advanceUntilIdle()

        backgroundScope.launch { transport.emitIncoming(buildDataResponseWithKeyCode(1)) } // STOP — not handled
        advanceTimeBy(200)
        advanceUntilIdle()

        collector.cancel()
        assertThat(received).isEmpty()
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

    // --- Equipment device identification (from DeviceInfo byte 0) ---

    @Test
    fun `handshake detects equipment device from DeviceInfo`() = runTest {
        val session = createSession(this)

        backgroundScope.launch {
            transport.emitIncoming(buildDeviceInfoResponse(deviceId = V1Message.DEVICE_TREADMILL))
            transport.emitIncoming(buildConnectAck())
            transport.emitIncoming(buildSupportedDevicesResponse())
            transport.emitIncoming(buildSystemInfoResponse())
            transport.emitIncoming(buildVersionInfoResponse())
            transport.emitIncoming(buildSecurityUnlockedResponse())
            transport.emitIncoming(buildCapabilityResponse())
            respondToConsoleStartup()
        }

        session.start()
        advanceUntilIdle()

        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
        assertThat(session.capabilities).isNotNull()
        assertThat(session.capabilities!!.equipmentDeviceId).isEqualTo(V1Message.DEVICE_TREADMILL)
    }

    @Test
    fun `handshake defaults to FITNESS_BIKE when DeviceInfo reports an implausible device`() = runTest {
        val session = createSession(this)

        backgroundScope.launch {
            transport.emitIncoming(buildDeviceInfoResponse(deviceId = 0)) // NONE — not a real equipment id
            transport.emitIncoming(buildConnectAck())
            transport.emitIncoming(buildSupportedDevicesResponse())
            transport.emitIncoming(buildSystemInfoResponse())
            transport.emitIncoming(buildVersionInfoResponse())
            transport.emitIncoming(buildSecurityUnlockedResponse())
            transport.emitIncoming(buildCapabilityResponse())
            respondToConsoleStartup()
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
     * Build a DataResponse carrying a KEY_OBJECT code (console keypad state).
     * 35 periodicReadFields sorted by fieldIndex = 87 bytes total; KEY_OBJECT (14 bytes) starts at
     * offset 14 (after KPH=2, GRADE=2, RESISTANCE=2, WATTS=2, CURRENT_DISTANCE=4), `code` is bytes 0-1 LE.
     */
    private fun buildDataResponseWithKeyCode(code: Int): ByteArray {
        val fieldData = ByteArray(87)
        fieldData[14] = (code and 0xFF).toByte()
        fieldData[15] = ((code shr 8) and 0xFF).toByte()

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
    fun `commandToFields SetResistance scales the raw value`() {
        val session = createUnstartedSession() // maxResistance = 24
        // GlassOS ResistanceConverter: scale = 10000/24 ≈ 416.67; raw = round(level*scale) - 1, clamped ≥ 0.
        assertThat(session.commandToFields(DeviceCommand.SetResistance(0)))
            .containsExactly(V1DataField.RESISTANCE, 0f)
        assertThat(session.commandToFields(DeviceCommand.SetResistance(12)))
            .containsExactly(V1DataField.RESISTANCE, 4999f)
        assertThat(session.commandToFields(DeviceCommand.SetResistance(24)))
            .containsExactly(V1DataField.RESISTANCE, 9999f)
    }

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

    // --- Poll-field filtering by supportedBitFields ---

    /**
     * Extracts the set of fieldIndex values encoded in the read bitmask of a poll-loop
     * ReadWriteData packet (one with no pending write fields). Returns null if the packet
     * isn't a write-empty ReadWriteData. Packet layout:
     *   [deviceId, len, cmd=0x02, writeNumSections=0, readNumSections, ...readMasks..., checksum]
     *
     * Note: both the startup capability read and the poll loop produce write-empty
     * ReadWriteData packets. Callers that want the poll loop specifically should pick
     * the last such packet (poll runs after handshake/capability/console-startup).
     */
    private fun ByteArray.decodePollReadIndices(): Set<Int>? {
        if (size < 5 || this[2] != 0x02.toByte()) return null
        val writeNumSections = this[3].toInt() and 0xFF
        if (writeNumSections != 0) return null
        val readNumSections = this[4].toInt() and 0xFF
        if (5 + readNumSections > size) return null
        val result = mutableSetOf<Int>()
        for (section in 0 until readNumSections) {
            val mask = this[5 + section].toInt() and 0xFF
            for (bit in 0..7) if (mask and (1 shl bit) != 0) result.add(section * 8 + bit)
        }
        return result
    }

    @Test
    fun `handshake filters periodicReadFields by supportedBitFields`() = runTest {
        // Argon-treadmill-shaped declaration: supports most of periodicReadFields except
        // the bike-specific session aggregates and the rower-only fields.
        val omittedFields = setOf(
            V1DataField.AVERAGE_WATTS,
            V1DataField.AVERAGE_GRADE,
            V1DataField.STROKES,
            V1DataField.STROKES_PER_MINUTE,
            V1DataField.FIVE_HUNDRED_SPLIT,
            V1DataField.AVG_FIVE_HUNDRED_SPLIT,
        )
        val declaredFields = V1DataField.periodicReadFields - omittedFields
        val supported = declaredFields.map { it.fieldIndex }.toSet() +
            V1DataField.REQUIRE_START_REQUESTED.fieldIndex

        val session = createSession(this)
        backgroundScope.launch {
            transport.emitIncoming(buildDeviceInfoResponse(supportedBitFields = supported))
            transport.emitIncoming(buildConnectAck())
            transport.emitIncoming(buildSupportedDevicesResponse())
            transport.emitIncoming(buildSystemInfoResponse())
            transport.emitIncoming(buildVersionInfoResponse())
            transport.emitIncoming(buildSecurityUnlockedResponse())
            transport.emitIncoming(buildCapabilityResponse())
            respondToConsoleStartup()
        }
        session.start()
        advanceUntilIdle()
        // Nudge the poll loop forward — startPollLoop launches into backgroundScope which
        // advanceUntilIdle doesn't necessarily drain to its first write.
        advanceTimeBy(200)

        // Take the LAST write-empty ReadWriteData — the startup capability read is also write-empty
        // (it asks for MAX_GRADE, MIN_GRADE, …, TOTAL_TIME) and arrives first.
        val pollIndices = transport.writtenPackets.mapNotNull { it.decodePollReadIndices() }.lastOrNull()
        assertThat(pollIndices).isNotNull()

        // The poll should request exactly the declared fields, with the omitted ones gone.
        assertThat(pollIndices!!).containsExactlyElementsIn(declaredFields.map { it.fieldIndex })
        for (omitted in omittedFields) {
            assertThat(pollIndices).doesNotContain(omitted.fieldIndex)
        }
    }

    @Test
    fun `handshake with empty supportedBitFields falls back to full periodicReadFields`() = runTest {
        val session = createSession(this)
        backgroundScope.launch {
            transport.emitIncoming(buildDeviceInfoResponse(supportedBitFields = emptySet()))
            transport.emitIncoming(buildConnectAck())
            transport.emitIncoming(buildSupportedDevicesResponse())
            transport.emitIncoming(buildSystemInfoResponse())
            transport.emitIncoming(buildVersionInfoResponse())
            transport.emitIncoming(buildSecurityUnlockedResponse())
            transport.emitIncoming(buildCapabilityResponse())
            // prepareConsole writes BOTH fields when supportedBitFields is empty.
            transport.emitIncoming(buildReadWriteAck())     // REQUIRE_START_REQUESTED write
            transport.emitIncoming(buildReadWriteAck())     // IDLE_MODE_LOCKOUT write
            transport.emitIncoming(buildWorkoutModeAck(10)) // WARM_UP
            transport.emitIncoming(buildWorkoutModeAck(2))  // RUNNING
        }
        session.start()
        advanceUntilIdle()
        advanceTimeBy(200)

        val pollIndices = transport.writtenPackets.mapNotNull { it.decodePollReadIndices() }.lastOrNull()
        assertThat(pollIndices).isNotNull()
        assertThat(pollIndices!!).containsExactlyElementsIn(
            V1DataField.periodicReadFields.map { it.fieldIndex },
        )
    }

    @Test
    fun `treadmill poll round-trip decodes calories and distance correctly`() = runTest {
        // Drop the rower-only fields plus AVERAGE_WATTS — a plausible treadmill declaration.
        val omittedFields = setOf(
            V1DataField.AVERAGE_WATTS,
            V1DataField.AVERAGE_GRADE,
            V1DataField.STROKES,
            V1DataField.STROKES_PER_MINUTE,
            V1DataField.FIVE_HUNDRED_SPLIT,
            V1DataField.AVG_FIVE_HUNDRED_SPLIT,
        )
        val pollFields = (V1DataField.periodicReadFields - omittedFields).sortedBy { it.fieldIndex }
        val supported = pollFields.map { it.fieldIndex }.toSet() +
            V1DataField.REQUIRE_START_REQUESTED.fieldIndex

        // Build a DataResponse payload sized to the FILTERED set, with known CALORIES + DISTANCE.
        // CaloriesConverter encoding: encoded = calories * 100_000_000 / 1024.
        // The formula is 100_000_000 = 2^8 · 5^8 and 1024 = 2^10, so calories must be a multiple
        // of 4 to round-trip cleanly through the integer-division encode/decode pair.
        val targetCalories = 48
        val targetDistanceRaw = 1234 // V1Converter.INT — raw int passed through to accumulator
        val encodedCalories = (targetCalories.toLong() * 100_000_000L / 1024L).toInt()

        val payloadSize = pollFields.sumOf { it.sizeBytes }
        val payload = ByteArray(payloadSize)
        var offset = 0
        for (field in pollFields) {
            when (field) {
                V1DataField.CURRENT_CALORIES -> {
                    payload[offset]     = (encodedCalories and 0xFF).toByte()
                    payload[offset + 1] = ((encodedCalories shr 8) and 0xFF).toByte()
                    payload[offset + 2] = ((encodedCalories shr 16) and 0xFF).toByte()
                    payload[offset + 3] = ((encodedCalories shr 24) and 0xFF).toByte()
                }
                V1DataField.CURRENT_DISTANCE -> {
                    payload[offset]     = (targetDistanceRaw and 0xFF).toByte()
                    payload[offset + 1] = ((targetDistanceRaw shr 8) and 0xFF).toByte()
                    payload[offset + 2] = ((targetDistanceRaw shr 16) and 0xFF).toByte()
                    payload[offset + 3] = ((targetDistanceRaw shr 24) and 0xFF).toByte()
                }
                else -> { /* leave zero */ }
            }
            offset += field.sizeBytes
        }
        val totalLen = 4 + payload.size + 1
        val header = byteArrayOf(0x07, totalLen.toByte(), 0x02, 0x02)
        val withoutChecksum = header + payload
        val responsePacket = withoutChecksum + V1Codec.checksum(withoutChecksum)

        val session = createSession(this)
        backgroundScope.launch {
            transport.emitIncoming(buildDeviceInfoResponse(supportedBitFields = supported))
            transport.emitIncoming(buildConnectAck())
            transport.emitIncoming(buildSupportedDevicesResponse())
            transport.emitIncoming(buildSystemInfoResponse())
            transport.emitIncoming(buildVersionInfoResponse())
            transport.emitIncoming(buildSecurityUnlockedResponse())
            transport.emitIncoming(buildCapabilityResponse())
            respondToConsoleStartup()
            transport.emitIncoming(responsePacket)
        }
        session.start()
        advanceUntilIdle()
        advanceTimeBy(200)

        val data = session.exerciseData.value
        assertThat(data).isNotNull()
        assertThat(data!!.calories).isEqualTo(targetCalories)
        // The bug we're guarding against produced negative calories, so additionally guard the sign.
        assertThat(data.calories!!).isAtLeast(0)
        assertThat(data.distance).isEqualTo(targetDistanceRaw.toFloat())
    }
}
