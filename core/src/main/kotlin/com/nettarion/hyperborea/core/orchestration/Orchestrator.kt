package com.nettarion.hyperborea.core.orchestration

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.RetryPolicy
import com.nettarion.hyperborea.core.adapter.AdapterState
import com.nettarion.hyperborea.core.adapter.HardwareAdapter
import com.nettarion.hyperborea.core.adapter.SensorAdapter
import com.nettarion.hyperborea.core.adapter.SensorReading
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.model.FanMode
import com.nettarion.hyperborea.core.profile.RideRecorder
import com.nettarion.hyperborea.core.profile.UserPreferences
import com.nettarion.hyperborea.core.system.SystemController
import com.nettarion.hyperborea.core.system.SystemMonitor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class Orchestrator(
    private val systemMonitor: SystemMonitor,
    private val systemController: SystemController,
    private val ecosystemManager: EcosystemManager,
    private val hardwareAdapter: HardwareAdapter,
    private val broadcastManager: BroadcastManager,
    private val rideRecorder: RideRecorder,
    private val userPreferences: UserPreferences,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
    private val sensorAdapter: SensorAdapter? = null,
) {

    private val _state = MutableStateFlow<OrchestratorState>(OrchestratorState.Idle)
    val state: StateFlow<OrchestratorState> = _state.asStateFlow()
    val lastSavedRideId: StateFlow<Long?> = rideRecorder.lastSavedRideId

    private val mutex = Mutex()
    private var hardwareMonitorJob: Job? = null
    private var commandPipelineJob: Job? = null
    private var workoutModeMonitorJob: Job? = null
    private var probeJob: Job? = null

    private val hardwareRetryPolicy = RetryPolicy(maxAttempts = 5, initialDelayMs = 2000, maxDelayMs = 15000)
    @Volatile
    private var isReconnecting = false
    private var preservedElapsedSeconds: Long = 0L

    /**
     * Best-effort, *passive* pre-identification of the hardware while idle. Only proceeds when every
     * prerequisite is already satisfied — it never actively fulfils anything (no USB-permission
     * dialog) and never surfaces the [OrchestratorState.Error] state: any failure (prereqs not met,
     * identify failed, exception) just leaves the orchestrator in [OrchestratorState.Idle]. Active
     * fulfilment + dialogs + errors are the responsibility of [start]. [startProbing] re-runs this
     * each time the USB becomes accessible.
     */
    suspend fun probe(): DeviceInfo? {
        mutex.withLock {
            if (_state.value !is OrchestratorState.Idle) return null

            try {
                if (!arePrerequisitesMet()) {
                    logger.d(TAG, "Probe skipped — hardware not ready yet; staying idle")
                    return null
                }

                _state.value = OrchestratorState.Preparing("Identifying hardware")
                val deviceInfo = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                    hardwareAdapter.identify()
                }
                if (deviceInfo == null) {
                    _state.value = OrchestratorState.Idle
                    logger.d(TAG, "Probe: hardware did not identify; staying idle")
                    return null
                }

                broadcastManager.updateDeviceInfo(deviceInfo)
                _state.value = OrchestratorState.Idle
                logger.i(TAG, "Probe complete: ${deviceInfo.name}")
                return deviceInfo
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                logger.w(TAG, "Probe failed (will retry on start): ${e.message}")
                _state.value = OrchestratorState.Idle
                return null
            }
        }
    }

    /**
     * Continuously pre-identifies the hardware while idle: re-runs [probe] each time the FitPro USB
     * becomes accessible (which auto-grants on attach when the app is the foreground/HOME app), so
     * the dashboard shows the device name without the user doing anything — and stops probing once
     * it's identified. `distinctUntilChanged` + the `deviceInfo == null` guard keep the bike's
     * ~20 s USB power-cycle from triggering a probe storm. Idempotent; the first `StateFlow`
     * emission performs the initial probe.
     */
    fun startProbing() {
        if (probeJob?.isActive == true) return
        probeJob = scope.launch {
            systemMonitor.snapshot
                .map { snap -> hardwareAdapter.prerequisites.all { it.isMet(snap) } }
                .distinctUntilChanged()
                .collect { hwReady ->
                    if (hwReady && _state.value is OrchestratorState.Idle && hardwareAdapter.deviceInfo.value == null) {
                        probe()
                    }
                }
        }
    }

    fun stopProbing() {
        probeJob?.cancel()
        probeJob = null
    }

    /**
     * Read-only check used by [probe]: are all ecosystem + hardware prerequisites already satisfied
     * and can the hardware operate? Refreshes the snapshot first so the ecosystem checks see fresh
     * package/component state. Never calls a prerequisite's `fulfill` and never touches [_state].
     */
    private suspend fun arePrerequisitesMet(): Boolean {
        refreshAndAwait()
        val snapshot = systemMonitor.snapshot.value
        return ecosystemManager.prerequisites.all { it.isMet(snapshot) } &&
            hardwareAdapter.prerequisites.all { it.isMet(snapshot) } &&
            hardwareAdapter.canOperate(snapshot)
    }

    suspend fun start() {
        mutex.withLock {
            val current = _state.value
            if (current is OrchestratorState.Running ||
                current is OrchestratorState.Preparing ||
                current is OrchestratorState.Paused ||
                current is OrchestratorState.AwaitingConsoleStart
            ) {
                logger.d(TAG, "start() ignored (current state: $current)")
                return
            }

            try {
                startInternal()
            } catch (e: Exception) {
                logger.e(TAG, "Unexpected error during start", e)
                _state.value = OrchestratorState.Error("Unexpected: ${e.message}", e)
            }
        }
    }

    private suspend fun ensureReady(): Boolean {
        _state.value = OrchestratorState.Preparing("Fulfilling ecosystem prerequisites")
        if (!fulfillPrerequisites(ecosystemManager.prerequisites, "ecosystem")) return false

        refreshAndAwait()

        _state.value = OrchestratorState.Preparing("Fulfilling hardware prerequisites")
        if (!fulfillPrerequisites(hardwareAdapter.prerequisites, "hardware")) return false

        refreshAndAwait()

        val snapshot = systemMonitor.snapshot.value
        logger.d(TAG, "Snapshot: USB=${snapshot.status.isUsbHostAvailable}, " +
            "BLE=${snapshot.status.isBluetoothLeAdvertisingSupported}, " +
            "WiFi=${snapshot.status.isWifiEnabled}")
        if (!hardwareAdapter.canOperate(snapshot)) {
            val msg = "Hardware cannot operate with current system state"
            logger.e(TAG, msg)
            _state.value = OrchestratorState.Error(msg)
            return false
        }
        return true
    }

    private suspend fun startInternal() {
        if (!ensureReady()) {
            logger.d(TAG, "startInternal() aborted: ensureReady() failed")
            return
        }

        // Connect hardware
        _state.value = OrchestratorState.Preparing("Connecting to bike")
        hardwareAdapter.connect()

        val hardwareState = hardwareAdapter.state.value
        if (hardwareState is AdapterState.Error) {
            val msg = "Hardware: ${hardwareState.message}"
            logger.e(TAG, msg)
            _state.value = OrchestratorState.Error(msg, hardwareState.cause)
            return
        }

        // Connect sensor if available (non-fatal)
        connectSensor()

        // Wire data source to broadcasts (merge sensor readings if available)
        val deviceInfo = hardwareAdapter.deviceInfo.value
            ?: throw IllegalStateException("Hardware connected but deviceInfo is null")
        val mergedData: StateFlow<ExerciseData?> = if (sensorAdapter != null) {
            combine(hardwareAdapter.exerciseData, sensorAdapter.reading) { exercise, sensor ->
                if (exercise == null) return@combine null
                when (sensor) {
                    is SensorReading.HeartRate -> exercise.copy(heartRate = sensor.bpm)
                    null -> exercise
                }
            }.stateIn(scope, SharingStarted.Eagerly, hardwareAdapter.exerciseData.value)
        } else {
            hardwareAdapter.exerciseData
        }
        broadcastManager.connectDataSource(mergedData)
        broadcastManager.updateDeviceInfo(deviceInfo)

        // Command pipeline: broadcasts → hardware
        commandPipelineJob = scope.launch {
            broadcastManager.incomingCommands
                .catch { e -> logger.e(TAG, "Command pipeline error", e) }
                .collect { command ->
                    try {
                        logger.d(TAG, "Forwarding command: $command")
                        hardwareAdapter.sendCommand(command)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { logger.e(TAG, "Failed to forward command", e) }
                }
        }

        // Treadmills park in AwaitingConsoleStart: the MCU gates belt motion on the physical Start
        // key, so the WORKOUT_MODE poll (handled below) drives the AwaitingConsoleStart → Running
        // promotion when the user presses it. Other devices go straight to Running.
        if (deviceInfo.type == DeviceType.TREADMILL) {
            _state.value = OrchestratorState.AwaitingConsoleStart(CONSOLE_START_MESSAGE)
            logger.i(TAG, "Treadmill armed in WARM_UP — awaiting physical Start key")
        } else {
            enterRunning(mergedData)
        }

        // Workout-mode monitor:
        //  - WORKOUT_MODE_RUNNING from AwaitingConsoleStart → promote to Running (user pressed the
        //    physical Start key; the MCU completed the WARM_UP → RUNNING transition).
        //  - WORKOUT_MODE_DMK from Running → Paused (safety key removed).
        //  - WORKOUT_MODE_IDLE from Paused → Running (safety key re-inserted) OR from
        //    Running/AwaitingConsoleStart → tear the workout down (user pressed the physical Stop
        //    key; symmetrical with app-side Stop).
        workoutModeMonitorJob = scope.launch {
            hardwareAdapter.exerciseData.filterNotNull()
                .collect { data ->
                    when (data.workoutMode) {
                        WORKOUT_MODE_RUNNING -> {
                            if (_state.value is OrchestratorState.AwaitingConsoleStart) {
                                logger.i(TAG, "Console Start pressed — entering Running")
                                enterRunning(mergedData)
                            }
                        }
                        WORKOUT_MODE_DMK -> {
                            if (_state.value is OrchestratorState.Running) {
                                logger.w(TAG, "Safety key removed — pausing")
                                _state.value = OrchestratorState.Paused
                            }
                            // From AwaitingConsoleStart: ignore — the console's own
                            // "INSERT SAFETY KEY" hardware indicator covers this case.
                        }
                        WORKOUT_MODE_IDLE -> {
                            val current = _state.value
                            when (current) {
                                is OrchestratorState.Paused -> {
                                    logger.i(TAG, "Safety key re-inserted — resuming")
                                    hardwareAdapter.sendCommand(DeviceCommand.ResumeWorkout)
                                    _state.value = OrchestratorState.Running(degraded = hardwareAdapter.degradedReason.value)
                                }
                                is OrchestratorState.Running, is OrchestratorState.AwaitingConsoleStart -> {
                                    // User pressed the physical Stop key. The Dashboard's
                                    // save-ride dialog gates on elapsed ≥ 60 s, so calling stop()
                                    // with saveRide=false from AwaitingConsoleStart (elapsed=0)
                                    // is a clean silent teardown.
                                    val wasRunning = current is OrchestratorState.Running
                                    logger.i(TAG, "Console returned to IDLE — stopping workout (running=$wasRunning)")
                                    scope.launch { stop(saveRide = wasRunning) }
                                }
                                else -> {}
                            }
                        }
                    }
                }
        }

        // Hardware disconnect monitor — reconnect with backoff
        hardwareMonitorJob = scope.launch {
            hardwareAdapter.state.collect { hwState ->
                if ((hwState is AdapterState.Error || hwState is AdapterState.Inactive)
                    && _state.value.isWorkoutActive() && !isReconnecting) {
                    isReconnecting = true
                    try {
                        val reason = if (hwState is AdapterState.Error) hwState.message else "Hardware disconnected"
                        logger.w(TAG, "$reason -- attempting reconnect")
                        ensureActive()
                        if (!_state.value.isWorkoutActive()) return@collect
                        mutex.withLock {
                            if (_state.value.isWorkoutActive()) {
                                // Preserve AwaitingConsoleStart across the dropout — surface the
                                // disconnect via degraded on Running/Paused only.
                                _state.value = when (_state.value) {
                                    is OrchestratorState.AwaitingConsoleStart -> _state.value
                                    else -> OrchestratorState.Running(degraded = reason)
                                }
                            }
                        }

                        var reconnected = false
                        for (attempt in 1..hardwareRetryPolicy.maxAttempts) {
                            delay(hardwareRetryPolicy.delayForAttempt(attempt))
                            val current = hardwareAdapter.exerciseData.value
                            if (current != null && current.elapsedTime > 0) {
                                preservedElapsedSeconds += current.elapsedTime
                            }
                            hardwareAdapter.disconnect()
                            hardwareAdapter.setInitialElapsedTime(preservedElapsedSeconds)
                            hardwareAdapter.connect()
                            ensureActive()
                            if (!_state.value.isWorkoutActive()) return@collect
                            if (hardwareAdapter.state.value is AdapterState.Active) {
                                logger.i(TAG, "Hardware reconnected on attempt $attempt")
                                mutex.withLock {
                                    if (_state.value.isWorkoutActive()) {
                                        // Re-derive the state from device type + current
                                        // WORKOUT_MODE so a treadmill that's still WARM_UP lands
                                        // back in AwaitingConsoleStart, not Running.
                                        val newType = hardwareAdapter.deviceInfo.value?.type
                                        val mode = hardwareAdapter.exerciseData.value?.workoutMode
                                            ?: WORKOUT_MODE_IDLE
                                        _state.value = if (newType == DeviceType.TREADMILL && mode != WORKOUT_MODE_RUNNING) {
                                            OrchestratorState.AwaitingConsoleStart(CONSOLE_START_MESSAGE)
                                        } else {
                                            OrchestratorState.Running(degraded = hardwareAdapter.degradedReason.value)
                                        }
                                    }
                                }
                                reconnected = true
                                break
                            }
                        }

                        if (!reconnected && isActive) {
                            mutex.withLock {
                                if (_state.value is OrchestratorState.Running) {
                                    stopInternal("Hardware reconnect failed after ${hardwareRetryPolicy.maxAttempts} attempts")
                                }
                            }
                        }
                    } finally {
                        isReconnecting = false
                    }
                }
            }
        }
    }

    /**
     * Final phase of bring-up: switch [_state] to [OrchestratorState.Running], start the
     * [rideRecorder], and kick the fan into AUTO if the user has configured that. Called from
     * [startInternal] on non-treadmill devices, and from the workout-mode monitor on treadmills
     * once the user has pressed the physical Start key (WORKOUT_MODE → RUNNING). Idempotent in
     * practice because [rideRecorder.start] is — and the workout-mode monitor only calls in from
     * [OrchestratorState.AwaitingConsoleStart], so a second entry won't be triggered by the
     * normal flow.
     */
    private suspend fun enterRunning(mergedData: StateFlow<ExerciseData?>) {
        _state.value = OrchestratorState.Running(degraded = hardwareAdapter.degradedReason.value)
        rideRecorder.start(mergedData.filterNotNull())

        if (userPreferences.fanMode.value == FanMode.AUTO) {
            hardwareAdapter.sendCommand(DeviceCommand.SetFanSpeed(4)) // AUTO
        }

        logger.i(TAG, "Orchestrator running")
    }

    /**
     * True while a workout is "in the air" from the user's perspective — armed, running, paused,
     * or holding a degraded broadcast. Used by the reconnect path to decide whether a transient
     * USB dropout warrants reconnect-with-backoff (vs an idle orchestrator that should stay idle).
     */
    private fun OrchestratorState.isWorkoutActive(): Boolean =
        this is OrchestratorState.Running ||
            this is OrchestratorState.Paused ||
            this is OrchestratorState.AwaitingConsoleStart

    private fun connectSensor() {
        val sensor = sensorAdapter ?: return
        scope.launch {
            try {
                // Sensor connection is non-fatal — workout continues without HR if it fails
                logger.i(TAG, "Sensor adapter available: ${sensor.id.displayName}")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                logger.w(TAG, "Sensor connection failed (non-fatal): ${e.message}")
            }
        }
    }

    private suspend fun fulfillPrerequisites(prerequisites: List<Prerequisite>, label: String): Boolean {
        val snapshot = systemMonitor.snapshot.value
        for (prereq in prerequisites) {
            if (prereq.isMet(snapshot)) {
                logger.d(TAG, "$label prerequisite already met: ${prereq.id}")
                continue
            }
            val fulfill = prereq.fulfill
            if (fulfill == null) {
                val msg = "Cannot fulfill $label prerequisite '${prereq.id}'"
                logger.e(TAG, msg)
                _state.value = OrchestratorState.Error(msg)
                return false
            }
            logger.i(TAG, "Fulfilling $label prerequisite: ${prereq.id}")
            val result = withTimeoutOrNull(prereq.fulfillTimeoutMs ?: PREREQUISITE_TIMEOUT_MS) {
                fulfill(systemController)
            }
            if (result == null) {
                val msg = "Timeout fulfilling $label prerequisite '${prereq.id}'"
                logger.e(TAG, msg)
                _state.value = OrchestratorState.Error(msg)
                return false
            }
            when (result) {
                is FulfillResult.Success ->
                    logger.i(TAG, "Fulfilled $label prerequisite: ${prereq.id}")
                is FulfillResult.Failed -> {
                    _state.value = OrchestratorState.Error(
                        "Failed to fulfill $label prerequisite '${prereq.id}': ${result.reason}",
                        result.cause,
                    )
                    return false
                }
            }
        }
        return true
    }

    suspend fun pause() {
        mutex.withLock {
            if (_state.value !is OrchestratorState.Running) {
                logger.d(TAG, "pause() ignored (not Running, current: ${_state.value})")
                return
            }
            hardwareAdapter.sendCommand(DeviceCommand.PauseWorkout)
            _state.value = OrchestratorState.Paused
            logger.i(TAG, "Orchestrator paused")
        }
    }

    suspend fun resume() {
        mutex.withLock {
            if (_state.value !is OrchestratorState.Paused) {
                logger.d(TAG, "resume() ignored (not Paused, current: ${_state.value})")
                return
            }
            hardwareAdapter.sendCommand(DeviceCommand.ResumeWorkout)
            _state.value = OrchestratorState.Running(degraded = hardwareAdapter.degradedReason.value)
            logger.i(TAG, "Orchestrator resumed")
        }
    }

    suspend fun stop(saveRide: Boolean = true) {
        mutex.withLock {
            val current = _state.value
            if (current is OrchestratorState.Idle || current is OrchestratorState.Stopping) {
                logger.d(TAG, "stop() ignored (current state: $current)")
                return
            }

            _state.value = OrchestratorState.Stopping
            preservedElapsedSeconds = 0L

            rideRecorder.stop(save = saveRide)

            commandPipelineJob?.cancel()
            commandPipelineJob = null
            broadcastManager.disconnectDataSource()

            hardwareMonitorJob?.cancel()
            hardwareMonitorJob = null
            workoutModeMonitorJob?.cancel()
            workoutModeMonitorJob = null
            isReconnecting = false

            // Turn off fan if it was managed by Hyperborea
            if (userPreferences.fanMode.value != FanMode.OFF) {
                try { hardwareAdapter.sendCommand(DeviceCommand.SetFanSpeed(0)) }
                catch (e: Exception) { logger.w(TAG, "Failed to turn off fan: ${e.message}") }
            }

            hardwareAdapter.disconnect()

            try { sensorAdapter?.disconnect() }
            catch (e: Exception) { logger.w(TAG, "Sensor disconnect failed: ${e.message}") }

            _state.value = OrchestratorState.Idle
            logger.i(TAG, "Orchestrator stopped")
        }
    }

    private suspend fun stopInternal(reason: String) = withContext(NonCancellable) {
        preservedElapsedSeconds = 0L
        rideRecorder.stop()

        commandPipelineJob?.cancel()
        commandPipelineJob = null
        broadcastManager.disconnectDataSource()

        hardwareMonitorJob?.cancel()
        hardwareMonitorJob = null
        workoutModeMonitorJob?.cancel()
        workoutModeMonitorJob = null
        isReconnecting = false

        try { sensorAdapter?.disconnect() }
        catch (e: Exception) { logger.w(TAG, "Sensor disconnect failed: ${e.message}") }

        _state.value = OrchestratorState.Error(reason)
        logger.i(TAG, "Orchestrator stopped: $reason")
    }

    private suspend fun refreshAndAwait() {
        val before = systemMonitor.snapshot.value.timestamp
        systemMonitor.refresh()
        val result = withTimeoutOrNull(REFRESH_TIMEOUT_MS) {
            systemMonitor.snapshot.first { it.timestamp != before }
        }
        if (result == null) {
            logger.w(TAG, "Timed out waiting for snapshot refresh, proceeding with stale snapshot")
        }
    }

    private companion object {
        const val TAG = "Orchestrator"
        const val PROBE_TIMEOUT_MS = 10_000L
        const val PREREQUISITE_TIMEOUT_MS = 10_000L
        const val REFRESH_TIMEOUT_MS = 5_000L
        const val WORKOUT_MODE_IDLE = 1
        const val WORKOUT_MODE_RUNNING = 2
        const val WORKOUT_MODE_DMK = 8

        const val CONSOLE_START_MESSAGE = "Press START on the console to begin"
    }
}
