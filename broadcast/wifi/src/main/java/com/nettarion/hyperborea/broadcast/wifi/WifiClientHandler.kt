package com.nettarion.hyperborea.broadcast.wifi

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.ftms.ControlPointParser
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.ftms.FtmsDataEncoder
import com.nettarion.hyperborea.core.ftms.RevolutionCounter
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class WifiClientHandler(
    val clientId: String,
    private val input: InputStream,
    private val output: OutputStream,
    private val deviceType: DeviceType,
    private val serviceDef: WifiServiceDefinition,
    private val scope: CoroutineScope,
    private val onCommand: (DeviceCommand) -> Unit,
    private val logger: AppLogger,
) {
    internal val enabledNotifications: MutableSet<ShortUuid> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    private val writeMutex = Mutex()
    @Volatile var isClosed = false
        private set

    private val revCounter = RevolutionCounter()
    private val pendingData = MutableStateFlow<ExerciseData?>(null)
    private var sendJob: Job? = null
    private var consecutiveWriteErrors = 0

    suspend fun runReadLoop() = withContext(Dispatchers.IO) {
        try {
            while (!isClosed) {
                val request = WifiCodec.readRequest(input) ?: break
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

    fun updateData(data: ExerciseData) {
        pendingData.value = data
    }

    fun startSendLoop() {
        sendJob = scope.launch {
            pendingData.filterNotNull().collect { data ->
                sendNotificationsInternal(data)
                delay(NOTIFICATION_INTERVAL_MS)
            }
        }
    }

    private suspend fun sendNotificationsInternal(data: ExerciseData) {
        if (isClosed) return
        val now = System.currentTimeMillis()

        if (enabledNotifications.contains(serviceDef.dataCharacteristic)) {
            val value = FtmsDataEncoder.encodeData(deviceType, data)
            sendNotification(WifiCodec.encodeNotification(serviceDef.dataCharacteristic, value))
        }

        if (enabledNotifications.contains(WifiServiceDefinition.CPS_MEASUREMENT)) {
            revCounter.update(data, now)
            val value = FtmsDataEncoder.encodeCpsMeasurement(
                data,
                revCounter.cumulativeWheelRevs,
                revCounter.lastWheelEventTime,
                revCounter.cumulativeCrankRevs,
                revCounter.lastCrankEventTime,
            )
            sendNotification(WifiCodec.encodeNotification(WifiServiceDefinition.CPS_MEASUREMENT, value))
        }

        if (enabledNotifications.contains(WifiServiceDefinition.TRAINING_STATUS)) {
            val value = FtmsDataEncoder.encodeTrainingStatus(data.workoutMode)
            sendNotification(WifiCodec.encodeNotification(WifiServiceDefinition.TRAINING_STATUS, value))
        }
    }

    fun close() {
        isClosed = true
        sendJob?.cancel()
        sendJob = null
        runCatching { input.close() }
        runCatching { output.close() }
    }

    private suspend fun handleRequest(request: WifiMessage.Request) {
        when (request) {
            is WifiMessage.DiscoverServices -> {
                logger.d(TAG, "[$clientId] TNP DiscoverServices")
                handleDiscoverServices(request)
            }
            is WifiMessage.DiscoverCharacteristics -> {
                logger.d(TAG, "[$clientId] TNP DiscoverCharacteristics service=${request.serviceUuid}")
                handleDiscoverCharacteristics(request)
            }
            is WifiMessage.ReadCharacteristic -> {
                logger.d(TAG, "[$clientId] TNP ReadCharacteristic char=${request.charUuid}")
                handleReadCharacteristic(request)
            }
            is WifiMessage.WriteCharacteristic -> {
                logger.d(TAG, "[$clientId] TNP WriteCharacteristic char=${request.charUuid} len=${request.value.size}")
                handleWriteCharacteristic(request)
            }
            is WifiMessage.EnableNotifications -> {
                logger.d(TAG, "[$clientId] TNP EnableNotifications char=${request.charUuid} enable=${request.enable}")
                handleEnableNotifications(request)
            }
            is WifiMessage.UnknownCompat -> {
                logger.d(TAG, "[$clientId] TNP UnknownCompat seq=${request.sequence}")
                send(WifiCodec.encodeResponse(WifiCodec.ID_UNKNOWN_COMPAT, request.sequence, WifiCodec.RESP_SUCCESS))
            }
        }
    }

    private suspend fun handleDiscoverServices(request: WifiMessage.DiscoverServices) {
        val services = WifiServiceDefinition.services
        val payload = ByteArray(services.size * 16)
        services.forEachIndexed { i, uuid ->
            WifiCodec.encodeUuidBlob(uuid).copyInto(payload, i * 16)
        }
        send(WifiCodec.encodeResponse(WifiCodec.ID_DISCOVER_SERVICES, request.sequence, WifiCodec.RESP_SUCCESS, payload))
    }

    private suspend fun handleDiscoverCharacteristics(request: WifiMessage.DiscoverCharacteristics) {
        val chars = serviceDef.characteristicsFor(request.serviceUuid)
        if (chars == null) {
            logger.d(TAG, "[$clientId] TNP DiscoverCharacteristics: service ${request.serviceUuid} not found")
            send(WifiCodec.encodeResponse(WifiCodec.ID_DISCOVER_CHARACTERISTICS, request.sequence, WifiCodec.RESP_SERVICE_NOT_FOUND))
            return
        }
        for ((uuid, props) in chars) {
            logger.d(TAG, "[$clientId]   char=$uuid props=0x${(props.toInt() and 0xFF).toString(16).padStart(2, '0')}")
        }
        val serviceBlob = WifiCodec.encodeUuidBlob(request.serviceUuid)
        val payload = ByteArray(16 + chars.size * 17)
        serviceBlob.copyInto(payload, 0)
        chars.forEachIndexed { i, (uuid, props) ->
            val offset = 16 + i * 17
            WifiCodec.encodeUuidBlob(uuid).copyInto(payload, offset)
            payload[offset + 16] = props
        }
        send(WifiCodec.encodeResponse(WifiCodec.ID_DISCOVER_CHARACTERISTICS, request.sequence, WifiCodec.RESP_SUCCESS, payload))
    }

    private suspend fun handleReadCharacteristic(request: WifiMessage.ReadCharacteristic) {
        val value = serviceDef.readValue(request.charUuid)
        if (value == null) {
            send(WifiCodec.encodeResponse(WifiCodec.ID_READ_CHARACTERISTIC, request.sequence, WifiCodec.RESP_CHAR_NOT_FOUND))
            return
        }
        val payload = WifiCodec.encodeUuidBlob(request.charUuid) + value
        send(WifiCodec.encodeResponse(WifiCodec.ID_READ_CHARACTERISTIC, request.sequence, WifiCodec.RESP_SUCCESS, payload))
    }

    private suspend fun handleWriteCharacteristic(request: WifiMessage.WriteCharacteristic) {
        if (!serviceDef.isWritable(request.charUuid)) {
            send(WifiCodec.encodeResponse(WifiCodec.ID_WRITE_CHARACTERISTIC, request.sequence, WifiCodec.RESP_OP_NOT_SUPPORTED))
            return
        }

        // Send write success response
        send(WifiCodec.encodeResponse(WifiCodec.ID_WRITE_CHARACTERISTIC, request.sequence, WifiCodec.RESP_SUCCESS))

        val result = when (request.charUuid) {
            WifiServiceDefinition.FTMS_CONTROL_POINT -> {
                val parsed = ControlPointParser.parseFtmsControlPoint(request.value)
                logger.d(TAG, "[$clientId] FTMS control point parsed: $parsed")
                // Send FTMS CP indication response via notification
                if (request.value.isNotEmpty()) {
                    val cpResp = ControlPointParser.encodeResponse(
                        request.value[0],
                        when (parsed) {
                            is ControlPointParser.ControlPointResult.Unsupported -> ControlPointParser.RESULT_NOT_SUPPORTED
                            else -> ControlPointParser.RESULT_SUCCESS
                        },
                    )
                    send(WifiCodec.encodeNotification(WifiServiceDefinition.FTMS_CONTROL_POINT, cpResp))
                }
                // Extract fan command from simulation parameters (opcode 0x11)
                ControlPointParser.extractFanCommand(request.value)?.let { onCommand(it) }
                parsed
            }
            WifiServiceDefinition.TRAINER_CONTROL -> {
                val parsed = ControlPointParser.parseTrainerControl(request.value)
                logger.d(TAG, "[$clientId] Trainer control parsed: $parsed")
                parsed
            }
            else -> null
        }

        if (result is ControlPointParser.ControlPointResult.DeviceCmd) {
            onCommand(result.command)
        }
    }

    private suspend fun handleEnableNotifications(request: WifiMessage.EnableNotifications) {
        if (!serviceDef.isNotifiable(request.charUuid)) {
            send(WifiCodec.encodeResponse(WifiCodec.ID_ENABLE_NOTIFICATIONS, request.sequence, WifiCodec.RESP_CHAR_NOT_FOUND))
            return
        }

        if (request.enable) {
            enabledNotifications.add(request.charUuid)
            logger.d(TAG, "Client $clientId enabled notifications for ${request.charUuid}")
        } else {
            enabledNotifications.remove(request.charUuid)
            logger.d(TAG, "Client $clientId disabled notifications for ${request.charUuid}")
        }

        send(WifiCodec.encodeResponse(WifiCodec.ID_ENABLE_NOTIFICATIONS, request.sequence, WifiCodec.RESP_SUCCESS))
    }

    private suspend fun sendNotification(bytes: ByteArray) {
        if (isClosed) return
        try {
            writeMutex.withLock {
                withContext(Dispatchers.IO) {
                    output.write(bytes)
                    output.flush()
                }
            }
            consecutiveWriteErrors = 0
        } catch (e: Exception) {
            consecutiveWriteErrors++
            logger.d(TAG, "Client $clientId notification write error ($consecutiveWriteErrors/$MAX_CONSECUTIVE_WRITE_ERRORS): ${e.message}")
            if (consecutiveWriteErrors >= MAX_CONSECUTIVE_WRITE_ERRORS) {
                isClosed = true
            }
        }
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
        const val TAG = "WifiClient"
        const val NOTIFICATION_INTERVAL_MS = 250L
        const val MAX_CONSECUTIVE_WRITE_ERRORS = 3
    }
}
