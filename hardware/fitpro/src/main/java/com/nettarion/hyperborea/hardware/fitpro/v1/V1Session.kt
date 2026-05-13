package com.nettarion.hyperborea.hardware.fitpro.v1

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.model.ConsoleKey
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.model.DeviceIdentity
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.hardware.fitpro.session.DeviceDatabase
import com.nettarion.hyperborea.hardware.fitpro.session.ExerciseDataAccumulator
import com.nettarion.hyperborea.hardware.fitpro.session.FitProSession
import com.nettarion.hyperborea.hardware.fitpro.session.PowerEstimator
import com.nettarion.hyperborea.hardware.fitpro.session.SessionState
import com.nettarion.hyperborea.hardware.fitpro.transport.HidTransport
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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

    private val _consoleKeyPresses =
        MutableSharedFlow<ConsoleKey>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val consoleKeyPresses: SharedFlow<ConsoleKey> = _consoleKeyPresses.asSharedFlow()

    private val _degradedReason = MutableStateFlow<String?>(null)
    override val degradedReason: StateFlow<String?> = _degradedReason.asStateFlow()

    private var pollJob: Job? = null
    private var pendingWriteFields: Map<V1DataField, Float> = emptyMap()
    private val pendingWriteMutex = Mutex()
    @Volatile private var pendingCalibration: CompletableDeferred<Unit>? = null
    private var lastLogTimeMs = 0L
    private var consecutivePollErrors = 0
    private var lastSentGrade = 0f
    private var lastSentSpeed = 0f
    private var lastKeyCode = -1 // for KEY_OBJECT press-edge detection
    private val resistance = ResistanceConverter(deviceInfo.maxResistance)

    /** Device capabilities read from MCU during handshake. */
    var capabilities: V1Capabilities? = null
        private set

    /** Power-curve table index for this device, resolved during the handshake. */
    var powerCurveIndex: Int? = null
        private set

    // Security handshake state — stored for SECURITY_BLOCK re-verification
    private var softwareVersion: Int = 0
    private var hardwareVersion: Int = 0
    private var serialNumber: Int = 0
    private var partNumber: Int = 0
    private var model: Int = 0
    private var masterLibraryVersion: Int = 0

    /** Bitfield indices ([V1DataField.fieldIndex]) the device declared it supports; empty if it couldn't be read. */
    private var supportedBitFields: Set<Int> = emptySet()

    /**
     * The actual set we poll for each loop iteration, narrowed to what the device claims to support.
     * Filtering matters because [V1Codec.decodeDataResponseForFields] decodes the response as a flat
     * blob in field-index order with no per-field presence check, so asking for a field the MCU
     * doesn't supply causes every subsequent field to land on the wrong offset (the bug behind the
     * NordicTrack 2950 Argon-firmware -10595 kcal / 139 km screenshot).
     */
    private var pollFields: Set<V1DataField> = V1DataField.periodicReadFields

    /** Tracks whether the previous poll's response was flagged truncated, so we log only on the edge. */
    private var lastTruncatedSeen: Boolean = false

    override suspend fun start() {
        if (_sessionState.value is SessionState.Streaming || _sessionState.value is SessionState.Connecting) return

        try {
            _sessionState.value = SessionState.Connecting
            transport.open()

            _sessionState.value = SessionState.Handshaking
            transport.clearBuffer()
            handshake()

            // Console init (done while still in IDLE), then bring the workout up the way the
            // console firmware expects: IDLE → WARM_UP → RUNNING, confirming each step.
            prepareConsole()
            accumulator.start()
            transitionToRunning()

            _sessionState.value = SessionState.Streaming
            startPollLoop()
            logger.i(TAG, "V1 session started")
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
                writeMessage(V1Message.Outgoing.ReadWriteData(
                    writeFields = mapOf(V1DataField.WORKOUT_MODE to WorkoutMode.IDLE.raw),
                ))
                delay(COMMAND_DELAY_MS)

                writeMessage(V1Message.Outgoing.Disconnect())
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
        _degradedReason.value = null
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

    override suspend fun calibrate() {
        try {
            transport.open()
            transport.clearBuffer()
            handshake()

            // Calibration runs from idle — no RUNNING mode needed.
            // Connect → handshake → calibrate commands at 4s intervals → disconnect.
            runCalibration()
        } finally {
            try { transport.close() } catch (_: Exception) {}
        }
    }

    override suspend fun writeFeature(command: DeviceCommand) {
        if (command is DeviceCommand.CalibrateIncline) {
            if (_sessionState.value !is SessionState.Streaming) {
                throw IllegalStateException("Not connected")
            }
            val deferred = CompletableDeferred<Unit>()
            pendingCalibration = deferred
            deferred.await()
            return
        }

        if (_sessionState.value !is SessionState.Streaming) return

        val fields = commandToFields(command)

        pendingWriteMutex.withLock {
            pendingWriteFields = pendingWriteFields + fields
        }
    }

    internal fun commandToFields(command: DeviceCommand): Map<V1DataField, Float> = when (command) {
        is DeviceCommand.SetResistance -> {
            mapOf(V1DataField.RESISTANCE to resistance.levelToRaw(command.level).toFloat())
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
            mapOf(
                V1DataField.WATT_GOAL to command.watts.toFloat(),
                V1DataField.IS_CONSTANT_WATTS_MODE to 1f,
            )
        }
        is DeviceCommand.PauseWorkout -> {
            mapOf(V1DataField.WORKOUT_MODE to WorkoutMode.PAUSE.raw)
        }
        is DeviceCommand.ResumeWorkout -> {
            mapOf(V1DataField.WORKOUT_MODE to WorkoutMode.RUNNING.raw)
        }
        is DeviceCommand.CalibrateIncline -> emptyMap()
        is DeviceCommand.SetFanSpeed -> mapOf(V1DataField.FAN_STATE to command.level.toFloat())
        is DeviceCommand.SetVolume -> mapOf(V1DataField.VOLUME to command.level.toFloat())
        is DeviceCommand.SetGear -> mapOf(V1DataField.GEAR to command.gear.toFloat())
        is DeviceCommand.SetDistanceGoal -> mapOf(V1DataField.DISTANCE_GOAL to command.meters.toFloat())
        is DeviceCommand.SetWarmupTimeout -> mapOf(V1DataField.WARMUP_TIMEOUT to command.seconds.toFloat())
        is DeviceCommand.SetCooldownTimeout -> mapOf(V1DataField.COOLDOWN_TIMEOUT to command.seconds.toFloat())
        is DeviceCommand.SetPauseTimeout -> mapOf(V1DataField.PAUSE_TIMEOUT to command.seconds.toFloat())
        is DeviceCommand.SetWarmUpMode -> mapOf(V1DataField.WORKOUT_MODE to WorkoutMode.WARM_UP.raw)
        is DeviceCommand.SetCoolDownMode -> mapOf(V1DataField.WORKOUT_MODE to WorkoutMode.COOL_DOWN.raw)
        is DeviceCommand.SetErgMode -> mapOf(V1DataField.IS_CONSTANT_WATTS_MODE to if (command.enable) 1f else 0f)
    }

    private suspend fun handshake() {
        // 0. DeviceInfo (from MAIN) → serialNumber, softwareVersion, and the real equipment
        //    device ID: the MCU echoes its own device type in byte 0 of the response.
        val deviceInfo = sendAndAwait(V1Message.Outgoing.DeviceInfo())
        val equipmentDeviceId: Int
        if (deviceInfo is V1Message.Incoming.DeviceInfoResponse) {
            softwareVersion = deviceInfo.softwareVersion
            hardwareVersion = deviceInfo.hardwareVersion
            serialNumber = deviceInfo.serialNumber
            supportedBitFields = deviceInfo.supportedBitFields
            equipmentDeviceId = deviceInfo.deviceId.takeIf { it in V1Message.EQUIPMENT_DEVICE_IDS }
                ?: V1Message.DEVICE_FITNESS_BIKE
            logger.i(
                TAG,
                "Device info: sw=$softwareVersion, hw=$hardwareVersion, serial=$serialNumber, " +
                    "equipmentDeviceId=$equipmentDeviceId, supportedBitFields=${supportedBitFields.size}",
            )
            pollFields = computePollFields(supportedBitFields)
        } else {
            logger.w(TAG, "Expected DeviceInfoResponse, got: $deviceInfo — defaulting equipment to FITNESS_BIKE")
            equipmentDeviceId = V1Message.DEVICE_FITNESS_BIKE
        }

        delay(COMMAND_DELAY_MS)

        // 1. Connect to the equipment device.
        val connectResponse = sendAndAwait(V1Message.Outgoing.Connect(equipmentDeviceId))
            ?: throw IllegalStateException("Connect handshake timed out")
        if (connectResponse !is V1Message.Incoming.ConnectAck) {
            throw IllegalStateException("Expected ConnectAck, got: $connectResponse")
        }
        logger.d(TAG, "Connected to device ${connectResponse.deviceId}")

        delay(COMMAND_DELAY_MS)

        // 2. SupportedDevices → the sub-devices/sensors the controller manages (diagnostic only).
        val supportedDevices = sendAndAwait(V1Message.Outgoing.SupportedDevices())
        if (supportedDevices is V1Message.Incoming.SupportedDevicesResponse) {
            logger.d(TAG, "Sub-devices: ${supportedDevices.deviceIds}")
        } else {
            logger.w(TAG, "SupportedDevices failed: $supportedDevices")
        }

        delay(COMMAND_DELAY_MS)

        // 3. SystemInfo → partNumber, model
        val systemInfo = sendAndAwait(V1Message.Outgoing.SystemInfo())
        if (systemInfo is V1Message.Incoming.SystemInfoResponse) {
            partNumber = systemInfo.partNumber
            model = systemInfo.model
            logger.d(TAG, "System info: partNumber=$partNumber, model=$model")
            powerCurveIndex = DeviceDatabase.powerCurveIndexForPartNumber(partNumber)
            if (powerCurveIndex != null) {
                logger.i(TAG, "Power curve table: $powerCurveIndex (from part number $partNumber)")
            }
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

        delay(COMMAND_DELAY_MS)

        // 6. Read startup fields (device limits + equipment stats)
        readStartupFields(equipmentDeviceId)
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

    private suspend fun readStartupFields(equipmentDeviceId: Int) {
        val response = sendReadWrite(readFields = V1DataField.startupReadFields)
        if (response == null || response.status != V1Message.STATUS_DONE || response.fields.isEmpty()) {
            logger.d(TAG, "Startup field read returned no data: $response")
            return
        }
        val fields = response.fields
        capabilities = V1Capabilities(
            maxGrade = fields[V1DataField.MAX_GRADE],
            minGrade = fields[V1DataField.MIN_GRADE],
            maxKph = fields[V1DataField.MAX_KPH],
            minKph = fields[V1DataField.MIN_KPH],
            maxResistance = fields[V1DataField.MAX_RESISTANCE_LEVEL]?.toInt()?.takeIf { it > 0 },
            equipmentDeviceId = equipmentDeviceId,
        )
        logger.i(TAG, "Capabilities: $capabilities")
        val eqHours = fields[V1DataField.TOTAL_TIME]?.toLong()
        val eqDist = fields[V1DataField.MOTOR_TOTAL_DISTANCE]
        _deviceIdentity.value = _deviceIdentity.value?.copy(equipmentHours = eqHours, equipmentDistance = eqDist)
        logger.i(TAG, "Equipment stats: totalTime=${eqHours}s, totalDistance=$eqDist")
    }

    /**
     * Console init, done while the console is still IDLE (before the workout transition): on devices
     * with a [V1DataField.REQUIRE_START_REQUESTED] bitfield, tell the MCU we'll drive the start
     * ourselves (via the WORKOUT_MODE transition below); otherwise hold off the idle/auto-pause
     * timeout ([V1DataField.IDLE_MODE_LOCKOUT]) so a Zwift-style session keeps streaming when the
     * user briefly stops moving. Mirrors GlassOS's FitPro1 console init (which picks exactly one of
     * the two from the device's bitfield map and never writes either during a workout).
     */
    private suspend fun prepareConsole() {
        when {
            // Couldn't read the device's bitfield map — keep the historical behaviour of writing
            // both, but now un-crammed and while the console is still IDLE (which the MCU expects).
            supportedBitFields.isEmpty() -> {
                writeConsoleField(V1DataField.REQUIRE_START_REQUESTED, FIELD_ENABLED)
                writeConsoleField(V1DataField.IDLE_MODE_LOCKOUT, FIELD_ENABLED)
            }
            V1DataField.REQUIRE_START_REQUESTED.fieldIndex in supportedBitFields ->
                writeConsoleField(V1DataField.REQUIRE_START_REQUESTED, FIELD_ENABLED)
            else ->
                writeConsoleField(V1DataField.IDLE_MODE_LOCKOUT, FIELD_ENABLED)
        }
    }

    private suspend fun writeConsoleField(field: V1DataField, value: Float) {
        sendReadWrite(writeFields = mapOf(field to value), readFields = setOf(field))
        delay(COMMAND_DELAY_MS)
    }

    /**
     * Builds the per-loop read set from [V1DataField.periodicReadFields] intersected with the
     * device's self-declared [supportedBitFields]. We trust the device's declaration: if it didn't
     * claim a field, the MCU won't include bytes for it in the response, and asking anyway would
     * misalign every later field's offset (the bug that produced -10595 kcal / 139 km on the
     * NordicTrack 2950 Argon screenshot).
     *
     * Falls back to the full periodicReadFields set if [supportedBitFields] is empty (handshake
     * couldn't parse the device's bitmask) — that preserves the pre-fix behavior for devices
     * we've always worked with, and the warning makes the fallback visible.
     */
    private fun computePollFields(supportedBitFields: Set<Int>): Set<V1DataField> {
        if (supportedBitFields.isEmpty()) {
            logger.w(
                TAG,
                "Device declared no supportedBitFields; polling the full periodicReadFields set. " +
                    "If the MCU omits any of these fields the decoder will misalign — watch for isTruncated.",
            )
            return V1DataField.periodicReadFields
        }
        val filtered = V1DataField.periodicReadFields.filterTo(mutableSetOf()) { it.fieldIndex in supportedBitFields }
        val omitted = V1DataField.periodicReadFields - filtered
        if (omitted.isNotEmpty()) {
            logger.i(
                TAG,
                "Filtering ${omitted.size} unsupported field(s) from poll: ${omitted.joinToString { it.name }}",
            )
        }
        return filtered
    }

    /**
     * Brings the console up to the running WORKOUT state the way the firmware expects:
     * `IDLE → WARM_UP(10) → RUNNING(2)`, confirming each step by reading [V1DataField.WORKOUT_MODE]
     * back. If the MCU never confirms a step, we log a warning and proceed anyway (a degraded
     * session beats no session) — that warning is the thing to look for in logs when controls
     * (resistance / belt speed) don't respond.
     */
    private suspend fun transitionToRunning() {
        writeAndConfirmWorkoutMode(WorkoutMode.WARM_UP) { it != WorkoutMode.IDLE }
        val running = writeAndConfirmWorkoutMode(WorkoutMode.RUNNING) { it == WorkoutMode.RUNNING }
        logger.i(TAG, "Console state: IDLE → WARM_UP → ${running ?: WorkoutMode.UNKNOWN}")
        _degradedReason.value =
            if (running == WorkoutMode.RUNNING) null
            else "The console didn't confirm the workout started — resistance/speed may not respond"
    }

    private suspend fun writeAndConfirmWorkoutMode(target: WorkoutMode, accept: (WorkoutMode) -> Boolean): WorkoutMode? {
        repeat((STATE_CONFIRM_TIMEOUT_MS / STATE_CONFIRM_POLL_MS).toInt()) { attempt ->
            // Assert the target on the first attempt; subsequent attempts just poll the read-back.
            val response = sendReadWrite(
                writeFields = if (attempt == 0) mapOf(V1DataField.WORKOUT_MODE to target.raw) else emptyMap(),
                readFields = setOf(V1DataField.WORKOUT_MODE),
            )
            val mode = response?.fields?.get(V1DataField.WORKOUT_MODE)?.let { WorkoutMode.fromRaw(it) }
            if (mode != null && accept(mode)) return mode
            delay(STATE_CONFIRM_POLL_MS)
        }
        logger.w(TAG, "Console didn't reach $target — workout may be inactive; continuing")
        return null
    }

    /**
     * Sends one ReadWriteData (writes [writeFields], requests [readFields]) and decodes the single-
     * packet response, returning it (or null on no/garbled response). Used for the startup-field read
     * and the workout-state writes/confirmations — not the poll loop, which reads the multi-packet
     * [V1DataField.periodicReadFields] via [pollOnce].
     */
    private suspend fun sendReadWrite(
        writeFields: Map<V1DataField, Float> = emptyMap(),
        readFields: Set<V1DataField> = emptySet(),
    ): V1Message.Incoming.DataResponse? {
        writeMessage(V1Message.Outgoing.ReadWriteData(writeFields = writeFields, readFields = readFields))
        delay(READ_DELAY_MS)
        val raw = readPacketOrNull() ?: return null
        return V1Codec.decodeSingleDataResponse(raw, readFields.ifEmpty { V1DataField.periodicReadFields })
    }

    private suspend fun writeMessage(message: V1Message.Outgoing) {
        for (packet in V1Codec.encode(message)) transport.write(packet)
    }

    private fun startPollLoop() {
        pollJob = scope.launch {
            while (isActive && _sessionState.value is SessionState.Streaming) {
                val calibDeferred = pendingCalibration
                if (calibDeferred != null) {
                    pendingCalibration = null
                    try {
                        runCalibration()
                        calibDeferred.complete(Unit)
                    } catch (e: CancellationException) {
                        calibDeferred.cancel(e)
                        throw e
                    } catch (e: Exception) {
                        calibDeferred.completeExceptionally(e)
                    }
                } else {
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

        // ReadWriteData targets DEVICE_MAIN (0x02) — FITNESS_BIKE (0x07) returns DEV_NOT_SUPPORTED.
        // pollFields is periodicReadFields ∩ supportedBitFields so the response payload size matches
        // the decoder's positional read; see V1Codec.decodeDataResponseForFields.
        writeMessage(V1Message.Outgoing.ReadWriteData(
            writeFields = writeFields,
            readFields = pollFields,
        ))

        delay(READ_DELAY_MS)

        // No timeout here on purpose: the poll loop is steady-state and just waits for the next reply.
        val firstPacket = transport.readPacket()
        if (firstPacket == null) {
            if (writeFields.isNotEmpty()) {
                pendingWriteMutex.withLock {
                    pendingWriteFields = writeFields + pendingWriteFields
                }
            }
            return
        }

        val decoded = try {
            readResponse(firstPacket, pollFields)
        } catch (e: Exception) {
            logger.w(TAG, "Malformed response (${firstPacket.size} bytes): ${e.message}")
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

            // Edge-triggered: log once when the response shape stops matching the request shape,
            // not every 100ms. Means the MCU is supplying a different field set than its DeviceInfo
            // bitmask declared — keep going (lenient decode produced what it could) but surface it.
            if (decoded.isTruncated && !lastTruncatedSeen) {
                logger.w(
                    TAG,
                    "DataResponse payload size doesn't match the requested ${pollFields.size}-field shape " +
                        "(decoded ${decoded.fields.size} field(s)) — later field offsets may be unreliable.",
                )
            } else if (!decoded.isTruncated && lastTruncatedSeen) {
                logger.i(TAG, "DataResponse payload size now matches the requested field shape again.")
            }
            lastTruncatedSeen = decoded.isTruncated

            applyDataResponse(decoded.fields)
            handleKeyObject(decoded.keyObject)
            estimatePowerIfNeeded()
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
                V1DataField.RESISTANCE -> accumulator.updateResistance(resistance.rawToLevel(value.toInt()))
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
                        when (WorkoutMode.fromRaw(mode)) {
                            WorkoutMode.PAUSE -> accumulator.pause()
                            WorkoutMode.RUNNING -> {
                                accumulator.resume()
                                accumulator.startTimer()
                            }
                            else -> {}
                        }
                    }
                    accumulator.updateWorkoutMode(mode)
                }
                V1DataField.VERTICAL_METER_GAIN -> accumulator.updateVerticalGain(value)
                V1DataField.VERTICAL_METER_NET -> accumulator.updateVerticalNet(value)
                V1DataField.AVERAGE_WATTS -> accumulator.updateAverageWatts(value.toInt())
                V1DataField.AVERAGE_GRADE -> accumulator.updateAverageIncline(value)
                V1DataField.LAP_TIME -> accumulator.updateLapTime(value.toLong())
                V1DataField.RECOVERABLE_PAUSED_TIME -> accumulator.updatePausedTime(value.toLong())
                V1DataField.START_REQUESTED -> accumulator.updateStartRequested(value.toInt() != 0)
                V1DataField.GOAL_TIME -> accumulator.updateGoalTime(value.toLong())
                V1DataField.STROKES -> accumulator.updateStrokeCount(value.toInt())
                V1DataField.STROKES_PER_MINUTE -> accumulator.updateStrokeRate(value.toInt())
                V1DataField.FIVE_HUNDRED_SPLIT -> accumulator.updateSplitTime(value.toInt())
                V1DataField.AVG_FIVE_HUNDRED_SPLIT -> accumulator.updateAvgSplitTime(value.toInt())
                // KEY_OBJECT is decoded onto DataResponse.keyObject and handled in handleKeyObject(),
                // so it never reaches this map — this case only keeps the `when` exhaustive.
                V1DataField.KEY_OBJECT,
                V1DataField.RUNNING_TIME,
                V1DataField.DISTANCE,
                V1DataField.CALORIES,
                V1DataField.MAX_RESISTANCE_LEVEL,
                V1DataField.WATT_GOAL,
                V1DataField.FAN_STATE,
                V1DataField.IDLE_MODE_LOCKOUT,
                V1DataField.REQUIRE_START_REQUESTED,
                V1DataField.VOLUME,
                V1DataField.GEAR,
                V1DataField.PAUSE_TIMEOUT,
                V1DataField.WARMUP_TIMEOUT,
                V1DataField.COOLDOWN_TIMEOUT,
                V1DataField.DISTANCE_GOAL,
                V1DataField.IS_CONSTANT_WATTS_MODE,
                V1DataField.MAX_GRADE,
                V1DataField.MIN_GRADE,
                V1DataField.MAX_KPH,
                V1DataField.MIN_KPH,
                V1DataField.MAX_PULSE,
                V1DataField.MAX_RPM,
                V1DataField.SYSTEM_UNITS,
                V1DataField.MOTOR_TOTAL_DISTANCE,
                V1DataField.TOTAL_TIME,
                V1DataField.IS_READY_TO_DISCONNECT -> { /* write-only, capability, or unprocessed fields */ }
            }
        }
    }

    /**
     * Emits a [ConsoleKey] on each fresh press of the console membrane keypad. KEY_OBJECT reports the
     * *currently-pressed* key (and 0 on release), so we edge-detect: emit when the code changes to a
     * new non-zero value. The equipment's own MCU acts on the resistance/incline/speed keys directly,
     * so we don't drive anything from this — it's exposed for the UI (and diagnostics) only.
     */
    private fun handleKeyObject(keyObject: KeyObject?) {
        val code = keyObject?.code ?: 0
        if (code == lastKeyCode) return
        lastKeyCode = code
        if (code == 0) return
        val key = fitProKeyToConsoleKey(code)
        logger.d(TAG, "Console keypad: code=$code held=${keyObject?.timeHeld ?: 0}ms${key?.let { " ($it)" } ?: ""}")
        key?.let { _consoleKeyPresses.tryEmit(it) }
    }

    private fun fitProKeyToConsoleKey(code: Int): ConsoleKey? = when (code) {
        KEY_SPEED_UP -> ConsoleKey.SPEED_UP
        KEY_SPEED_DOWN -> ConsoleKey.SPEED_DOWN
        KEY_INCLINE_UP -> ConsoleKey.INCLINE_UP
        KEY_INCLINE_DOWN -> ConsoleKey.INCLINE_DOWN
        // GEAR_UP/DOWN map to resistance — on bike consoles the +/- buttons are the resistance/gear
        // selector and there's no separate "gear" the app tracks.
        KEY_RESISTANCE_UP, KEY_GEAR_UP -> ConsoleKey.RESISTANCE_UP
        KEY_RESISTANCE_DOWN, KEY_GEAR_DOWN -> ConsoleKey.RESISTANCE_DOWN
        else -> null // stop / fan / volume / etc. — not mapped (yet)
    }

    private fun estimatePowerIfNeeded() {
        val snapshot = accumulator.snapshot()
        if (snapshot.power != null && snapshot.power != 0) return // MCU provides power
        val speed = snapshot.speed ?: return
        val resistance = snapshot.resistance ?: return
        val maxRes = deviceInfo.maxResistance
        if (maxRes <= 0) return

        val estimated = powerCurveIndex?.let {
            PowerEstimator.estimate(it, speed, resistance, maxRes, deviceInfo.type)
        } ?: PowerEstimator.estimateFallback(speed, resistance, maxRes)

        if (estimated != null && estimated > 0) {
            accumulator.updatePower(estimated)
        }
    }

    private suspend fun runCalibration() {
        logger.i(TAG, "Starting incline calibration")
        var attempts = 0
        while (attempts < MAX_CALIBRATION_ATTEMPTS) {
            val response = sendAndAwait(V1Message.Outgoing.Calibrate())
            logger.d(TAG, "Calibration poll $attempts: $response")
            if (response is V1Message.Incoming.GenericResponse) {
                when (response.status) {
                    V1Message.STATUS_DONE -> {
                        logger.i(TAG, "Incline calibration complete")
                        return
                    }
                    V1Message.STATUS_IN_PROGRESS -> {
                        attempts++
                        delay(CALIBRATION_POLL_MS)
                    }
                    V1Message.STATUS_SECURITY_BLOCK -> {
                        logger.w(TAG, "Security block during calibration — re-verifying")
                        verifySecurity()
                        attempts++
                    }
                    else -> throw IllegalStateException("Calibration failed: status=${response.status}")
                }
            } else {
                throw IllegalStateException("Unexpected calibration response: $response")
            }
        }
        throw IllegalStateException("Calibration timed out after $MAX_CALIBRATION_ATTEMPTS attempts")
    }

    private suspend fun sendAndAwait(message: V1Message.Outgoing): V1Message.Incoming? {
        writeMessage(message)
        delay(READ_DELAY_MS)
        val firstPacket = readPacketOrNull() ?: return null
        return readResponse(firstPacket)
    }

    /** [transport.readPacket] with a safety timeout — a non-responsive MCU must not hang the session. */
    private suspend fun readPacketOrNull(): ByteArray? =
        withTimeoutOrNull(RESPONSE_TIMEOUT_MS) { transport.readPacket() }

    private suspend fun readResponse(
        firstPacket: ByteArray,
        dataResponseFields: Set<V1DataField>? = null,
    ): V1Message.Incoming? {
        if (V1Codec.isMultiPacketHeader(firstPacket)) {
            val expected = V1Codec.expectedPacketCount(firstPacket)
            val packets = mutableListOf(firstPacket)
            repeat(expected) {
                val dataPacket = transport.readPacket() ?: return null
                packets.add(dataPacket)
            }
            return V1Codec.decode(packets, dataResponseFields)
        }
        return V1Codec.decodeSingle(firstPacket, dataResponseFields)
    }

    private fun roundToStep(value: Float, step: Float): Float =
        (value / step).roundToInt() * step


    companion object {
        private const val TAG = "V1Session"
        private const val POLL_INTERVAL_MS = 100L
        private const val COMMAND_DELAY_MS = 100L
        private const val READ_DELAY_MS = 0L
        // Safety timeout for a single MCU response — it normally replies immediately; if it ever
        // doesn't, fail/degrade gracefully instead of hanging the session.
        private const val RESPONSE_TIMEOUT_MS = 1000L
        private const val MAX_CONSECUTIVE_POLL_ERRORS = 10
        private const val CALIBRATION_POLL_MS = 4000L // 4-second poll interval during calibration
        private const val MAX_CALIBRATION_ATTEMPTS = 60 // 4-minute timeout at 4s intervals

        // Confirming a WORKOUT_MODE transition: re-read WORKOUT_MODE every STATE_CONFIRM_POLL_MS,
        // for up to STATE_CONFIRM_TIMEOUT_MS, before giving up and continuing degraded.
        private const val STATE_CONFIRM_POLL_MS = 150L
        private const val STATE_CONFIRM_TIMEOUT_MS = 5_000L

        // Console-init field values: 1 = ENABLED (REQUIRE_START_REQUESTED) / LOCKED (IDLE_MODE_LOCKOUT).
        private const val FIELD_ENABLED = 1f

        // KEY_OBJECT key codes for the console-keypad buttons we forward.
        private const val KEY_SPEED_UP = 3
        private const val KEY_SPEED_DOWN = 4
        private const val KEY_INCLINE_UP = 5
        private const val KEY_INCLINE_DOWN = 6
        private const val KEY_RESISTANCE_UP = 7
        private const val KEY_RESISTANCE_DOWN = 8
        private const val KEY_GEAR_UP = 9
        private const val KEY_GEAR_DOWN = 10
    }
}
