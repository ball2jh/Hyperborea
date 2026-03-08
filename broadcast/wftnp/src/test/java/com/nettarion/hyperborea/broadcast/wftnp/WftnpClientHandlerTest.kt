package com.nettarion.hyperborea.broadcast.wftnp

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.test.buildDeviceInfo
import com.nettarion.hyperborea.core.ftms.ControlPointParser
import com.nettarion.hyperborea.core.ftms.FtmsServiceMetadata
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WftnpClientHandlerTest {

    private val deviceInfo = buildDeviceInfo()
    private val serviceDef = WftnpServiceDefinition(deviceInfo)
    private val commands = mutableListOf<DeviceCommand>()
    private val logger = object : AppLogger {
        override fun d(tag: String, message: String) {}
        override fun i(tag: String, message: String) {}
        override fun w(tag: String, message: String) {}
        override fun e(tag: String, message: String, throwable: Throwable?) {}
    }

    private data class ParsedResponse(val id: Byte, val seq: Byte, val code: Byte, val payload: ByteArray)

    private fun parseResponses(bytes: ByteArray): List<ParsedResponse> {
        val responses = mutableListOf<ParsedResponse>()
        var offset = 0
        while (offset + WftnpCodec.HEADER_SIZE <= bytes.size) {
            val length = ((bytes[offset + 4].toInt() and 0xFF) shl 8) or (bytes[offset + 5].toInt() and 0xFF)
            if (offset + WftnpCodec.HEADER_SIZE + length > bytes.size) break
            val payload = bytes.copyOfRange(offset + WftnpCodec.HEADER_SIZE, offset + WftnpCodec.HEADER_SIZE + length)
            responses.add(ParsedResponse(bytes[offset + 1], bytes[offset + 2], bytes[offset + 3], payload))
            offset += WftnpCodec.HEADER_SIZE + length
        }
        return responses
    }

    @Test
    fun `discover services returns FTMS and CPS`() = runTest {
        val request = WftnpCodec.encodeResponse(WftnpCodec.ID_DISCOVER_SERVICES, 0x01, 0x00)
        val input = ByteArrayInputStream(request)
        val output = ByteArrayOutputStream()
        val handler = WftnpClientHandler(
            clientId = "test",
            input = input,
            output = output,
            deviceType = DeviceType.BIKE,
            serviceDef = serviceDef,
            scope = this,
            onCommand = { commands.add(it) },
            logger = logger,
        )

        handler.runReadLoop()

        val responses = parseResponses(output.toByteArray())
        assertThat(responses).hasSize(1)
        val resp = responses[0]
        assertThat(resp.id).isEqualTo(WftnpCodec.ID_DISCOVER_SERVICES)
        assertThat(resp.code).isEqualTo(WftnpCodec.RESP_SUCCESS)
        assertThat(resp.payload.size).isEqualTo(32)

        val ftmsUuid = WftnpCodec.decodeShortUuid(resp.payload, 0)
        val cpsUuid = WftnpCodec.decodeShortUuid(resp.payload, 16)
        assertThat(ftmsUuid).isEqualTo(ShortUuid(0x1826))
        assertThat(cpsUuid).isEqualTo(ShortUuid(0x1818))
    }

    @Test
    fun `discover characteristics for FTMS service`() = runTest {
        val uuidBlob = WftnpCodec.encodeUuidBlob(ShortUuid(0x1826))
        val request = WftnpCodec.encodeResponse(WftnpCodec.ID_DISCOVER_CHARACTERISTICS, 0x01, 0x00, uuidBlob)
        val input = ByteArrayInputStream(request)
        val output = ByteArrayOutputStream()
        val handler = WftnpClientHandler(
            clientId = "test",
            input = input,
            output = output,
            deviceType = DeviceType.BIKE,
            serviceDef = serviceDef,
            scope = this,
            onCommand = { commands.add(it) },
            logger = logger,
        )

        handler.runReadLoop()

        val responses = parseResponses(output.toByteArray())
        assertThat(responses).hasSize(1)
        val resp = responses[0]
        assertThat(resp.id).isEqualTo(WftnpCodec.ID_DISCOVER_CHARACTERISTICS)
        assertThat(resp.code).isEqualTo(WftnpCodec.RESP_SUCCESS)
        // 16 (service blob) + 8 * 17 (char entries) = 152
        assertThat(resp.payload.size).isEqualTo(152)
    }

    @Test
    fun `discover characteristics for unknown service`() = runTest {
        val uuidBlob = WftnpCodec.encodeUuidBlob(ShortUuid(0x9999))
        val request = WftnpCodec.encodeResponse(WftnpCodec.ID_DISCOVER_CHARACTERISTICS, 0x01, 0x00, uuidBlob)
        val input = ByteArrayInputStream(request)
        val output = ByteArrayOutputStream()
        val handler = WftnpClientHandler(
            clientId = "test",
            input = input,
            output = output,
            deviceType = DeviceType.BIKE,
            serviceDef = serviceDef,
            scope = this,
            onCommand = { commands.add(it) },
            logger = logger,
        )

        handler.runReadLoop()

        val responses = parseResponses(output.toByteArray())
        assertThat(responses).hasSize(1)
        assertThat(responses[0].code).isEqualTo(WftnpCodec.RESP_SERVICE_NOT_FOUND)
    }

    @Test
    fun `read FTMS Feature characteristic`() = runTest {
        val uuidBlob = WftnpCodec.encodeUuidBlob(ShortUuid(0x2ACC))
        val request = WftnpCodec.encodeResponse(WftnpCodec.ID_READ_CHARACTERISTIC, 0x01, 0x00, uuidBlob)
        val input = ByteArrayInputStream(request)
        val output = ByteArrayOutputStream()
        val handler = WftnpClientHandler(
            clientId = "test",
            input = input,
            output = output,
            deviceType = DeviceType.BIKE,
            serviceDef = serviceDef,
            scope = this,
            onCommand = { commands.add(it) },
            logger = logger,
        )

        handler.runReadLoop()

        val responses = parseResponses(output.toByteArray())
        assertThat(responses).hasSize(1)
        val resp = responses[0]
        assertThat(resp.id).isEqualTo(WftnpCodec.ID_READ_CHARACTERISTIC)
        assertThat(resp.code).isEqualTo(WftnpCodec.RESP_SUCCESS)
        // 16 (UUID blob) + 8 (FTMS feature value) = 24
        assertThat(resp.payload.size).isEqualTo(24)
        val expectedFeature = FtmsServiceMetadata.ftmsFeatureValue(DeviceType.BIKE)
        val actualFeature = resp.payload.copyOfRange(16, 24)
        assertThat(actualFeature).isEqualTo(expectedFeature)
    }

    @Test
    fun `read unknown characteristic`() = runTest {
        val uuidBlob = WftnpCodec.encodeUuidBlob(ShortUuid(0x9999))
        val request = WftnpCodec.encodeResponse(WftnpCodec.ID_READ_CHARACTERISTIC, 0x01, 0x00, uuidBlob)
        val input = ByteArrayInputStream(request)
        val output = ByteArrayOutputStream()
        val handler = WftnpClientHandler(
            clientId = "test",
            input = input,
            output = output,
            deviceType = DeviceType.BIKE,
            serviceDef = serviceDef,
            scope = this,
            onCommand = { commands.add(it) },
            logger = logger,
        )

        handler.runReadLoop()

        val responses = parseResponses(output.toByteArray())
        assertThat(responses).hasSize(1)
        assertThat(responses[0].code).isEqualTo(WftnpCodec.RESP_CHAR_NOT_FOUND)
    }

    @Test
    fun `write FTMS CP emits resistance command`() = runTest {
        val uuidBlob = WftnpCodec.encodeUuidBlob(ShortUuid(0x2AD9))
        val writeValue = byteArrayOf(0x04, 0x64, 0x00)
        val payload = uuidBlob + writeValue
        val request = WftnpCodec.encodeResponse(WftnpCodec.ID_WRITE_CHARACTERISTIC, 0x01, 0x00, payload)
        val input = ByteArrayInputStream(request)
        val output = ByteArrayOutputStream()
        val handler = WftnpClientHandler(
            clientId = "test",
            input = input,
            output = output,
            deviceType = DeviceType.BIKE,
            serviceDef = serviceDef,
            scope = this,
            onCommand = { commands.add(it) },
            logger = logger,
        )

        handler.runReadLoop()

        assertThat(commands).containsExactly(DeviceCommand.SetResistance(10))
    }

    @Test
    fun `write FTMS CP sends write response and CP indication`() = runTest {
        val uuidBlob = WftnpCodec.encodeUuidBlob(ShortUuid(0x2AD9))
        val writeValue = byteArrayOf(0x04, 0x64, 0x00)
        val payload = uuidBlob + writeValue
        val request = WftnpCodec.encodeResponse(WftnpCodec.ID_WRITE_CHARACTERISTIC, 0x01, 0x00, payload)
        val input = ByteArrayInputStream(request)
        val output = ByteArrayOutputStream()
        val handler = WftnpClientHandler(
            clientId = "test",
            input = input,
            output = output,
            deviceType = DeviceType.BIKE,
            serviceDef = serviceDef,
            scope = this,
            onCommand = { commands.add(it) },
            logger = logger,
        )

        handler.runReadLoop()

        val responses = parseResponses(output.toByteArray())
        assertThat(responses).hasSize(2)

        // First: write success
        assertThat(responses[0].id).isEqualTo(WftnpCodec.ID_WRITE_CHARACTERISTIC)
        assertThat(responses[0].code).isEqualTo(WftnpCodec.RESP_SUCCESS)

        // Second: CP indication as notification
        assertThat(responses[1].id).isEqualTo(WftnpCodec.ID_NOTIFICATION)
        val notifPayload = responses[1].payload
        val notifUuid = WftnpCodec.decodeShortUuid(notifPayload, 0)
        assertThat(notifUuid).isEqualTo(ShortUuid(0x2AD9))
        val cpResponse = notifPayload.copyOfRange(16, notifPayload.size)
        assertThat(cpResponse).isEqualTo(byteArrayOf(0x80.toByte(), 0x04, 0x01))
    }

    @Test
    fun `write Wahoo control emits ERG command`() = runTest {
        val uuidBlob = WftnpCodec.encodeUuidBlob(ShortUuid(0xE005))
        val writeValue = byteArrayOf(0x42, 0xC8.toByte(), 0x00)
        val payload = uuidBlob + writeValue
        val request = WftnpCodec.encodeResponse(WftnpCodec.ID_WRITE_CHARACTERISTIC, 0x01, 0x00, payload)
        val input = ByteArrayInputStream(request)
        val output = ByteArrayOutputStream()
        val handler = WftnpClientHandler(
            clientId = "test",
            input = input,
            output = output,
            deviceType = DeviceType.BIKE,
            serviceDef = serviceDef,
            scope = this,
            onCommand = { commands.add(it) },
            logger = logger,
        )

        handler.runReadLoop()

        assertThat(commands).containsExactly(DeviceCommand.SetTargetPower(200))

        // Only write success, no CP indication for Wahoo
        val responses = parseResponses(output.toByteArray())
        assertThat(responses).hasSize(1)
        assertThat(responses[0].id).isEqualTo(WftnpCodec.ID_WRITE_CHARACTERISTIC)
        assertThat(responses[0].code).isEqualTo(WftnpCodec.RESP_SUCCESS)
    }

    @Test
    fun `write to non-writable characteristic`() = runTest {
        val uuidBlob = WftnpCodec.encodeUuidBlob(ShortUuid(0x2ACC))
        val writeValue = byteArrayOf(0x01, 0x02)
        val payload = uuidBlob + writeValue
        val request = WftnpCodec.encodeResponse(WftnpCodec.ID_WRITE_CHARACTERISTIC, 0x01, 0x00, payload)
        val input = ByteArrayInputStream(request)
        val output = ByteArrayOutputStream()
        val handler = WftnpClientHandler(
            clientId = "test",
            input = input,
            output = output,
            deviceType = DeviceType.BIKE,
            serviceDef = serviceDef,
            scope = this,
            onCommand = { commands.add(it) },
            logger = logger,
        )

        handler.runReadLoop()

        val responses = parseResponses(output.toByteArray())
        assertThat(responses).hasSize(1)
        assertThat(responses[0].code).isEqualTo(WftnpCodec.RESP_OP_NOT_SUPPORTED)
        assertThat(commands).isEmpty()
    }

    @Test
    fun `enable notifications tracks subscription`() = runTest {
        val dataChar = serviceDef.dataCharacteristic
        val uuidBlob = WftnpCodec.encodeUuidBlob(dataChar)
        val payload = uuidBlob + byteArrayOf(0x01)
        val request = WftnpCodec.encodeResponse(WftnpCodec.ID_ENABLE_NOTIFICATIONS, 0x01, 0x00, payload)
        val input = ByteArrayInputStream(request)
        val output = ByteArrayOutputStream()
        val handler = WftnpClientHandler(
            clientId = "test",
            input = input,
            output = output,
            deviceType = DeviceType.BIKE,
            serviceDef = serviceDef,
            scope = this,
            onCommand = { commands.add(it) },
            logger = logger,
        )

        handler.runReadLoop()

        assertThat(handler.enabledNotifications).contains(dataChar)
    }

    @Test
    fun `disable notifications removes subscription`() = runTest {
        val dataChar = serviceDef.dataCharacteristic
        val uuidBlob = WftnpCodec.encodeUuidBlob(dataChar)
        val enablePayload = uuidBlob + byteArrayOf(0x01)
        val disablePayload = uuidBlob + byteArrayOf(0x00)
        val enableRequest = WftnpCodec.encodeResponse(WftnpCodec.ID_ENABLE_NOTIFICATIONS, 0x01, 0x00, enablePayload)
        val disableRequest = WftnpCodec.encodeResponse(WftnpCodec.ID_ENABLE_NOTIFICATIONS, 0x02, 0x00, disablePayload)
        val input = ByteArrayInputStream(enableRequest + disableRequest)
        val output = ByteArrayOutputStream()
        val handler = WftnpClientHandler(
            clientId = "test",
            input = input,
            output = output,
            deviceType = DeviceType.BIKE,
            serviceDef = serviceDef,
            scope = this,
            onCommand = { commands.add(it) },
            logger = logger,
        )

        handler.runReadLoop()

        assertThat(handler.enabledNotifications).doesNotContain(dataChar)
    }

    // --- Bug-catching tests below ---

    @Test
    fun `session control Request Control sends SUCCESS indication without emitting command`() = runTest {
        // Zwift sends opcode 0x00 (Request Control) before any real commands.
        // If this emits a DeviceCommand, the hardware gets unexpected instructions.
        // If the indication has NOT_SUPPORTED instead of SUCCESS, Zwift stops sending commands.
        val uuidBlob = WftnpCodec.encodeUuidBlob(WftnpServiceDefinition.FTMS_CONTROL_POINT)
        val payload = uuidBlob + byteArrayOf(0x00) // Request Control
        val request = WftnpCodec.encodeResponse(WftnpCodec.ID_WRITE_CHARACTERISTIC, 0x01, 0x00, payload)
        val input = ByteArrayInputStream(request)
        val output = ByteArrayOutputStream()
        val handler = WftnpClientHandler(
            clientId = "test",
            input = input,
            output = output,
            deviceType = DeviceType.BIKE,
            serviceDef = serviceDef,
            scope = this,
            onCommand = { commands.add(it) },
            logger = logger,
        )

        handler.runReadLoop()

        assertThat(commands).isEmpty()
        val responses = parseResponses(output.toByteArray())
        assertThat(responses).hasSize(2)
        // CP indication must have RESULT_SUCCESS (0x01), not RESULT_NOT_SUPPORTED
        val cpIndication = responses[1].payload.copyOfRange(16, responses[1].payload.size)
        assertThat(cpIndication).isEqualTo(byteArrayOf(0x80.toByte(), 0x00, ControlPointParser.RESULT_SUCCESS))
    }

    @Test
    fun `unsupported opcode sends NOT_SUPPORTED in CP indication`() = runTest {
        // If someone flips the Unsupported/else branches, every indication has the wrong result code.
        val uuidBlob = WftnpCodec.encodeUuidBlob(WftnpServiceDefinition.FTMS_CONTROL_POINT)
        val payload = uuidBlob + byteArrayOf(0xFF.toByte()) // unknown opcode
        val request = WftnpCodec.encodeResponse(WftnpCodec.ID_WRITE_CHARACTERISTIC, 0x01, 0x00, payload)
        val input = ByteArrayInputStream(request)
        val output = ByteArrayOutputStream()
        val handler = WftnpClientHandler(
            clientId = "test",
            input = input,
            output = output,
            deviceType = DeviceType.BIKE,
            serviceDef = serviceDef,
            scope = this,
            onCommand = { commands.add(it) },
            logger = logger,
        )

        handler.runReadLoop()

        assertThat(commands).isEmpty()
        val responses = parseResponses(output.toByteArray())
        val cpIndication = responses[1].payload.copyOfRange(16, responses[1].payload.size)
        assertThat(cpIndication[2]).isEqualTo(ControlPointParser.RESULT_NOT_SUPPORTED)
    }

    @Test
    fun `sequence numbers echoed correctly across multiple requests`() = runTest {
        // Zwift matches responses by sequence number. A bug that hardcodes or
        // corrupts seq numbers would cause Zwift to ignore responses.
        val req1 = WftnpCodec.encodeResponse(WftnpCodec.ID_DISCOVER_SERVICES, 0x0A, 0x00)
        val uuidBlob = WftnpCodec.encodeUuidBlob(ShortUuid(0x2ACC))
        val req2 = WftnpCodec.encodeResponse(WftnpCodec.ID_READ_CHARACTERISTIC, 0x0B, 0x00, uuidBlob)
        val enablePayload = WftnpCodec.encodeUuidBlob(serviceDef.dataCharacteristic) + byteArrayOf(0x01)
        val req3 = WftnpCodec.encodeResponse(WftnpCodec.ID_ENABLE_NOTIFICATIONS, 0x0C, 0x00, enablePayload)

        val input = ByteArrayInputStream(req1 + req2 + req3)
        val output = ByteArrayOutputStream()
        val handler = WftnpClientHandler(
            clientId = "test",
            input = input,
            output = output,
            deviceType = DeviceType.BIKE,
            serviceDef = serviceDef,
            scope = this,
            onCommand = { commands.add(it) },
            logger = logger,
        )

        handler.runReadLoop()

        val responses = parseResponses(output.toByteArray())
        assertThat(responses).hasSize(3)
        assertThat(responses[0].seq).isEqualTo(0x0A.toByte())
        assertThat(responses[1].seq).isEqualTo(0x0B.toByte())
        assertThat(responses[2].seq).isEqualTo(0x0C.toByte())
    }

    @Test
    fun `indoor bike simulation 0x11 emits correct incline through full pipeline`() = runTest {
        // Grade is at bytes 3-4 (not 1-2). A byte offset bug would produce wrong incline.
        // 5% grade = 500 (0x01F4) in sint16 LE at 0.01 resolution
        val uuidBlob = WftnpCodec.encodeUuidBlob(WftnpServiceDefinition.FTMS_CONTROL_POINT)
        val simParams = byteArrayOf(
            0x11,                   // opcode: Indoor Bike Simulation
            0x00, 0x00,             // wind speed (ignored)
            0xF4.toByte(), 0x01,    // grade = 500 = 5.0%
            0x00,                   // crw (ignored)
            0x00,                   // cw (ignored)
        )
        val payload = uuidBlob + simParams
        val request = WftnpCodec.encodeResponse(WftnpCodec.ID_WRITE_CHARACTERISTIC, 0x01, 0x00, payload)
        val input = ByteArrayInputStream(request)
        val output = ByteArrayOutputStream()
        val handler = WftnpClientHandler(
            clientId = "test",
            input = input,
            output = output,
            deviceType = DeviceType.BIKE,
            serviceDef = serviceDef,
            scope = this,
            onCommand = { commands.add(it) },
            logger = logger,
        )

        handler.runReadLoop()

        assertThat(commands).hasSize(1)
        val cmd = commands[0] as DeviceCommand.SetIncline
        assertThat(cmd.percent).isEqualTo(5.0f)
    }

    @Test
    fun `enable notifications on FTMS CP succeeds because INDICATE flag is notifiable`() = runTest {
        // FTMS CP has WRITE|INDICATE flags. If isNotifiable() only checks NOTIFY
        // and not INDICATE, this fails — breaking CP indications for Zwift.
        val cpUuid = WftnpServiceDefinition.FTMS_CONTROL_POINT
        val payload = WftnpCodec.encodeUuidBlob(cpUuid) + byteArrayOf(0x01)
        val request = WftnpCodec.encodeResponse(WftnpCodec.ID_ENABLE_NOTIFICATIONS, 0x01, 0x00, payload)
        val input = ByteArrayInputStream(request)
        val output = ByteArrayOutputStream()
        val handler = WftnpClientHandler(
            clientId = "test",
            input = input,
            output = output,
            deviceType = DeviceType.BIKE,
            serviceDef = serviceDef,
            scope = this,
            onCommand = { commands.add(it) },
            logger = logger,
        )

        handler.runReadLoop()

        assertThat(handler.enabledNotifications).contains(cpUuid)
        val responses = parseResponses(output.toByteArray())
        assertThat(responses[0].code).isEqualTo(WftnpCodec.RESP_SUCCESS)
    }

    @Test
    fun `discover characteristics for CPS service returns 3 characteristics`() = runTest {
        // CPS service has 3 chars. If someone adds/removes one, the payload size changes.
        val uuidBlob = WftnpCodec.encodeUuidBlob(ShortUuid(0x1818))
        val request = WftnpCodec.encodeResponse(WftnpCodec.ID_DISCOVER_CHARACTERISTICS, 0x01, 0x00, uuidBlob)
        val input = ByteArrayInputStream(request)
        val output = ByteArrayOutputStream()
        val handler = WftnpClientHandler(
            clientId = "test",
            input = input,
            output = output,
            deviceType = DeviceType.BIKE,
            serviceDef = serviceDef,
            scope = this,
            onCommand = { commands.add(it) },
            logger = logger,
        )

        handler.runReadLoop()

        val responses = parseResponses(output.toByteArray())
        assertThat(responses[0].code).isEqualTo(WftnpCodec.RESP_SUCCESS)
        // 16 (service blob) + 3 * 17 (char entries) = 67
        assertThat(responses[0].payload.size).isEqualTo(67)
    }

    @Test
    fun `enable notifications on non-notifiable characteristic is rejected`() = runTest {
        // FTMS Feature (0x2ACC) is READ-only. If isNotifiable doesn't check properly,
        // the handler would accept notifications for non-notifiable chars.
        val payload = WftnpCodec.encodeUuidBlob(ShortUuid(0x2ACC)) + byteArrayOf(0x01)
        val request = WftnpCodec.encodeResponse(WftnpCodec.ID_ENABLE_NOTIFICATIONS, 0x01, 0x00, payload)
        val input = ByteArrayInputStream(request)
        val output = ByteArrayOutputStream()
        val handler = WftnpClientHandler(
            clientId = "test",
            input = input,
            output = output,
            deviceType = DeviceType.BIKE,
            serviceDef = serviceDef,
            scope = this,
            onCommand = { commands.add(it) },
            logger = logger,
        )

        handler.runReadLoop()

        assertThat(handler.enabledNotifications).isEmpty()
        val responses = parseResponses(output.toByteArray())
        assertThat(responses[0].code).isEqualTo(WftnpCodec.RESP_CHAR_NOT_FOUND)
    }
}
