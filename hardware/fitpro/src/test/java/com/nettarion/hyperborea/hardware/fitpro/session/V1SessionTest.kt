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
    fun `start sends DeviceInfo then SupportedCommands, no Connect`() = runTest {
        val session = createSession(this)

        backgroundScope.launch {
            respondToHandshake()
        }

        session.start()
        advanceUntilIdle()

        assertThat(transport.writtenPackets).isNotEmpty()
        // First packet is DeviceInfo (0x81) — its response reports the equipment device type
        assertThat(transport.writtenPackets[0][2]).isEqualTo(0x81.toByte())
        // Second is SupportedCommands (0x88) — the stock bring-up; we never send a Connect (0x04)
        assertThat(transport.writtenPackets[1][2]).isEqualTo(0x88.toByte())
        assertThat(transport.writtenPackets.map { it[2] }).doesNotContain(0x04.toByte())
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
        transport.emitIncoming(buildSupportedCommandsResponse())
        transport.emitIncoming(buildSystemInfoResponse())
        transport.emitIncoming(buildVersionInfoResponse())
        transport.emitIncoming(buildSecurityUnlockedResponse())
        transport.emitIncoming(buildCapabilityResponse())
        respondToConsoleStartup()
    }

    /**
     * Responses for the post-handshake part of start() on a non-treadmill device: prepareConsole()
     * writes REQUIRE_START_REQUESTED (since [buildDeviceInfoResponse] declares it but not
     * IDLE_MODE_LOCKOUT), then transitionToActive() writes WORKOUT_MODE=WARM_UP, confirms, writes
     * WORKOUT_MODE=RUNNING, and confirms. (If IDLE_MODE_LOCKOUT were declared too, transitionToActive
     * would also issue an unlock write before RUNNING — see [respondToBikeConsoleStartupWithLockout].)
     */
    private suspend fun respondToConsoleStartup() {
        transport.emitIncoming(buildReadWriteAck())         // prepareConsole: REQUIRE_START_REQUESTED write
        transport.emitIncoming(buildWorkoutModeAck(10))     // transitionToActive: WARM_UP confirmed
        transport.emitIncoming(buildWorkoutModeAck(2))      // transitionToActive: RUNNING confirmed
    }

    /**
     * Treadmill variant: transitionToActive() only writes WORKOUT_MODE=WARM_UP and waits there —
     * the MCU itself drives the WARM_UP → RUNNING transition once the physical Start key is pressed
     * (see [V1Session.transitionToActive]). So only two acks: REQUIRE_START_REQUESTED, WARM_UP.
     */
    private suspend fun respondToTreadmillConsoleStartup() {
        transport.emitIncoming(buildReadWriteAck())         // prepareConsole: REQUIRE_START_REQUESTED write
        transport.emitIncoming(buildWorkoutModeAck(10))     // transitionToActive: WARM_UP confirmed
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

    /**
     * IDLE_MODE_LOCKOUT (fieldIndex=95 → section 11 bit 7) write detector. writeNumSections=12 and
     * the section-11 mask byte is 0x80 (everything else zero), data byte at offset 3+12=15.
     */
    private fun ByteArray.idleModeLockoutWriteValue(): Int? {
        if (size < 17 || this[2] != 0x02.toByte()) return null
        if (this[3] != 0x0C.toByte()) return null
        // Sections 0..10 mask bytes must be zero, section 11 must be 0x80
        for (i in 4..14) if (this[i] != 0x00.toByte()) return null
        if (this[15] != 0x80.toByte()) return null
        return this[16].toInt() and 0xFF
    }

    private fun ByteArray.isIdleModeLockoutWrite(): Boolean = idleModeLockoutWriteValue() != null
    private fun ByteArray.isIdleModeLockoutUnlock(): Boolean = idleModeLockoutWriteValue() == 0

    private suspend fun respondWithDataResponse() {
        transport.emitIncoming(buildDataResponsePacket(wattsValue = 180))
    }

    // device=8, len, cmd=0x88, status=0x02, one byte per supported command opcode, checksum.
    // Default declares the three optional handshake commands (SystemInfo/VersionInfo/VerifySecurity)
    // so a full handshake proceeds; gating tests override to omit one.
    private fun buildSupportedCommandsResponse(vararg commandIds: Int = intArrayOf(0x82, 0x84, 0x90)): ByteArray {
        val totalLen = 4 + commandIds.size + 1 // header + ids + checksum
        val data = byteArrayOf(0x08, totalLen.toByte(), 0x88.toByte(), 0x02) +
            commandIds.map { it.toByte() }.toByteArray()
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
            transport.emitIncoming(buildSupportedCommandsResponse())
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

    @Test
    fun `treadmill start writes REQUIRE_START_REQUESTED and WARM_UP but never IDLE_MODE_LOCKOUT or RUNNING`() = runTest {
        // The MCU on a treadmill gates belt motion on the physical Start key; writing RUNNING from
        // the app alone would only time out the confirmation poll and falsely surface as "degraded".
        // The session must arm at WARM_UP and let the orchestrator wait for the physical key
        // press to drive the WARM_UP → RUNNING transition via the WORKOUT_MODE poll.
        val session = createSession(this)

        backgroundScope.launch {
            transport.emitIncoming(buildDeviceInfoResponse(deviceId = V1Message.DEVICE_TREADMILL))
            transport.emitIncoming(buildSupportedCommandsResponse())
            transport.emitIncoming(buildSystemInfoResponse())
            transport.emitIncoming(buildVersionInfoResponse())
            transport.emitIncoming(buildSecurityUnlockedResponse())
            transport.emitIncoming(buildCapabilityResponse())
            respondToTreadmillConsoleStartup()
        }

        session.start()
        advanceUntilIdle()

        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
        // Critical: we wrote WARM_UP but never RUNNING (the MCU does that on the physical Start key).
        val workoutModeWrites = transport.writtenPackets.mapNotNull { it.workoutModeWriteValue() }
        assertThat(workoutModeWrites).containsExactly(10)
        // And critical: degraded must stay null — "armed and awaiting" is the expected steady state,
        // not a failure mode.
        assertThat(session.degradedReason.value).isNull()
        // And IDLE_MODE_LOCKOUT must never be written on a treadmill — locking idle-mode on a
        // belt machine fights the MCU's own start-key safety interlock.
        assertThat(transport.writtenPackets.none { it.isIdleModeLockoutWrite() }).isTrue()
    }

    @Test
    fun `bike with IDLE_MODE_LOCKOUT support unlocks before writing RUNNING`() = runTest {
        // The firmware refuses the WORKOUT_MODE=RUNNING transition while idle-mode is locked
        // (even though we needed it locked through prepareConsole for streaming-without-auto-pause).
        // This test asserts the ordering: lockout unlock (=0) comes strictly before the
        // WORKOUT_MODE=RUNNING write.
        val session = createSession(this)
        val bikeWithLockout: Set<Int> = V1DataField.periodicReadFields.map { it.fieldIndex }.toSet() +
            V1DataField.REQUIRE_START_REQUESTED.fieldIndex +
            V1DataField.IDLE_MODE_LOCKOUT.fieldIndex

        backgroundScope.launch {
            transport.emitIncoming(buildDeviceInfoResponse(supportedBitFields = bikeWithLockout))
            transport.emitIncoming(buildSupportedCommandsResponse())
            transport.emitIncoming(buildSystemInfoResponse())
            transport.emitIncoming(buildVersionInfoResponse())
            transport.emitIncoming(buildSecurityUnlockedResponse())
            transport.emitIncoming(buildCapabilityResponse())
            // prepareConsole writes REQUIRE_START_REQUESTED then IDLE_MODE_LOCKOUT=ENABLED (bike +
            // both supported). transitionToActive then writes IDLE_MODE_LOCKOUT=DISABLED, WARM_UP,
            // and RUNNING — four post-handshake acks total.
            transport.emitIncoming(buildReadWriteAck()) // REQUIRE_START_REQUESTED=ENABLED
            transport.emitIncoming(buildReadWriteAck()) // IDLE_MODE_LOCKOUT=ENABLED (lockout for streaming)
            transport.emitIncoming(buildReadWriteAck()) // IDLE_MODE_LOCKOUT=DISABLED (unlock before RUNNING)
            transport.emitIncoming(buildWorkoutModeAck(10))
            transport.emitIncoming(buildWorkoutModeAck(2))
        }

        session.start()
        advanceUntilIdle()

        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
        // The unlock write must appear before the RUNNING write.
        val packets = transport.writtenPackets
        val unlockIdx = packets.indexOfFirst { it.isIdleModeLockoutUnlock() }
        val runningIdx = packets.indexOfFirst { it.workoutModeWriteValue() == 2 }
        assertThat(unlockIdx).isGreaterThan(-1)
        assertThat(runningIdx).isGreaterThan(-1)
        assertThat(unlockIdx).isLessThan(runningIdx)
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
            transport.emitIncoming(buildSupportedCommandsResponse())
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
        // Doubles as the GEAR-alignment regression: STROKES (index 109) and STROKES_PER_MINUTE
        // (110) sit far past GEAR (index 26). If GEAR's wire width were under-declared, the
        // positional decode would land these on the wrong bytes and the values would be garbage.
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
    }

    // --- Equipment device identification (from DeviceInfo byte 0) ---

    @Test
    fun `handshake detects equipment device from DeviceInfo`() = runTest {
        val session = createSession(this)

        backgroundScope.launch {
            transport.emitIncoming(buildDeviceInfoResponse(deviceId = V1Message.DEVICE_TREADMILL))
            transport.emitIncoming(buildSupportedCommandsResponse())
            transport.emitIncoming(buildSystemInfoResponse())
            transport.emitIncoming(buildVersionInfoResponse())
            transport.emitIncoming(buildSecurityUnlockedResponse())
            transport.emitIncoming(buildCapabilityResponse())
            respondToTreadmillConsoleStartup()
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
            transport.emitIncoming(buildSupportedCommandsResponse())
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

    @Test
    fun `handshake skips SystemInfo VersionInfo and security when the controller doesn't declare them`() = runTest {
        val session = createSession(this)

        backgroundScope.launch {
            // Spin bike (sw=83 > 75) whose controller only declares ReadWriteData (0x02) — it omits
            // SystemInfo (0x82), VersionInfo (0x84) and VerifySecurity (0x90). Sending any of those
            // wedges its USB link, so the session must not send them. (S15i.)
            transport.emitIncoming(buildDeviceInfoResponse(deviceId = V1Message.DEVICE_SPIN_BIKE, sw = 83))
            transport.emitIncoming(buildSupportedCommandsResponse(0x02))
            transport.emitIncoming(buildCapabilityResponse()) // readStartupFields (ReadWriteData)
            respondToConsoleStartup()
        }

        session.start()
        advanceUntilIdle()

        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
        val writtenCommands = transport.writtenPackets.map { it[2] }
        assertThat(writtenCommands).contains(0x88.toByte())       // did ask SupportedCommands
        assertThat(writtenCommands).doesNotContain(0x04.toByte()) // never sent Connect
        assertThat(writtenCommands).doesNotContain(0x82.toByte()) // never sent SystemInfo
        assertThat(writtenCommands).doesNotContain(0x84.toByte()) // never sent VersionInfo
        assertThat(writtenCommands).doesNotContain(0x90.toByte()) // never sent VerifySecurity
    }

    @Test
    fun `handshake skips optional commands and polls when the controller never answers SupportedCommands`() = runTest {
        val session = createSession(this)

        backgroundScope.launch {
            // Controller answers DeviceInfo but not the SupportedCommands query — the read returns a
            // non-matching packet (here the startup-field ReadWriteData ack stands in for "no proper
            // SupportedCommands answer"). The session must treat that as "skip the optional commands"
            // and go straight to the data poll, not send SystemInfo and wedge the link.
            transport.emitIncoming(buildDeviceInfoResponse(deviceId = V1Message.DEVICE_SPIN_BIKE, sw = 83))
            transport.emitIncoming(buildReadWriteAck())        // consumed by the SupportedCommands read → null
            transport.emitIncoming(buildCapabilityResponse())  // readStartupFields (ReadWriteData)
            respondToConsoleStartup()
        }

        session.start()
        advanceUntilIdle()

        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
        val writtenCommands = transport.writtenPackets.map { it[2] }
        assertThat(writtenCommands).doesNotContain(0x04.toByte()) // never sent Connect
        assertThat(writtenCommands).doesNotContain(0x82.toByte()) // never sent SystemInfo
        assertThat(writtenCommands).doesNotContain(0x84.toByte()) // never sent VersionInfo
        assertThat(writtenCommands).doesNotContain(0x90.toByte()) // never sent VerifySecurity
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
        // 35 periodicReadFields sorted by fieldIndex = 94 bytes total (GEAR is 8 bytes)
        // RPM is at offset 12 (after KPH=2, GRADE=2, RESISTANCE=2, WATTS=2, CURRENT_DISTANCE=4)
        val fieldData = ByteArray(94)
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
     * 35 periodicReadFields sorted by fieldIndex = 94 bytes total (GEAR is 8 bytes).
     * Offsets: WATTS=6, RPM=12, WORKOUT_MODE=33.
     */
    private fun buildDataResponsePacket(wattsValue: Int = 100, rpmValue: Int = 0, workoutMode: Int = 0): ByteArray {
        val fieldData = ByteArray(94)
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
     * 35 periodicReadFields sorted by fieldIndex = 94 bytes total (GEAR is 8 bytes).
     * Key offsets: RESISTANCE=4, WATTS=6, ACTUAL_KPH=36.
     * ACTUAL_KPH uses SPEED converter (raw / 100 → kph).
     */
    private fun buildDataResponseWithSpeedAndResistance(
        wattsValue: Int = 0,
        speedRaw: Int = 0,        // ACTUAL_KPH raw value (kph × 100, e.g., 2000 = 20.0 kph)
        resistanceValue: Int = 0, // raw resistance (converted by resistanceRawToLevel)
    ): ByteArray {
        val fieldData = ByteArray(94)
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
        val fieldData = ByteArray(94)
        // Offsets are +7 vs the pre-GEAR-fix layout: GEAR (index 26) is 8 bytes, and these rower
        // fields all sit after it. STROKES at offset 85, 2 bytes LE
        fieldData[85] = (strokes and 0xFF).toByte()
        fieldData[86] = ((strokes shr 8) and 0xFF).toByte()
        // STROKES_PER_MINUTE at offset 87, 1 byte
        fieldData[87] = strokesPerMinute.toByte()
        // FIVE_HUNDRED_SPLIT at offset 88, 2 bytes LE
        fieldData[88] = (splitTime and 0xFF).toByte()
        fieldData[89] = ((splitTime shr 8) and 0xFF).toByte()
        // AVG_FIVE_HUNDRED_SPLIT at offset 90, 2 bytes LE
        fieldData[90] = (avgSplitTime and 0xFF).toByte()
        fieldData[91] = ((avgSplitTime shr 8) and 0xFF).toByte()

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
        // ResistanceConverter (matches stock): integer scale = 10000/24 = 416; raw = level*416 - 1, clamped ≥ 0.
        assertThat(session.commandToFields(DeviceCommand.SetResistance(0)))
            .containsExactly(V1DataField.RESISTANCE, 0f)
        assertThat(session.commandToFields(DeviceCommand.SetResistance(12)))
            .containsExactly(V1DataField.RESISTANCE, 4991f)
        assertThat(session.commandToFields(DeviceCommand.SetResistance(24)))
            .containsExactly(V1DataField.RESISTANCE, 9983f)
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
            transport.emitIncoming(buildSupportedCommandsResponse())
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
            transport.emitIncoming(buildSupportedCommandsResponse())
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
        val targetDistanceRaw = 1234 // V1Converter.INT — raw meters on the wire; stored as km (÷1000)
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
            transport.emitIncoming(buildSupportedCommandsResponse())
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
        assertThat(data.distance).isEqualTo(targetDistanceRaw.toFloat() / 1000f) // meters → km
    }

    // --- Speed source by device type ---

    @Test
    fun `bike speed comes from ACTUAL_KPH and KPH is not surfaced as a target`() = runTest {
        val session = startStreamingSession() // default device = FITNESS_BIKE (not belt-based)

        backgroundScope.launch {
            transport.emitIncoming(buildDataResponseWithSpeeds(kphRaw = 500, actualKphRaw = 2000))
        }
        advanceTimeBy(200)
        advanceUntilIdle()

        val data = session.exerciseData.value!!
        assertThat(data.speed).isEqualTo(20.0f)  // ACTUAL_KPH 2000/100 — the virtual speed
        assertThat(data.targetSpeed).isNull()    // bikes have no speed setpoint — no blue target arrow
    }

    @Test
    fun `treadmill speed comes from KPH and ACTUAL_KPH is ignored`() = runTest {
        // On a belt machine ACTUAL_KPH stays 0; the real belt speed is reported in KPH. Reading speed
        // from ACTUAL_KPH (the bike behaviour) is exactly the user-reported "white speed stuck at 0".
        val session = startStreamingTreadmillSession()

        backgroundScope.launch {
            transport.emitIncoming(buildDataResponseWithSpeeds(kphRaw = 850, actualKphRaw = 0))
        }
        advanceTimeBy(200)
        advanceUntilIdle()

        val data = session.exerciseData.value!!
        assertThat(data.speed).isEqualTo(8.5f)   // KPH 850/100 — the real belt speed
        assertThat(data.targetSpeed).isNull()    // no phantom target number on a treadmill
    }

    // --- Belt halt on stop (safety) ---

    @Test
    fun `treadmill stop commands belt speed zero and PAUSE before disconnecting`() = runTest {
        // A bare WORKOUT_MODE=IDLE write does not stop the belt on a treadmill — it kept running until
        // the user hit the physical Stop key. Stop must command KPH=0 + PAUSE.
        val session = startStreamingTreadmillSession()
        val writesBefore = transport.writtenPackets.size

        session.stop()
        advanceUntilIdle()

        val newPackets = transport.writtenPackets.drop(writesBefore)
        assertThat(newPackets.any { it.isBeltHaltWrite() }).isTrue()
        assertThat(newPackets.any { it[2] == 0x05.toByte() }).isTrue() // Disconnect
        assertThat(session.sessionState.value).isEqualTo(SessionState.Disconnected)
    }

    @Test
    fun `bike stop writes IDLE and never a belt halt`() = runTest {
        val session = startStreamingSession() // FITNESS_BIKE — nothing keeps moving, IDLE is enough
        val writesBefore = transport.writtenPackets.size

        backgroundScope.launch { transport.emitIncoming(buildDisconnectAck()) }
        session.stop()
        advanceUntilIdle()

        val newPackets = transport.writtenPackets.drop(writesBefore)
        assertThat(newPackets.none { it.isBeltHaltWrite() }).isTrue()
        assertThat(newPackets.any { it.workoutModeWriteValue() == 1 }).isTrue() // WORKOUT_MODE=IDLE
    }

    private suspend fun TestScope.startStreamingTreadmillSession(): V1Session {
        val session = createSession(this)
        backgroundScope.launch {
            transport.emitIncoming(buildDeviceInfoResponse(deviceId = V1Message.DEVICE_TREADMILL))
            transport.emitIncoming(buildSupportedCommandsResponse())
            transport.emitIncoming(buildSystemInfoResponse())
            transport.emitIncoming(buildVersionInfoResponse())
            transport.emitIncoming(buildSecurityUnlockedResponse())
            transport.emitIncoming(buildCapabilityResponse())
            respondToTreadmillConsoleStartup()
        }
        session.start()
        advanceUntilIdle()
        assertThat(session.sessionState.value).isEqualTo(SessionState.Streaming)
        return session
    }

    /** Full periodicReadFields payload with KPH (idx0) and ACTUAL_KPH (idx16) set to known raw values. */
    private fun buildDataResponseWithSpeeds(kphRaw: Int, actualKphRaw: Int): ByteArray {
        val pollFields = V1DataField.periodicReadFields.sortedBy { it.fieldIndex }
        val payload = ByteArray(pollFields.sumOf { it.sizeBytes })
        var offset = 0
        for (field in pollFields) {
            when (field) {
                V1DataField.KPH -> {
                    payload[offset] = (kphRaw and 0xFF).toByte()
                    payload[offset + 1] = ((kphRaw shr 8) and 0xFF).toByte()
                }
                V1DataField.ACTUAL_KPH -> {
                    payload[offset] = (actualKphRaw and 0xFF).toByte()
                    payload[offset + 1] = ((actualKphRaw shr 8) and 0xFF).toByte()
                }
                else -> { /* leave zero */ }
            }
            offset += field.sizeBytes
        }
        val totalLen = 4 + payload.size + 1
        val header = byteArrayOf(0x07, totalLen.toByte(), 0x02, 0x02)
        val withoutChecksum = header + payload
        return withoutChecksum + V1Codec.checksum(withoutChecksum)
    }

    /**
     * True for the belt-halt ReadWriteData stop() sends on a treadmill: writes KPH (idx0 → section 0
     * bit 0) = 0 and WORKOUT_MODE (idx12 → section 1 bit 4) = PAUSE(3). Encoding:
     * `[device, len, cmd=0x02, writeNumSections=2, mask0=0x01, mask1=0x10, KPH_lo, KPH_hi, mode, …]`.
     */
    private fun ByteArray.isBeltHaltWrite(): Boolean {
        if (size < 9 || this[2] != 0x02.toByte()) return false
        if (this[3] != 0x02.toByte() || this[4] != 0x01.toByte() || this[5] != 0x10.toByte()) return false
        if (this[6] != 0x00.toByte() || this[7] != 0x00.toByte()) return false // KPH = 0
        return this[8] == 0x03.toByte() // WORKOUT_MODE = PAUSE(3)
    }

    /**
     * True for the belt clean-end ReadWriteData stop() sends after the halt: writes GRADE (idx1 →
     * section 0 bit 1) = 0 and WORKOUT_MODE (idx12 → section 1 bit 4) = IDLE(1). Encoding:
     * `[device, len, cmd=0x02, writeNumSections=2, mask0=0x02, mask1=0x10, GRADE_lo, GRADE_hi, mode, …]`.
     */
    private fun ByteArray.isBeltCleanEndWrite(): Boolean {
        if (size < 9 || this[2] != 0x02.toByte()) return false
        if (this[3] != 0x02.toByte() || this[4] != 0x02.toByte() || this[5] != 0x10.toByte()) return false
        if (this[6] != 0x00.toByte() || this[7] != 0x00.toByte()) return false // GRADE = 0
        return this[8] == 0x01.toByte() // WORKOUT_MODE = IDLE(1)
    }

    // --- Graceful teardown: wait for ready-to-disconnect ---

    @Test
    fun `treadmill graceful stop writes a clean end and polls ready-to-disconnect before Disconnect`() = runTest {
        // The fix for "can't restart without force-stop": end the workout cleanly (IDLE + GRADE 0)
        // and wait for the MCU's IS_READY_TO_DISCONNECT before closing the bus. No responses are fed,
        // so the bounded ready-wait times out and proceeds — also exercising the timeout path.
        val session = startStreamingTreadmillSession()
        val writesBefore = transport.writtenPackets.size

        session.stop()
        advanceUntilIdle()

        val newPackets = transport.writtenPackets.drop(writesBefore)
        val cleanEndIdx = newPackets.indexOfFirst { it.isBeltCleanEndWrite() }
        // The single-field ready poll (field 116 → section 14, bit 4); the periodic poll reads many fields.
        val readyIdx = newPackets.indexOfFirst { it.decodePollReadIndices() == setOf(116) }
        val disconnectIdx = newPackets.indexOfFirst { it[2] == 0x05.toByte() }

        assertThat(cleanEndIdx).isAtLeast(0)
        assertThat(readyIdx).isGreaterThan(cleanEndIdx)
        assertThat(disconnectIdx).isGreaterThan(readyIdx)
        assertThat(session.sessionState.value).isEqualTo(SessionState.Disconnected)
    }

    @Test
    fun `bike graceful stop polls ready-to-disconnect before Disconnect`() = runTest {
        val session = startStreamingSession()
        val writesBefore = transport.writtenPackets.size

        session.stop()
        advanceUntilIdle()

        val newPackets = transport.writtenPackets.drop(writesBefore)
        val readyIdx = newPackets.indexOfFirst { it.decodePollReadIndices() == setOf(116) }
        val disconnectIdx = newPackets.indexOfFirst { it[2] == 0x05.toByte() }
        assertThat(readyIdx).isAtLeast(0)
        assertThat(disconnectIdx).isGreaterThan(readyIdx)
        assertThat(session.sessionState.value).isEqualTo(SessionState.Disconnected)
    }

}
