package com.nettarion.hyperborea.hardware.fitpro.session

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.DeviceCommand
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
            EquipmentProfiles.S22I,
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

    private fun buildDisconnectAck(): ByteArray {
        val data = byteArrayOf(0x07, 0x04, 0x05)
        return data + V1Codec.checksum(data)
    }

    /**
     * Build a DataResponse packet with known field values.
     * The response after the 4-byte header contains field data in the order
     * of periodicReadFields sorted by fieldIndex.
     */
    private fun buildDataResponsePacket(wattsValue: Int = 100): ByteArray {
        // periodicReadFields sorted by fieldIndex:
        // WATTS(3,2), RPM(5,2), PULSE(10,4), ACTUAL_KPH(16,2), ACTUAL_INCLINE(17,2),
        // RUNNING_TIME(11,4), CURRENT_DISTANCE(4,4), CURRENT_CALORIES(21,4), CURRENT_TIME(20,4),
        // GRADE(1,2), RESISTANCE(2,2), WORKOUT_MODE(12,1)
        // Sorted: GRADE(1), RESISTANCE(2), WATTS(3), CURRENT_DISTANCE(4), RPM(5),
        //         PULSE(10), RUNNING_TIME(11), WORKOUT_MODE(12), ACTUAL_KPH(16),
        //         ACTUAL_INCLINE(17), CURRENT_TIME(20), CURRENT_CALORIES(21)

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
        // RPM(5) - 2 bytes - value 0
        fieldData.addAll(listOf(0, 0))
        // PULSE(10) - 4 bytes - value 0
        fieldData.addAll(listOf(0, 0, 0, 0))
        // RUNNING_TIME(11) - 4 bytes - value 0
        fieldData.addAll(listOf(0, 0, 0, 0))
        // WORKOUT_MODE(12) - 1 byte - value 0
        fieldData.add(0)
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
}
