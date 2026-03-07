package com.nettarion.hyperborea.broadcast.wftnp

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.ControlPointParser
import com.nettarion.hyperborea.core.DeviceCommand
import com.nettarion.hyperborea.core.DeviceType
import com.nettarion.hyperborea.core.ExerciseData
import com.nettarion.hyperborea.core.FtmsDataEncoder
import com.nettarion.hyperborea.core.RevolutionCounter
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class WftnpClientHandler(
    val clientId: String,
    private val input: InputStream,
    private val output: OutputStream,
    private val deviceType: DeviceType,
    private val serviceDef: WftnpServiceDefinition,
    private val onCommand: (DeviceCommand) -> Unit,
    private val logger: AppLogger,
) {
    private val enabledNotifications: MutableSet<ShortUuid> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    private val writeMutex = Mutex()
    @Volatile var isClosed = false
        private set

    private val revCounter = RevolutionCounter()

    suspend fun runReadLoop() = withContext(Dispatchers.IO) {
        try {
            while (!isClosed) {
                val request = WftnpCodec.readRequest(input) ?: break
                handleRequest(request)
            }
        } catch (e: Exception) {
            if (!isClosed) {
                logger.d(TAG, "Client $clientId read error: ${e.message}")
            }
        } finally {
            isClosed = true
        }
    }

    suspend fun sendNotifications(data: ExerciseData) {
        if (isClosed) return
        val now = System.currentTimeMillis()

        if (enabledNotifications.contains(serviceDef.dataCharacteristic)) {
            val value = FtmsDataEncoder.encodeData(deviceType, data)
            send(WftnpCodec.encodeNotification(serviceDef.dataCharacteristic, value))
        }

        if (enabledNotifications.contains(WftnpServiceDefinition.CPS_MEASUREMENT)) {
            revCounter.update(data, now)
            val value = FtmsDataEncoder.encodeCpsMeasurement(
                data,
                revCounter.cumulativeWheelRevs,
                revCounter.lastWheelEventTime,
                revCounter.cumulativeCrankRevs,
                revCounter.lastCrankEventTime,
            )
            send(WftnpCodec.encodeNotification(WftnpServiceDefinition.CPS_MEASUREMENT, value))
        }
    }

    fun close() {
        isClosed = true
        runCatching { input.close() }
        runCatching { output.close() }
    }

    private suspend fun handleRequest(request: WftnpMessage.Request) {
        when (request) {
            is WftnpMessage.DiscoverServices -> handleDiscoverServices(request)
            is WftnpMessage.DiscoverCharacteristics -> handleDiscoverCharacteristics(request)
            is WftnpMessage.ReadCharacteristic -> handleReadCharacteristic(request)
            is WftnpMessage.WriteCharacteristic -> handleWriteCharacteristic(request)
            is WftnpMessage.EnableNotifications -> handleEnableNotifications(request)
            is WftnpMessage.UnknownCompat -> {
                send(WftnpCodec.encodeResponse(WftnpCodec.ID_UNKNOWN_COMPAT, request.sequence, WftnpCodec.RESP_SUCCESS))
            }
        }
    }

    private suspend fun handleDiscoverServices(request: WftnpMessage.DiscoverServices) {
        val services = WftnpServiceDefinition.services
        val payload = ByteArray(services.size * 16)
        services.forEachIndexed { i, uuid ->
            WftnpCodec.encodeUuidBlob(uuid).copyInto(payload, i * 16)
        }
        send(WftnpCodec.encodeResponse(WftnpCodec.ID_DISCOVER_SERVICES, request.sequence, WftnpCodec.RESP_SUCCESS, payload))
    }

    private suspend fun handleDiscoverCharacteristics(request: WftnpMessage.DiscoverCharacteristics) {
        val chars = serviceDef.characteristicsFor(request.serviceUuid)
        if (chars == null) {
            send(WftnpCodec.encodeResponse(WftnpCodec.ID_DISCOVER_CHARACTERISTICS, request.sequence, WftnpCodec.RESP_SERVICE_NOT_FOUND))
            return
        }
        val serviceBlob = WftnpCodec.encodeUuidBlob(request.serviceUuid)
        val payload = ByteArray(16 + chars.size * 17)
        serviceBlob.copyInto(payload, 0)
        chars.forEachIndexed { i, (uuid, props) ->
            val offset = 16 + i * 17
            WftnpCodec.encodeUuidBlob(uuid).copyInto(payload, offset)
            payload[offset + 16] = props
        }
        send(WftnpCodec.encodeResponse(WftnpCodec.ID_DISCOVER_CHARACTERISTICS, request.sequence, WftnpCodec.RESP_SUCCESS, payload))
    }

    private suspend fun handleReadCharacteristic(request: WftnpMessage.ReadCharacteristic) {
        val value = serviceDef.readValue(request.charUuid)
        if (value == null) {
            send(WftnpCodec.encodeResponse(WftnpCodec.ID_READ_CHARACTERISTIC, request.sequence, WftnpCodec.RESP_CHAR_NOT_FOUND))
            return
        }
        val payload = WftnpCodec.encodeUuidBlob(request.charUuid) + value
        send(WftnpCodec.encodeResponse(WftnpCodec.ID_READ_CHARACTERISTIC, request.sequence, WftnpCodec.RESP_SUCCESS, payload))
    }

    private suspend fun handleWriteCharacteristic(request: WftnpMessage.WriteCharacteristic) {
        if (!serviceDef.isWritable(request.charUuid)) {
            send(WftnpCodec.encodeResponse(WftnpCodec.ID_WRITE_CHARACTERISTIC, request.sequence, WftnpCodec.RESP_OP_NOT_SUPPORTED))
            return
        }

        // Send WFTNP write success response
        send(WftnpCodec.encodeResponse(WftnpCodec.ID_WRITE_CHARACTERISTIC, request.sequence, WftnpCodec.RESP_SUCCESS))

        val result = when (request.charUuid) {
            WftnpServiceDefinition.FTMS_CONTROL_POINT -> {
                val parsed = ControlPointParser.parseFtmsControlPoint(request.value)
                // Send FTMS CP indication response via notification
                if (request.value.isNotEmpty()) {
                    val cpResp = ControlPointParser.encodeResponse(
                        request.value[0],
                        when (parsed) {
                            is ControlPointParser.ControlPointResult.Unsupported -> ControlPointParser.RESULT_NOT_SUPPORTED
                            else -> ControlPointParser.RESULT_SUCCESS
                        },
                    )
                    send(WftnpCodec.encodeNotification(WftnpServiceDefinition.FTMS_CONTROL_POINT, cpResp))
                }
                parsed
            }
            WftnpServiceDefinition.WAHOO_CONTROL -> ControlPointParser.parseWahooControl(request.value)
            else -> null
        }

        if (result is ControlPointParser.ControlPointResult.DeviceCmd) {
            onCommand(result.command)
        }
    }

    private suspend fun handleEnableNotifications(request: WftnpMessage.EnableNotifications) {
        if (!serviceDef.isNotifiable(request.charUuid)) {
            send(WftnpCodec.encodeResponse(WftnpCodec.ID_ENABLE_NOTIFICATIONS, request.sequence, WftnpCodec.RESP_CHAR_NOT_FOUND))
            return
        }

        if (request.enable) {
            enabledNotifications.add(request.charUuid)
            logger.d(TAG, "Client $clientId enabled notifications for ${request.charUuid}")
        } else {
            enabledNotifications.remove(request.charUuid)
            logger.d(TAG, "Client $clientId disabled notifications for ${request.charUuid}")
        }

        send(WftnpCodec.encodeResponse(WftnpCodec.ID_ENABLE_NOTIFICATIONS, request.sequence, WftnpCodec.RESP_SUCCESS))
    }

    private suspend fun send(bytes: ByteArray) {
        if (isClosed) return
        try {
            writeMutex.withLock {
                withContext(Dispatchers.IO) {
                    output.write(bytes)
                    output.flush()
                }
            }
        } catch (e: Exception) {
            logger.d(TAG, "Client $clientId write error: ${e.message}")
            isClosed = true
        }
    }

    private companion object {
        const val TAG = "WftnpClient"
    }
}
