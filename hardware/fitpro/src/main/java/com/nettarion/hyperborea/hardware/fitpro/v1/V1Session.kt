package com.nettarion.hyperborea.hardware.fitpro.v1

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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class V1Session(
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

    private var pollJob: Job? = null
    private var pendingWriteFields: Map<V1DataField, Float> = emptyMap()
    private val pendingWriteMutex = Mutex()
    private var lastLogTimeMs = 0L
    private var consecutivePollErrors = 0
    private var lastSentGrade = 0f
    private var lastSentSpeed = 0f

    // Security handshake state — stored for SECURITY_BLOCK re-verification
    private var softwareVersion: Int = 0
    private var hardwareVersion: Int = 0
    private var serialNumber: Int = 0
    private var partNumber: Int = 0
    private var model: Int = 0
    private var masterLibraryVersion: Int = 0

    override suspend fun start() {
        if (_sessionState.value is SessionState.Streaming || _sessionState.value is SessionState.Connecting) return

        try {
            _sessionState.value = SessionState.Connecting
            transport.open()

            _sessionState.value = SessionState.Handshaking
            transport.clearBuffer()
            handshake()

            accumulator.start()
            _sessionState.value = SessionState.Streaming

            startPollLoop()

            logger.i(TAG, "V1 session started")

            // Enter RUNNING mode, signal workout start, and disable idle
            // lockout so the bike doesn't auto-pause when pedaling stops.
            // REQUIRE_START_REQUESTED tells the MCU to begin its workout
            // session so CURRENT_TIME/DISTANCE/CALORIES start counting.
            pendingWriteMutex.withLock {
                pendingWriteFields = mapOf(
                    V1DataField.WORKOUT_MODE to WORKOUT_MODE_RUNNING,
                    V1DataField.REQUIRE_START_REQUESTED to 1f,
                    V1DataField.IDLE_MODE_LOCKOUT to 1f,
                )
            }
            logger.i(TAG, "Entered RUNNING workout mode")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to start V1 session", e)
            try { transport.close() } catch (_: Exception) {}
            _sessionState.value = SessionState.Error(e.message ?: "V1 session failed", e)
        }
    }

    override suspend fun stop() {
        pollJob?.cancel()
        pollJob = null

        try {
            if (transport.isOpen) {
                // TODO: Test whether MCU auto-resets incline/resistance on IDLE transition.
                //  If not, explicitly write GRADE=0 and RESISTANCE=<default> here before IDLE.

                // Write WORKOUT_MODE=IDLE before disconnecting — fire-and-forget,
                // don't wait for responses since we're tearing down.
                val idleMsg = V1Message.Outgoing.ReadWriteData(
                    writeFields = mapOf(V1DataField.WORKOUT_MODE to WORKOUT_MODE_IDLE),
                )
                transport.write(V1Codec.encode(idleMsg).first())
                delay(COMMAND_DELAY_MS)

                transport.write(V1Codec.encode(V1Message.Outgoing.Disconnect()).first())
                delay(COMMAND_DELAY_MS)
                transport.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Error during V1 session stop: ${e.message}")
        }

        accumulator.reset()
        _exerciseData.value = null
        _deviceIdentity.value = null
        _sessionState.value = SessionState.Disconnected
        logger.i(TAG, "V1 session stopped")
    }

    override suspend fun identify(): DeviceIdentity? {
        try {
            transport.open()
            transport.clearBuffer()
            handshake()
            return _deviceIdentity.value
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            logger.e(TAG, "Identify failed", e)
            return null
        } finally {
            try { transport.close() } catch (_: Exception) {}
        }
    }

    override suspend fun writeFeature(command: DeviceCommand) {
        if (_sessionState.value !is SessionState.Streaming) return

        val fields = commandToFields(command)

        pendingWriteMutex.withLock {
            pendingWriteFields = pendingWriteFields + fields
        }
    }

    internal fun commandToFields(command: DeviceCommand): Map<V1DataField, Float> = when (command) {
        is DeviceCommand.SetResistance -> {
            val raw = resistanceLevelToRaw(command.level)
            mapOf(V1DataField.RESISTANCE to raw.toFloat())
        }
        is DeviceCommand.SetIncline -> {
            lastSentGrade = roundToStep(command.percent, deviceInfo.inclineStep)
            mapOf(V1DataField.GRADE to lastSentGrade)
        }
        is DeviceCommand.SetTargetSpeed -> {
            lastSentSpeed = command.kph
            mapOf(V1DataField.KPH to command.kph)
        }
        is DeviceCommand.AdjustIncline -> {
            lastSentGrade += if (command.increase) deviceInfo.inclineStep else -deviceInfo.inclineStep
            lastSentGrade = lastSentGrade.coerceIn(deviceInfo.minIncline, deviceInfo.maxIncline)
            mapOf(V1DataField.GRADE to lastSentGrade)
        }
        is DeviceCommand.AdjustSpeed -> {
            lastSentSpeed += if (command.increase) deviceInfo.speedStep else -deviceInfo.speedStep
            lastSentSpeed = lastSentSpeed.coerceIn(0f, deviceInfo.maxSpeed)
            mapOf(V1DataField.KPH to lastSentSpeed)
        }
        is DeviceCommand.SetTargetPower -> {
            mapOf(V1DataField.WATT_GOAL to command.watts.toFloat())
        }
        is DeviceCommand.PauseWorkout -> {
            mapOf(V1DataField.WORKOUT_MODE to WORKOUT_MODE_PAUSE)
        }
        is DeviceCommand.ResumeWorkout -> {
            mapOf(V1DataField.WORKOUT_MODE to WORKOUT_MODE_RUNNING)
        }
    }

    private suspend fun handshake() {
        // 1. Connect to FITNESS_BIKE
        val connectResponse = sendAndAwait(V1Message.Outgoing.Connect())
            ?: throw IllegalStateException("Connect handshake timed out")
        if (connectResponse !is V1Message.Incoming.ConnectAck) {
            throw IllegalStateException("Expected ConnectAck, got: $connectResponse")
        }
        logger.d(TAG, "Connected to device ${connectResponse.deviceId}")

        delay(COMMAND_DELAY_MS)

        // 2. DeviceInfo (from MAIN) → serialNumber, softwareVersion
        val deviceInfo = sendAndAwait(V1Message.Outgoing.DeviceInfo())
        if (deviceInfo is V1Message.Incoming.DeviceInfoResponse) {
            softwareVersion = deviceInfo.softwareVersion
            hardwareVersion = deviceInfo.hardwareVersion
            serialNumber = deviceInfo.serialNumber
            logger.d(TAG, "Device info: sw=$softwareVersion, hw=$hardwareVersion, serial=$serialNumber")
        } else {
            logger.w(TAG, "Expected DeviceInfoResponse, got: $deviceInfo")
        }

        delay(COMMAND_DELAY_MS)

        // 3. SystemInfo → partNumber, model
        val systemInfo = sendAndAwait(V1Message.Outgoing.SystemInfo())
        if (systemInfo is V1Message.Incoming.SystemInfoResponse) {
            partNumber = systemInfo.partNumber
            model = systemInfo.model
            logger.d(TAG, "System info: partNumber=$partNumber, model=$model")
        } else {
            logger.w(TAG, "Expected SystemInfoResponse, got: $systemInfo")
        }

        delay(COMMAND_DELAY_MS)

        // 4. VersionInfo → masterLibraryVersion
        val versionInfo = sendAndAwait(V1Message.Outgoing.VersionInfo())
        if (versionInfo is V1Message.Incoming.VersionInfoResponse) {
            masterLibraryVersion = versionInfo.masterLibraryVersion
            logger.d(TAG, "Version info: masterLib=$masterLibraryVersion, build=${versionInfo.masterLibraryBuild}")
        } else {
            logger.w(TAG, "Expected VersionInfoResponse, got: $versionInfo")
        }

        _deviceIdentity.value = DeviceIdentity(
            serialNumber = serialNumber.toString(),
            firmwareVersion = softwareVersion.toString(),
            hardwareVersion = hardwareVersion.toString(),
            model = model.toString(),
            partNumber = partNumber.toString(),
        )

        delay(COMMAND_DELAY_MS)

        // 5. VerifySecurity (only if SW version > 75)
        if (softwareVersion > 75) {
            verifySecurity()
        } else {
            logger.d(TAG, "Skipping security verification (sw=$softwareVersion <= 75)")
        }
    }

    private suspend fun verifySecurity() {
        val hash = V1Security.calculateHash(serialNumber, partNumber, model)
        val secretKey = masterLibraryVersion * 8
        val response = sendAndAwait(V1Message.Outgoing.VerifySecurity(hash = hash, secretKey = secretKey))

        if (response is V1Message.Incoming.SecurityResponse) {
            if (!response.isUnlocked) {
                throw IllegalStateException("Security verification failed (key=${response.unlockedKey})")
            }
            logger.i(TAG, "Security verified (key=${response.unlockedKey})")
        } else {
            logger.w(TAG, "Unexpected security response: $response")
        }
    }

    private fun startPollLoop() {
        pollJob = scope.launch {
            while (isActive && _sessionState.value is SessionState.Streaming) {
                try {
                    pollOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    consecutivePollErrors++
                    logger.w(TAG, "Poll error ($consecutivePollErrors/$MAX_CONSECUTIVE_POLL_ERRORS): ${e.message}")
                    if (consecutivePollErrors >= MAX_CONSECUTIVE_POLL_ERRORS) {
                        _sessionState.value = SessionState.Error("Repeated poll failures", e)
                    }
                }
                delay(POLL_INTERVAL_MS)
            }

            if (_sessionState.value is SessionState.Streaming) {
                logger.w(TAG, "Poll loop ended unexpectedly")
                _sessionState.value = SessionState.Disconnected
            }
        }
    }

    private suspend fun pollOnce() {
        val writeFields: Map<V1DataField, Float>
        pendingWriteMutex.withLock {
            writeFields = pendingWriteFields
            pendingWriteFields = emptyMap()
        }

        // ReadWriteData targets DEVICE_MAIN (0x02) — FITNESS_BIKE (0x07) returns DEV_NOT_SUPPORTED
        val message = V1Message.Outgoing.ReadWriteData(
            writeFields = writeFields,
            readFields = V1DataField.periodicReadFields,
        )

        val packets = V1Codec.encode(message)
        for (packet in packets) {
            transport.write(packet)
        }

        delay(READ_DELAY_MS)

        val response = transport.readPacket()
        if (response == null) {
            if (writeFields.isNotEmpty()) {
                pendingWriteMutex.withLock {
                    pendingWriteFields = writeFields + pendingWriteFields
                }
            }
            return
        }

        val decoded = try {
            V1Codec.decodeSingle(response)
        } catch (e: Exception) {
            logger.w(TAG, "Malformed response (${response.size} bytes): ${e.message}")
            // Re-queue write fields so commands aren't lost
            if (writeFields.isNotEmpty()) {
                pendingWriteMutex.withLock {
                    pendingWriteFields = writeFields + pendingWriteFields
                }
            }
            return
        }
        if (decoded is V1Message.Incoming.DataResponse) {
            if (decoded.status == V1Message.STATUS_SECURITY_BLOCK) {
                logger.w(TAG, "Security block — re-verifying")
                if (writeFields.isNotEmpty()) {
                    pendingWriteMutex.withLock {
                        pendingWriteFields = writeFields + pendingWriteFields
                    }
                }
                verifySecurity()
                return
            }
            if (decoded.status != V1Message.STATUS_DONE) {
                return
            }

            if (decoded.fields.isEmpty()) {
                logger.w(TAG, "DataResponse OK but empty fields (payload size mismatch)")
                return
            }

            applyDataResponse(decoded.fields)
            consecutivePollErrors = 0
            _exerciseData.value = accumulator.snapshot()

            val now = System.currentTimeMillis()
            if (now - lastLogTimeMs >= 1000L) {
                lastLogTimeMs = now
                val snap = _exerciseData.value
                if (snap != null) {
                    logger.d(TAG, "power=${snap.power}W cadence=${snap.cadence}rpm speed=${snap.speed}kph resistance=${snap.resistance} incline=${snap.incline}%")
                }
            }
        }
    }

    private fun applyDataResponse(fields: Map<V1DataField, Float>) {
        for ((field, value) in fields) {
            when (field) {
                V1DataField.WATTS -> accumulator.updatePower(value.toInt())
                V1DataField.RPM -> {
                    val prev = accumulator.snapshot().cadence
                    accumulator.updateCadence(value.toInt())
                    if ((prev == null || prev == 0) && value.toInt() > 0) {
                        logger.d(TAG, "Cadence went non-zero: ${value.toInt()} rpm")
                    }
                }
                V1DataField.ACTUAL_KPH -> accumulator.updateSpeed(value)
                V1DataField.KPH -> accumulator.updateTargetSpeed(value)
                V1DataField.RESISTANCE -> {
                    val level = resistanceRawToLevel(value.toInt())
                    accumulator.updateResistance(level)
                }
                V1DataField.ACTUAL_INCLINE -> accumulator.updateIncline(value)
                V1DataField.GRADE -> accumulator.updateTargetIncline(value)
                V1DataField.PULSE -> accumulator.updateHeartRate(value.toInt())
                V1DataField.CURRENT_DISTANCE -> accumulator.updateDistance(value)
                V1DataField.CURRENT_CALORIES -> accumulator.updateCalories(value.toInt())
                V1DataField.CURRENT_TIME -> accumulator.updateElapsedTime(value.toLong())
                V1DataField.WORKOUT_MODE -> {
                    val mode = value.toInt()
                    val previousMode = accumulator.snapshot().workoutMode
                    if (previousMode != mode) {
                        when (mode) {
                            WORKOUT_MODE_PAUSE.toInt() -> accumulator.pause()
                            WORKOUT_MODE_RUNNING.toInt() -> {
                                accumulator.resume()
                                accumulator.startTimer()
                            }
                        }
                    }
                    accumulator.updateWorkoutMode(mode)
                }
                V1DataField.RUNNING_TIME -> accumulator.updateLifetimeRunningTime(value.toLong())
                V1DataField.DISTANCE -> accumulator.updateLifetimeDistance(value)
                V1DataField.CALORIES -> accumulator.updateLifetimeCalories(value.toInt())
                V1DataField.MAX_RESISTANCE_LEVEL,
                V1DataField.WATT_GOAL,
                V1DataField.IDLE_MODE_LOCKOUT,
                V1DataField.REQUIRE_START_REQUESTED -> { /* Not in periodicReadFields */ }
            }
        }
    }

    private suspend fun sendAndAwait(message: V1Message.Outgoing): V1Message.Incoming? {
        val packets = V1Codec.encode(message)
        for (packet in packets) {
            transport.write(packet)
        }
        delay(READ_DELAY_MS)
        val response = transport.readPacket()
        return response?.let { V1Codec.decodeSingle(it) }
    }

    private fun roundToStep(value: Float, step: Float): Float =
        (value / step).roundToInt() * step

    // GlassOS ResistanceConverter: raw = max(0, level * ratio - 1)
    private val resistanceRatio = 10000 / deviceInfo.maxResistance

    private fun resistanceLevelToRaw(level: Int): Int =
        maxOf(0, level * resistanceRatio - 1)

    // GlassOS ResistanceConverter: level = ceil(raw / ratio)
    private fun resistanceRawToLevel(raw: Int): Int {
        val remainder = raw % resistanceRatio
        return raw / resistanceRatio + if (remainder != 0) 1 else 0
    }


    companion object {
        private const val TAG = "V1Session"
        private const val POLL_INTERVAL_MS = 100L
        private const val COMMAND_DELAY_MS = 100L
        private const val READ_DELAY_MS = 0L
        private const val MAX_CONSECUTIVE_POLL_ERRORS = 10
        private const val WORKOUT_MODE_IDLE = 1f
        private const val WORKOUT_MODE_RUNNING = 2f
        private const val WORKOUT_MODE_PAUSE = 3f
    }
}
