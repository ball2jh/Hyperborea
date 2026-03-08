package com.nettarion.hyperborea.hardware.fitpro.v2

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.model.DeviceIdentity
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.hardware.fitpro.session.ExerciseDataAccumulator
import com.nettarion.hyperborea.hardware.fitpro.session.FitProSession
import com.nettarion.hyperborea.hardware.fitpro.session.SessionState
import com.nettarion.hyperborea.hardware.fitpro.transport.HidTransport
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class V2Session(
    private val transport: HidTransport,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
    private val deviceInfo: DeviceInfo,
    private val accumulator: ExerciseDataAccumulator = ExerciseDataAccumulator(),
) : FitProSession {

    private val _exerciseData = MutableStateFlow<ExerciseData?>(null)
    override val exerciseData: StateFlow<ExerciseData?> = _exerciseData.asStateFlow()

    private val _deviceIdentity = MutableStateFlow<DeviceIdentity?>(null)
    override val deviceIdentity: StateFlow<DeviceIdentity?> = _deviceIdentity.asStateFlow()

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Disconnected)
    override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private var heartbeatJob: Job? = null
    private var receiveJob: Job? = null
    private var lastSentGrade = 0f
    private var lastSentSpeed = 0f

    override suspend fun start() {
        if (_sessionState.value is SessionState.Streaming || _sessionState.value is SessionState.Connecting) return

        try {
            _sessionState.value = SessionState.Connecting
            transport.open()

            _sessionState.value = SessionState.Handshaking
            queryAndSubscribe()

            accumulator.start()
            _sessionState.value = SessionState.Streaming

            startReceiveLoop()
            startHeartbeat()

            // Enter RUNNING mode so the bike accepts control writes
            val running = V2Message.Outgoing.WriteFeature(V2FeatureId.SYSTEM_MODE, SYSTEM_MODE_RUNNING)
            transport.write(V2Codec.encode(running))
            logger.i(TAG, "V2 session started, entered RUNNING mode")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to start V2 session", e)
            try { transport.close() } catch (_: Exception) {}
            _sessionState.value = SessionState.Error(e.message ?: "V2 session failed", e)
        }
    }

    override suspend fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        receiveJob?.cancel()
        receiveJob = null

        try {
            if (transport.isOpen) {
                // Return to IDLE before disconnecting
                val idle = V2Message.Outgoing.WriteFeature(V2FeatureId.SYSTEM_MODE, SYSTEM_MODE_IDLE)
                transport.write(V2Codec.encode(idle))

                val unsubscribe = V2Message.Outgoing.Unsubscribe(V2FeatureId.subscribable)
                transport.write(V2Codec.encode(unsubscribe))
                transport.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Error during V2 session stop: ${e.message}")
        }

        accumulator.reset()
        _exerciseData.value = null
        _deviceIdentity.value = null
        _sessionState.value = SessionState.Disconnected
        logger.i(TAG, "V2 session stopped")
    }

    override suspend fun writeFeature(command: DeviceCommand) {
        if (_sessionState.value !is SessionState.Streaming) return

        val message = when (command) {
            is DeviceCommand.SetResistance -> V2Message.Outgoing.WriteFeature(
                V2FeatureId.TARGET_RESISTANCE,
                command.level.toFloat(),
            )
            is DeviceCommand.SetIncline -> {
                lastSentGrade = roundToStep(command.percent, deviceInfo.inclineStep)
                V2Message.Outgoing.WriteFeature(V2FeatureId.TARGET_GRADE, lastSentGrade)
            }
            is DeviceCommand.SetTargetSpeed -> {
                lastSentSpeed = command.kph
                V2Message.Outgoing.WriteFeature(V2FeatureId.TARGET_KPH, command.kph)
            }
            is DeviceCommand.AdjustIncline -> {
                lastSentGrade += if (command.increase) deviceInfo.inclineStep else -deviceInfo.inclineStep
                lastSentGrade = lastSentGrade.coerceIn(deviceInfo.minIncline, deviceInfo.maxIncline)
                V2Message.Outgoing.WriteFeature(V2FeatureId.TARGET_GRADE, lastSentGrade)
            }
            is DeviceCommand.AdjustSpeed -> {
                lastSentSpeed += if (command.increase) deviceInfo.speedStep else -deviceInfo.speedStep
                lastSentSpeed = lastSentSpeed.coerceIn(0f, deviceInfo.maxSpeed)
                V2Message.Outgoing.WriteFeature(V2FeatureId.TARGET_KPH, lastSentSpeed)
            }
            is DeviceCommand.SetTargetPower -> V2Message.Outgoing.WriteFeature(
                V2FeatureId.GOAL_WATTS,
                command.watts.toFloat(),
            )
            is DeviceCommand.PauseWorkout -> {
                accumulator.pause()
                V2Message.Outgoing.WriteFeature(
                    V2FeatureId.SYSTEM_MODE,
                    SYSTEM_MODE_PAUSE,
                )
            }
            is DeviceCommand.ResumeWorkout -> {
                accumulator.resume()
                V2Message.Outgoing.WriteFeature(
                    V2FeatureId.SYSTEM_MODE,
                    SYSTEM_MODE_RUNNING,
                )
            }
        }

        try {
            transport.write(V2Codec.encode(message))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to write feature", e)
        }
    }

    private suspend fun queryAndSubscribe() {
        // Query supported features
        transport.write(V2Codec.encode(V2Message.Outgoing.QueryFeatures()))

        // Subscribe in batches of 8
        val batches = V2FeatureId.subscribable.chunked(MAX_SUBSCRIBE_BATCH)
        for (batch in batches) {
            val subscribe = V2Message.Outgoing.Subscribe(batch)
            transport.write(V2Codec.encode(subscribe))
        }

        _deviceIdentity.value = DeviceIdentity()
    }

    private fun startReceiveLoop() {
        receiveJob = scope.launch {
            try {
                transport.incoming().collect { data ->
                    handleIncoming(data)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "Receive loop error", e)
            }

            // Flow completed = device disconnected
            if (_sessionState.value is SessionState.Streaming) {
                logger.w(TAG, "Transport disconnected")
                _sessionState.value = SessionState.Disconnected
            }
        }
    }

    private fun handleIncoming(data: ByteArray) {
        val message = V2Codec.decode(data) ?: return

        when (message) {
            is V2Message.Incoming.Event -> {
                applyEvent(message.feature, message.value)
                _exerciseData.value = accumulator.snapshot()
            }
            is V2Message.Incoming.SupportedFeatures ->
                logger.d(TAG, "Supported features: ${message.features.map { it.name }}")
            is V2Message.Incoming.Acknowledge ->
                logger.d(TAG, "ACK: ${message.type}")
            is V2Message.Incoming.Error ->
                logger.w(TAG, "Device error code: ${message.code}")
            is V2Message.Incoming.Unknown ->
                logger.d(TAG, "Unknown message: ${message.raw.size} bytes")
        }
    }

    private fun applyEvent(feature: V2FeatureId, value: Float) {
        when (feature) {
            V2FeatureId.WATTS -> accumulator.updatePower(value.toInt())
            V2FeatureId.RPM -> accumulator.updateCadence(value.toInt())
            V2FeatureId.CURRENT_KPH -> accumulator.updateSpeed(value)
            V2FeatureId.TARGET_RESISTANCE -> accumulator.updateResistance(value.toInt())
            V2FeatureId.CURRENT_GRADE -> accumulator.updateIncline(value)
            V2FeatureId.PULSE -> accumulator.updateHeartRate(value.toInt())
            V2FeatureId.DISTANCE -> accumulator.updateDistance(value)
            V2FeatureId.CURRENT_CALORIES -> accumulator.updateCalories(value.toInt())
            V2FeatureId.RUNNING_TIME -> accumulator.updateElapsedTime(value.toLong())
            V2FeatureId.TARGET_KPH -> accumulator.updateTargetSpeed(value)
            V2FeatureId.TARGET_GRADE -> accumulator.updateTargetIncline(value)
            V2FeatureId.HEART_BEAT_INTERVAL -> { /* Protocol keepalive echo */ }
            V2FeatureId.SYSTEM_MODE -> { /* System mode notification, not exercise data */ }
            V2FeatureId.MAX_RESISTANCE -> { /* Device capability, not exercise data */ }
            V2FeatureId.GOAL_WATTS -> accumulator.updateTargetPower(value.toInt())
        }
    }

    private fun roundToStep(value: Float, step: Float): Float =
        (value / step).roundToInt() * step

    private fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (isActive && _sessionState.value is SessionState.Streaming) {
                try {
                    val heartbeat = V2Message.Outgoing.WriteFeature(
                        V2FeatureId.HEART_BEAT_INTERVAL,
                        HEARTBEAT_VALUE,
                    )
                    transport.write(V2Codec.encode(heartbeat))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w(TAG, "Heartbeat write failed: ${e.message}")
                }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    companion object {
        private const val TAG = "V2Session"
        private const val HEARTBEAT_INTERVAL_MS = 720L
        private const val HEARTBEAT_VALUE = 720f
        private const val MAX_SUBSCRIBE_BATCH = 8
        private const val SYSTEM_MODE_IDLE = 1f
        private const val SYSTEM_MODE_RUNNING = 2f
        private const val SYSTEM_MODE_PAUSE = 3f
    }
}
