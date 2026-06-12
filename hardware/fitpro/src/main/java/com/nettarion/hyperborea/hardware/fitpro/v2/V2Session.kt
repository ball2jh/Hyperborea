package com.nettarion.hyperborea.hardware.fitpro.v2

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.model.ConsoleKey
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.model.DeviceIdentity
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.model.isBeltBased
import com.nettarion.hyperborea.hardware.fitpro.session.ExerciseDataAccumulator
import com.nettarion.hyperborea.hardware.fitpro.session.FitProSession
import com.nettarion.hyperborea.hardware.fitpro.session.GripHeartRateFilter
import com.nettarion.hyperborea.hardware.fitpro.session.SessionState
import com.nettarion.hyperborea.hardware.fitpro.transport.HidTransport
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

    private val _consoleKeyPresses =
        MutableSharedFlow<ConsoleKey>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val consoleKeyPresses: SharedFlow<ConsoleKey> = _consoleKeyPresses.asSharedFlow()

    private val _degradedReason = MutableStateFlow<String?>(null)
    override val degradedReason: StateFlow<String?> = _degradedReason.asStateFlow()

    /** Latest [V2FeatureId.WORKOUT_STATE] value reported by the console (raw [V2WorkoutMode] ordinal); null until first event. */
    private val _workoutMode = MutableStateFlow<Float?>(null)

    /**
     * The complete feature set the console declared, published once the multi-frame
     * supported-features list terminates (see [V2Message.Incoming.SupportedFeatures]).
     */
    private val _supportedFeatures = MutableStateFlow<Set<V2FeatureId>?>(null)

    /** Union of supported-features frames received so far (the list arrives in pieces). */
    private val featureAccumulator = mutableSetOf<V2FeatureId>()
    private val unknownFeatureCodes = mutableSetOf<Int>()

    /** Raw equipment-type code from the console's [V2FeatureId.DEVICE_TYPE] event; null until reported. */
    private val _reportedDeviceType = MutableStateFlow<Float?>(null)

    /** Product-info fields accumulated from the console's field stream; published on end-of-list. */
    private val productInfoAccumulator = mutableMapOf<Int, String>()
    private val _productInfo = MutableStateFlow<Map<Int, String>?>(null)

    /** Set after the handshake; writes to features the console didn't declare are skipped. */
    private var declaredFeatures: Set<V2FeatureId>? = null

    private var lastKeyCode = 0

    /**
     * Equipment type of the connected machine. Resolved during [start], preferring the console's
     * own [V2FeatureId.DEVICE_TYPE] report (pushed as an initial event after subscribing); when
     * the console doesn't implement that feature we fall back to inferring from the supported
     * feature set — a belt-driven machine reports speed/grade features but no flywheel-resistance
     * features. Defaults to [DeviceType.BIKE] until resolved.
     */
    override var detectedDeviceType: DeviceType = DeviceType.BIKE
        private set

    private var receiveJob: Job? = null
    /** In-flight start-request drive (see [requestWorkoutStart]); completes → next press may retry. */
    private var startRequestJob: Job? = null
    private var lastSentGrade = 0f
    private var lastSentSpeed = 0f
    private val gripHeartRate = GripHeartRateFilter()

    override suspend fun start() {
        if (_sessionState.value is SessionState.Streaming || _sessionState.value is SessionState.Connecting) return

        try {
            _sessionState.value = SessionState.Connecting
            transport.open()

            _sessionState.value = SessionState.Handshaking
            startReceiveLoop()   // must run before the query so the reply isn't raced

            // The console must declare its supported features before we subscribe — it rejects
            // subscriptions to features it doesn't implement.
            val supported = querySupportedFeatures(QUERY_FEATURES_ATTEMPTS)
            if (supported == null) {
                logger.w(TAG, "Console never declared supported features — subscribing unfiltered")
            }
            declaredFeatures = supported

            // Identity (versions, part number, model name, serial) comes from the product-info
            // field stream — queried between the features reply and subscribing, the order the
            // console expects. Optional: a console that doesn't answer still streams data.
            _deviceIdentity.value = queryProductInfo() ?: DeviceIdentity()

            configureSubscriptions(supported)
            writeInitConfiguration(supported)

            // Equipment type: the console reports it directly as the DEVICE_TYPE feature's
            // initial event after subscribing; the supported-feature-set heuristic covers
            // consoles that don't implement it. transitionToWorkout() branches on this.
            detectedDeviceType = resolveDeviceType(supported)

            // Bring the console up to the workout-active state the way the firmware expects.
            transitionToWorkout()

            accumulator.start()
            _sessionState.value = SessionState.Streaming
            logger.i(TAG, "V2 session started")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to start V2 session", e)
            receiveJob?.cancel()
            receiveJob = null
            try { transport.close() } catch (_: Exception) {}
            _sessionState.value = SessionState.Error(e.message ?: "V2 session failed", e)
        }
    }

    override suspend fun stop() {
        receiveJob?.cancel()
        receiveJob = null
        startRequestJob?.cancel()
        startRequestJob = null

        try {
            if (transport.isOpen) {
                haltForTeardown()
                // Return the console to idle before disconnecting
                transport.write(V2Codec.encode(
                    V2Message.Outgoing.WriteFeature(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.NONE.raw),
                ))
                transport.write(V2Codec.encode(V2Message.Outgoing.Unsubscribe(V2FeatureId.subscribable)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Error during V2 session stop: ${e.message}")
        } finally {
            // Always release the USB connection, even when the teardown writes fail on a dead
            // link — skipping this leaks the connection (and the interface claim) until the GC
            // finalizer complains.
            try { transport.close() } catch (e: Exception) { logger.w(TAG, "Transport close failed: ${e.message}") }
        }

        accumulator.reset()
        featureAccumulator.clear()
        unknownFeatureCodes.clear()
        _supportedFeatures.value = null
        _reportedDeviceType.value = null
        productInfoAccumulator.clear()
        _productInfo.value = null
        declaredFeatures = null
        lastKeyCode = 0
        _exerciseData.value = null
        _deviceIdentity.value = null
        _degradedReason.value = null
        _sessionState.value = SessionState.Disconnected
        logger.i(TAG, "V2 session stopped")
    }

    /**
     * Belt machines must be explicitly stopped before the console drops to idle, or the belt keeps
     * running (the V1-confirmed bug; mirrored here for protocol parity). Command belt speed to 0 and
     * `PAUSED` — both halt the belt — then let the writes settle before teardown. V2 is event-driven
     * with no synchronous read-back (and no ready-to-disconnect signal), so unlike V1's graceful
     * teardown this is best-effort. Non-belt machines have nothing the app drives that keeps moving,
     * so this is a no-op for them.
     */
    private suspend fun haltForTeardown() {
        if (!detectedDeviceType.isBeltBased) return
        // No 0-speed write: belt-speed values below 0.5 kph are not accepted on V2 (the console
        // errors them) — PAUSED is what halts the belt.
        // Drop incline to 0 so an incline trainer doesn't park raised after teardown.
        transport.write(V2Codec.encode(V2Message.Outgoing.WriteFeature(V2FeatureId.TARGET_GRADE, 0f)))
        transport.write(V2Codec.encode(
            V2Message.Outgoing.WriteFeature(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.PAUSED.raw),
        ))
        delay(BELT_HALT_SETTLE_MS)
    }

    override suspend fun identify(): DeviceIdentity? {
        try {
            transport.open()
            startReceiveLoop()
            val supported = querySupportedFeatures(attempts = 1)
            logger.i(TAG, if (supported != null) "Console replied with ${supported.size} features" else "Console didn't reply to features query")
            _deviceIdentity.value = queryProductInfo() ?: DeviceIdentity()
            return _deviceIdentity.value
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            logger.e(TAG, "Identify failed", e)
            return null
        } finally {
            receiveJob?.cancel()
            receiveJob = null
            try { transport.close() } catch (_: Exception) {}
        }
    }

    /**
     * Asks for the product-info field stream and builds a [DeviceIdentity] from it. The console
     * streams tagged string fields and terminates with an end-of-list tag; null if it never
     * completes the stream (identity is optional — the session works without it).
     */
    private suspend fun queryProductInfo(): DeviceIdentity? {
        transport.write(V2Codec.encode(V2Message.Outgoing.QueryProductInfo()))
        val fields = withTimeoutOrNull(PRODUCT_INFO_TIMEOUT_MS) {
            _productInfo.filterNotNull().first()
        }
        if (fields == null) {
            logger.w(TAG, "Console didn't complete the product-info stream within ${PRODUCT_INFO_TIMEOUT_MS}ms")
            return null
        }
        val identity = DeviceIdentity(
            serialNumber = fields[V2Codec.PRODUCT_INFO_SERIAL_NUMBER]?.ifBlank { null },
            firmwareVersion = fields[V2Codec.PRODUCT_INFO_SW_VERSION]?.ifBlank { null },
            hardwareVersion = fields[V2Codec.PRODUCT_INFO_MOTOR_CONTROLLER_VERSION]?.ifBlank { null },
            model = fields[V2Codec.PRODUCT_INFO_MODEL_NAME]?.ifBlank { null },
            partNumber = fields[V2Codec.PRODUCT_INFO_HW_PART_NUMBER]?.ifBlank { null },
        )
        logger.i(TAG, "Product info: model=${identity.model}, serial=${identity.serialNumber}, " +
            "fw=${identity.firmwareVersion}, partNumber=${identity.partNumber}")
        return identity
    }

    override suspend fun calibrate() {
        throw UnsupportedOperationException("CalibrateIncline not supported on V2")
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
                V2Message.Outgoing.WriteFeature(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.PAUSED.raw)
            }
            is DeviceCommand.ResumeWorkout -> {
                accumulator.resume()
                V2Message.Outgoing.WriteFeature(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.RUNNING.raw)
            }
            is DeviceCommand.CalibrateIncline -> {
                logger.w(TAG, "CalibrateIncline not supported on V2")
                return
            }
            is DeviceCommand.SetFanSpeed -> V2Message.Outgoing.WriteFeature(
                V2FeatureId.FAN_STATE,
                command.level.toFloat(),
            )
            is DeviceCommand.SetUserWeight -> V2Message.Outgoing.WriteFeature(
                V2FeatureId.USER_WEIGHT_KG,
                command.kg,
            )
            is DeviceCommand.SetVolume,
            is DeviceCommand.SetGear,
            is DeviceCommand.SetDistanceGoal,
            is DeviceCommand.SetWarmupTimeout,
            is DeviceCommand.SetCooldownTimeout,
            is DeviceCommand.SetPauseTimeout,
            is DeviceCommand.SetWarmUpMode,
            is DeviceCommand.SetCoolDownMode,
            is DeviceCommand.SetErgMode -> {
                logger.w(TAG, "${command::class.simpleName} not supported on V2")
                return
            }
        }

        // Never write a feature the console didn't declare — it just replies with an error,
        // and rejected traffic is what we're eliminating. With no declared set (console never
        // answered the query) write anyway.
        val declared = declaredFeatures
        if (declared != null && message.feature !in declared) {
            logger.d(TAG, "Skipping ${command::class.simpleName}: ${message.feature} not declared by this console")
            return
        }

        try {
            transport.write(V2Codec.encode(message))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to write feature", e)
        }
    }

    /**
     * Console keypad. Same code space as the V1 keypad field; events repeat the current code, so
     * emit only on changes and ignore the idle/no-key value. Mostly observe-only — the stream
     * feeds UI and diagnostics — with one exception: on a V2 treadmill the MCU does NOT act on
     * its own Start key (it just forwards the press and waits for the host to drive the workout
     * state machine — see [requestWorkoutStart]), so START doubles as a start request here. This
     * covers firmware that forwards the key without also reporting
     * [V2WorkoutMode.READY_TO_START]; [requestWorkoutStart]'s single-flight guard absorbs the
     * overlap when both arrive.
     */
    private fun handleKeyCode(code: Int) {
        if (code == lastKeyCode) return
        lastKeyCode = code
        if (code == 0) return
        val key = when (code) {
            KEY_START -> ConsoleKey.START
            KEY_STOP -> ConsoleKey.STOP
            KEY_SPEED_UP -> ConsoleKey.SPEED_UP
            KEY_SPEED_DOWN -> ConsoleKey.SPEED_DOWN
            KEY_INCLINE_UP -> ConsoleKey.INCLINE_UP
            KEY_INCLINE_DOWN -> ConsoleKey.INCLINE_DOWN
            // Gear up/down maps to resistance — on bike consoles the +/- buttons are the
            // resistance/gear selector and there's no separate "gear" the app tracks.
            KEY_RESISTANCE_UP, KEY_GEAR_UP -> ConsoleKey.RESISTANCE_UP
            KEY_RESISTANCE_DOWN, KEY_GEAR_DOWN -> ConsoleKey.RESISTANCE_DOWN
            else -> null // fan / volume / etc. — not mapped (yet)
        }
        logger.d(TAG, "Console keypad: code=$code${key?.let { " ($it)" } ?: ""}")
        key?.let { _consoleKeyPresses.tryEmit(it) }
        if (key == ConsoleKey.START) requestWorkoutStart("physical Start key")
    }

    /**
     * Drives the workout to RUNNING in response to the user's physical Start press. On V2 the
     * MCU never starts the workout itself — pressing Start makes it report
     * [V2WorkoutMode.READY_TO_START] (and/or forward the key event) and wait for the HOST to
     * write the state machine; the stock GlassOS service translates that report into
     * `START_REQUESTED` and its UI starts the workout (see `V2ConsoleStateHolder` in the
     * decompiled sources, and the LargeX field logs where `WARM_UP` writes from idle bounce with
     * `WRITE_VALUE_NOT_ALLOWED` and Start presses go nowhere). We are that host now, so both
     * triggers land here.
     *
     * Treadmill-only: bikes/ellipticals are driven straight to RUNNING at arm time and have no
     * Start gate. Single-flight: a completed attempt (confirmed or timed out) re-arms, so the
     * user's next press simply retries. Skips WARM_UP when resuming from PAUSED — RUNNING alone
     * is the resume transition.
     */
    private fun requestWorkoutStart(trigger: String) {
        if (detectedDeviceType != DeviceType.TREADMILL) return
        if (_sessionState.value !is SessionState.Streaming) return
        val from = _workoutMode.value?.let { V2WorkoutMode.fromRaw(it) }
        if (from == V2WorkoutMode.RUNNING) return
        if (startRequestJob?.isActive == true) return

        startRequestJob = scope.launch {
            logger.i(TAG, "Start requested ($trigger, console state=${from ?: "unreported"}) — driving workout to RUNNING")
            try {
                if (from != V2WorkoutMode.PAUSED) {
                    writeWorkoutState(V2WorkoutMode.WARM_UP)
                    delay(START_WRITE_GAP_MS)
                }
                writeWorkoutState(V2WorkoutMode.RUNNING)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "Start-request writes failed", e)
                return@launch
            }
            val running = confirmWorkoutMode("reach RUNNING after Start") { it == V2WorkoutMode.RUNNING }
            if (running) logger.i(TAG, "Console workout state: RUNNING (start request honored)")
        }
    }

    /**
     * Asks the console for its supported-features list, waiting [SUPPORTED_FEATURES_TIMEOUT_MS]
     * per attempt (it's the console's slowest reply). The list arrives as a frame series ending
     * in an empty terminator frame; if frames arrived but the terminator never did, the partial
     * union is better than nothing. Returns null only if the console never answered at all.
     */
    private suspend fun querySupportedFeatures(attempts: Int): Set<V2FeatureId>? {
        repeat(attempts) { attempt ->
            transport.write(V2Codec.encode(V2Message.Outgoing.QueryFeatures()))
            val features = withTimeoutOrNull(SUPPORTED_FEATURES_TIMEOUT_MS) {
                _supportedFeatures.filterNotNull().first()
            }
            if (features != null) return features
            logger.w(TAG, "No complete supported-features reply within ${SUPPORTED_FEATURES_TIMEOUT_MS}ms (attempt ${attempt + 1}/$attempts)")
        }
        if (featureAccumulator.isNotEmpty()) {
            logger.w(TAG, "Supported-features list never terminated — using ${featureAccumulator.size} accumulated features")
            return featureAccumulator.toSet()
        }
        return null
    }

    /**
     * Clears whatever subscriptions a previous client left active (an empty Unsubscribe), then
     * subscribes to the features we want — restricted to what the console declared it supports,
     * because consoles reject Subscribe commands naming features they don't implement. With a
     * null [supported] (console never answered the query) we subscribe unfiltered and let the
     * console sort it out.
     */
    private suspend fun configureSubscriptions(supported: Set<V2FeatureId>?) {
        transport.write(V2Codec.encode(V2Message.Outgoing.Unsubscribe(emptyList())))

        val wanted = V2FeatureId.subscribable.filter { supported == null || it in supported }
        if (wanted.isEmpty()) {
            logger.w(TAG, "Console supports none of the features we want — no subscriptions made")
            return
        }
        val batches = wanted.chunked(MAX_SUBSCRIBE_BATCH)
        logger.d(TAG, "Subscribing to ${wanted.size} features in ${batches.size} batches")
        for (batch in batches) {
            transport.write(V2Codec.encode(V2Message.Outgoing.Subscribe(batch)))
        }
    }

    /**
     * One-shot configuration writes mirroring the stock service's connect sequence
     * (FitPro2Console): `HEART_BEAT_INTERVAL = 720` (a single configure of the MCU's heartbeat
     * cadence — the stock stack writes it once and never again; there is no periodic keepalive
     * on the wire) and `IDLE_SYSTEM_MODE_LOCK = UNLOCKED`. Strictly gated on the console
     * DECLARING each feature — unlike user-driven commands there's no value in attempting these
     * blind, and undeclared writes just generate rejection noise. Closing this bring-up gap is
     * part of ruling out "missing init precondition" as the cause of the LargeX refusing
     * WORKOUT_STATE writes from idle.
     */
    private suspend fun writeInitConfiguration(supported: Set<V2FeatureId>?) {
        if (supported == null) return
        if (V2FeatureId.HEART_BEAT_INTERVAL in supported) {
            transport.write(V2Codec.encode(
                V2Message.Outgoing.WriteFeature(V2FeatureId.HEART_BEAT_INTERVAL, HEART_BEAT_INTERVAL_MS),
            ))
        }
        if (V2FeatureId.IDLE_SYSTEM_MODE_LOCK in supported) {
            transport.write(V2Codec.encode(
                V2Message.Outgoing.WriteFeature(V2FeatureId.IDLE_SYSTEM_MODE_LOCK, IDLE_MODE_UNLOCKED),
            ))
        }
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

    private var lastLogTimeMs = 0L

    private fun handleIncoming(data: ByteArray) {
        val message = V2Codec.decode(data) ?: return

        when (message) {
            is V2Message.Incoming.Event -> {
                applyEvent(message.feature, message.value)
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
            is V2Message.Incoming.SupportedFeatures -> {
                if (message.isEndOfList) {
                    if (unknownFeatureCodes.isNotEmpty()) {
                        logger.i(TAG, "Console declared ${unknownFeatureCodes.size} feature ids we don't use: $unknownFeatureCodes")
                    }
                    logger.i(TAG, "Supported features complete: ${featureAccumulator.map { it.name }}")
                    _supportedFeatures.value = featureAccumulator.toSet()
                } else {
                    featureAccumulator += message.features
                    unknownFeatureCodes += message.unknownCodes
                    logger.d(TAG, "Supported-features frame: ${message.features.map { it.name }}" +
                        if (message.unknownCodes.isNotEmpty()) " + unknown ${message.unknownCodes}" else "")
                }
            }
            is V2Message.Incoming.ProductInfoField -> {
                if (message.isEndOfList) {
                    _productInfo.value = productInfoAccumulator.toMap()
                } else {
                    productInfoAccumulator[message.fieldType] = message.text
                }
            }
            is V2Message.Incoming.Acknowledge ->
                logger.d(TAG, "ACK: ${message.type}")
            is V2Message.Incoming.Error ->
                logger.w(TAG, "Console rejected ${message.describe()}")
            is V2Message.Incoming.Unknown ->
                logger.d(TAG, "Unknown message: ${message.raw.size} bytes")
        }
    }

    private fun applyEvent(feature: V2FeatureId, value: Float) {
        when (feature) {
            V2FeatureId.DEVICE_TYPE -> _reportedDeviceType.value = value
            V2FeatureId.KEY_COOKED -> handleKeyCode(value.toInt())
            V2FeatureId.REQUEST_DISCONNECT -> if (value != 0f) {
                // The console wants the link back (e.g. its own maintenance flow). Surface it
                // loudly; deciding to comply is the orchestrator's call once we see this in the
                // field and know what triggers it.
                logger.w(TAG, "Console requested disconnect (value=$value)")
            }
            V2FeatureId.TOTAL_IN_USE_SECONDS -> _deviceIdentity.value =
                (_deviceIdentity.value ?: DeviceIdentity()).copy(equipmentHours = value.toLong())
            V2FeatureId.TOTAL_MACHINE_DISTANCE -> _deviceIdentity.value =
                (_deviceIdentity.value ?: DeviceIdentity()).copy(equipmentDistance = value)
            V2FeatureId.USER_WEIGHT_KG -> { /* echo of our own write — not exercise data */ }
            V2FeatureId.FAN_STATE -> { /* console fan state — not exercise data */ }
            V2FeatureId.WATTS -> accumulator.updatePower(value.toInt())
            V2FeatureId.RPM -> accumulator.updateCadence(value.toInt())
            V2FeatureId.CURRENT_KPH -> accumulator.updateSpeed(value)
            V2FeatureId.TARGET_RESISTANCE -> accumulator.updateResistance(value.toInt())
            V2FeatureId.CURRENT_GRADE -> accumulator.updateIncline(value)
            // Grip HR is a noisy analog contact reading — gate + smooth it, clearing on contact loss.
            // External BLE HRMs bypass this and are merged in the orchestrator.
            V2FeatureId.PULSE -> accumulator.updateHeartRate(gripHeartRate.update(value.toInt()))
            V2FeatureId.DISTANCE -> accumulator.updateDistance(value)
            V2FeatureId.CURRENT_CALORIES -> accumulator.updateCalories(value.toInt())
            V2FeatureId.RUNNING_TIME -> accumulator.updateElapsedTime(value.toLong())
            V2FeatureId.TARGET_KPH -> accumulator.updateTargetSpeed(value)
            V2FeatureId.TARGET_GRADE -> accumulator.updateTargetIncline(value)
            V2FeatureId.SYSTEM_MODE -> { /* System on/standby/sleep — not the workout state, and not exercise data */ }
            // Write-only init configuration — never subscribed, but a console may echo writes back.
            V2FeatureId.HEART_BEAT_INTERVAL, V2FeatureId.IDLE_SYSTEM_MODE_LOCK -> { }
            // Translated to V1 [com.nettarion.hyperborea.hardware.fitpro.v1.WorkoutMode] numbering
            // when pushed to the accumulator, so the orchestrator's workout-mode monitor (which
            // uses V1 codes for DMK / IDLE / RUNNING) reacts uniformly to V1 and V2 sessions.
            V2FeatureId.WORKOUT_STATE -> {
                _workoutMode.value = value
                val mode = V2WorkoutMode.fromRaw(value)
                // Info-level on purpose: which states a console actually reports (and when) is
                // the load-bearing fact in field logs — e.g. whether READY_TO_START follows a
                // Start-key press. Low volume: V2 events fire on change only.
                logger.i(TAG, "Console workout state event: $mode (raw=$value)")
                // READY_TO_START is the V2 MCU's "user pressed the physical Start key" report —
                // it parks there waiting for the host to start the workout. Mirrors the stock
                // service's START_REQUESTED handling.
                if (mode == V2WorkoutMode.READY_TO_START) requestWorkoutStart("console reported READY_TO_START")
                accumulator.updateWorkoutMode(v2WorkoutStateToV1Code(mode))
            }
            V2FeatureId.MAX_RESISTANCE -> { /* Device capability, not exercise data */ }
            V2FeatureId.GOAL_WATTS -> accumulator.updateTargetPower(value.toInt())
        }
    }

    private fun roundToStep(value: Float, step: Float): Float =
        (value / step).roundToInt() * step

    /**
     * Brings the console up to the workout-active state the way the firmware expects. Two paths,
     * mirroring [com.nettarion.hyperborea.hardware.fitpro.v1.V1Session.transitionToActive]:
     *
     * - **Treadmill / incline trainer**: try `WARM_UP` and park. The belt is gated on the
     *   physical Start key; some firmware accepts the arm-time `WARM_UP` (and self-runs on the
     *   key), other firmware (LargeX) rejects any write from idle with `WRITE_VALUE_NOT_ALLOWED`
     *   and instead reports [V2WorkoutMode.READY_TO_START] when the key is pressed, expecting
     *   the host to drive the workout — [requestWorkoutStart] handles that. Either way the
     *   orchestrator parks in
     *   [com.nettarion.hyperborea.core.orchestration.OrchestratorState.AwaitingConsoleStart]
     *   until a `RUNNING` event promotes it.
     * - **Bike / elliptical / rower**: drive the state machine ourselves —
     *   `NONE → WARM_UP → RUNNING`, confirming each step from the [V2FeatureId.WORKOUT_STATE]
     *   events. If the console never confirms, log a warning and continue degraded.
     */
    private suspend fun transitionToWorkout() {
        if (detectedDeviceType == DeviceType.TREADMILL) {
            writeWorkoutState(V2WorkoutMode.WARM_UP)
            confirmWorkoutMode("leave idle") { it != V2WorkoutMode.NONE && it != V2WorkoutMode.READY_TO_START }
            logger.i(TAG, "Console workout state: NONE → WARM_UP (awaiting physical Start key)")
            _degradedReason.value = null
            return
        }

        writeWorkoutState(V2WorkoutMode.WARM_UP)
        confirmWorkoutMode("leave idle") { it != V2WorkoutMode.NONE && it != V2WorkoutMode.READY_TO_START }
        writeWorkoutState(V2WorkoutMode.RUNNING)
        val running = confirmWorkoutMode("reach RUNNING") { it == V2WorkoutMode.RUNNING }
        logger.i(TAG, "Console workout state: NONE → WARM_UP → ${if (running) V2WorkoutMode.RUNNING else V2WorkoutMode.UNKNOWN}")
        _degradedReason.value =
            if (running) null
            else "The console didn't confirm the workout started — resistance/speed may not respond"
    }

    /**
     * Resolves the equipment type, preferring the console's own report: when it declared the
     * [V2FeatureId.DEVICE_TYPE] feature, wait briefly for its initial event (pushed right after
     * subscribing) and map the code. Otherwise — or if the event never arrives, or carries a code
     * outside the exercise-equipment range — fall back to [deriveDeviceType].
     */
    private suspend fun resolveDeviceType(supported: Set<V2FeatureId>?): DeviceType {
        if (supported != null && V2FeatureId.DEVICE_TYPE in supported) {
            val raw = withTimeoutOrNull(DEVICE_TYPE_TIMEOUT_MS) {
                _reportedDeviceType.filterNotNull().first()
            }
            val mapped = raw?.let { mapReportedDeviceType(it) }
            if (mapped != null) {
                logger.i(TAG, "Detected device type: $mapped (console-reported code ${raw.toInt()})")
                return mapped
            }
            logger.w(TAG, "Console-reported device type unusable (raw=$raw) — falling back to feature heuristic")
        }
        if (supported == null) {
            logger.w(TAG, "Assuming $detectedDeviceType (no feature list to infer from)")
            return detectedDeviceType
        }
        return deriveDeviceType(supported).also {
            logger.i(TAG, "Detected device type: $it (inferred from ${supported.size} features)")
        }
    }

    /**
     * Equipment-type codes the console reports in the [V2FeatureId.DEVICE_TYPE] feature, mapped
     * to our coarser model. Codes outside the exercise-equipment range (wearables, controllers,
     * weight machines…) return null and defer to the feature heuristic.
     */
    private fun mapReportedDeviceType(raw: Float): DeviceType? = when (raw.toInt()) {
        4, 5 -> DeviceType.TREADMILL      // treadmill, incline trainer — both belt-based
        6, 9, 19 -> DeviceType.ELLIPTICAL // elliptical variants and striders
        7, 8 -> DeviceType.BIKE           // exercise bike, spin bike
        20 -> DeviceType.ROWER
        else -> null
    }

    /**
     * Heuristic fallback for consoles that don't implement the device-type feature: infer from
     * the feature set the console declared. Treadmills / incline trainers report belt-speed and
     * grade features but no flywheel resistance; bikes, ellipticals and rowers report resistance
     * features.
     */
    private fun deriveDeviceType(features: Set<V2FeatureId>): DeviceType {
        val hasResistance = V2FeatureId.TARGET_RESISTANCE in features ||
            V2FeatureId.MAX_RESISTANCE in features
        val hasBeltSpeed = V2FeatureId.TARGET_KPH in features || V2FeatureId.CURRENT_KPH in features
        val hasGrade = V2FeatureId.TARGET_GRADE in features || V2FeatureId.CURRENT_GRADE in features
        return when {
            !hasResistance && (hasBeltSpeed || hasGrade) -> DeviceType.TREADMILL
            else -> DeviceType.BIKE
        }
    }

    /**
     * Translates V2's [V2WorkoutMode] ordinal to the V1
     * [com.nettarion.hyperborea.hardware.fitpro.v1.WorkoutMode] raw value the orchestrator's
     * workout-mode monitor expects (V1 numbering is the lingua franca because V1 is older). The
     * `OFF_MACHINE` state has no V1 equivalent — the closest semantic match is `DMK` (user not on
     * device / not driving telemetry), so the monitor's safety-pause path fires for both.
     */
    private fun v2WorkoutStateToV1Code(mode: V2WorkoutMode): Int = when (mode) {
        V2WorkoutMode.RUNNING -> V1_WORKOUT_MODE_RUNNING
        V2WorkoutMode.PAUSED -> V1_WORKOUT_MODE_PAUSE
        V2WorkoutMode.OFF_MACHINE -> V1_WORKOUT_MODE_DMK
        V2WorkoutMode.WARM_UP -> V1_WORKOUT_MODE_WARM_UP
        V2WorkoutMode.COOL_DOWN -> V1_WORKOUT_MODE_COOL_DOWN
        V2WorkoutMode.NONE,
        V2WorkoutMode.READY_TO_START,
        V2WorkoutMode.RESULTS -> V1_WORKOUT_MODE_IDLE
        V2WorkoutMode.UNKNOWN -> V1_WORKOUT_MODE_UNKNOWN
    }

    private suspend fun writeWorkoutState(mode: V2WorkoutMode) {
        transport.write(V2Codec.encode(V2Message.Outgoing.WriteFeature(V2FeatureId.WORKOUT_STATE, mode.raw)))
    }

    /** Waits (up to [STATE_CONFIRM_TIMEOUT_MS]) for a [V2FeatureId.WORKOUT_STATE] event satisfying [accept]. */
    private suspend fun confirmWorkoutMode(what: String, accept: (V2WorkoutMode) -> Boolean): Boolean {
        val ok = withTimeoutOrNull(STATE_CONFIRM_TIMEOUT_MS) {
            _workoutMode.filterNotNull().map { V2WorkoutMode.fromRaw(it) }.first { accept(it) }
            true
        } != null
        if (!ok) logger.w(TAG, "Console didn't $what — workout may be inactive; continuing")
        return ok
    }

    companion object {
        private const val TAG = "V2Session"

        // Belt-machine halt on stop: let the speed-0 + PAUSED writes settle before teardown.
        private const val BELT_HALT_SETTLE_MS = 200L
        // Start request: brief gap so the MCU lands in WARM_UP before the RUNNING write follows.
        private const val START_WRITE_GAP_MS = 150L
        // Init configuration values — mirror the stock service's one-shot connect writes.
        private const val HEART_BEAT_INTERVAL_MS = 720f
        private const val IDLE_MODE_UNLOCKED = 0f
        private const val MAX_SUBSCRIBE_BATCH = 8
        // How long to wait for the console to confirm a WORKOUT_STATE transition before continuing degraded.
        private const val STATE_CONFIRM_TIMEOUT_MS = 5_000L
        // The features query is the console's slowest reply — give it longer than other commands,
        // and retry: subscriptions depend on its answer.
        private const val SUPPORTED_FEATURES_TIMEOUT_MS = 4_000L
        private const val QUERY_FEATURES_ATTEMPTS = 3
        // The DEVICE_TYPE initial event lands right after the subscribe ACKs — a short wait.
        private const val DEVICE_TYPE_TIMEOUT_MS = 2_000L
        // Product info streams many small frames — same long-command class as the features query.
        private const val PRODUCT_INFO_TIMEOUT_MS = 4_000L

        // Console keypad codes (same space as the V1 keypad field).
        private const val KEY_STOP = 1
        private const val KEY_START = 2
        private const val KEY_SPEED_UP = 3
        private const val KEY_SPEED_DOWN = 4
        private const val KEY_INCLINE_UP = 5
        private const val KEY_INCLINE_DOWN = 6
        private const val KEY_RESISTANCE_UP = 7
        private const val KEY_RESISTANCE_DOWN = 8
        private const val KEY_GEAR_UP = 9
        private const val KEY_GEAR_DOWN = 10

        // V1 [com.nettarion.hyperborea.hardware.fitpro.v1.WorkoutMode] raw codes used by the
        // orchestrator's workout-mode monitor — kept here as a translation target for V2's WORKOUT_STATE.
        private const val V1_WORKOUT_MODE_UNKNOWN = 0
        private const val V1_WORKOUT_MODE_IDLE = 1
        private const val V1_WORKOUT_MODE_RUNNING = 2
        private const val V1_WORKOUT_MODE_PAUSE = 3
        private const val V1_WORKOUT_MODE_DMK = 8
        private const val V1_WORKOUT_MODE_WARM_UP = 10
        private const val V1_WORKOUT_MODE_COOL_DOWN = 11
    }
}
