package com.nettarion.hyperborea.core.orchestration

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.RetryPolicy
import com.nettarion.hyperborea.core.adapter.AdapterState
import com.nettarion.hyperborea.core.adapter.HardwareAdapter
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.profile.RideRecorder
import com.nettarion.hyperborea.core.system.SystemController
import com.nettarion.hyperborea.core.system.SystemMonitor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
    private val logger: AppLogger,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<OrchestratorState>(OrchestratorState.Idle)
    val state: StateFlow<OrchestratorState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var hardwareMonitorJob: Job? = null
    private var commandPipelineJob: Job? = null

    private val hardwareRetryPolicy = RetryPolicy(maxAttempts = 5, initialDelayMs = 2000, maxDelayMs = 15000)
    @Volatile
    private var isReconnecting = false
    private var preservedElapsedSeconds: Long = 0L

    suspend fun start() {
        mutex.withLock {
            val current = _state.value
            if (current is OrchestratorState.Running || current is OrchestratorState.Preparing || current is OrchestratorState.Paused) return

            try {
                startInternal()
            } catch (e: Exception) {
                logger.e(TAG, "Unexpected error during start", e)
                _state.value = OrchestratorState.Error("Unexpected: ${e.message}", e)
            }
        }
    }

    private suspend fun startInternal() {
        // Ecosystem prerequisites
        _state.value = OrchestratorState.Preparing("Fulfilling ecosystem prerequisites")
        if (!fulfillPrerequisites(ecosystemManager.prerequisites, "ecosystem")) return

        refreshAndAwait()

        // Hardware prerequisites
        _state.value = OrchestratorState.Preparing("Fulfilling hardware prerequisites")
        if (!fulfillPrerequisites(hardwareAdapter.prerequisites, "hardware")) return

        refreshAndAwait()

        // Check hardware can operate
        val snapshot = systemMonitor.snapshot.value
        logger.d(TAG, "Snapshot: USB=${snapshot.status.isUsbHostAvailable}, " +
            "BLE=${snapshot.status.isBluetoothLeAdvertisingSupported}, " +
            "WiFi=${snapshot.status.isWifiEnabled}")
        if (!hardwareAdapter.canOperate(snapshot)) {
            val msg = "Hardware cannot operate with current system state"
            logger.e(TAG, msg)
            _state.value = OrchestratorState.Error(msg)
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

        // Wire data source to broadcasts
        val deviceInfo = hardwareAdapter.deviceInfo.value
            ?: throw IllegalStateException("Hardware connected but deviceInfo is null")
        broadcastManager.connectDataSource(hardwareAdapter.exerciseData)
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

        _state.value = OrchestratorState.Running()
        rideRecorder.start(hardwareAdapter.exerciseData.filterNotNull())
        logger.i(TAG, "Orchestrator running")

        // Hardware disconnect monitor — reconnect with backoff
        hardwareMonitorJob = scope.launch {
            hardwareAdapter.state.collect { hwState ->
                if ((hwState is AdapterState.Error || hwState is AdapterState.Inactive)
                    && (_state.value is OrchestratorState.Running || _state.value is OrchestratorState.Paused) && !isReconnecting) {
                    isReconnecting = true
                    try {
                        val reason = if (hwState is AdapterState.Error) hwState.message else "Hardware disconnected"
                        logger.w(TAG, "$reason -- attempting reconnect")
                        ensureActive()
                        if (_state.value !is OrchestratorState.Running && _state.value !is OrchestratorState.Paused) return@collect
                        mutex.withLock {
                            if (_state.value is OrchestratorState.Running || _state.value is OrchestratorState.Paused) {
                                _state.value = OrchestratorState.Running(degraded = reason)
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
                            if (_state.value !is OrchestratorState.Running && _state.value !is OrchestratorState.Paused) return@collect
                            if (hardwareAdapter.state.value is AdapterState.Active) {
                                logger.i(TAG, "Hardware reconnected on attempt $attempt")
                                mutex.withLock {
                                    if (_state.value is OrchestratorState.Running || _state.value is OrchestratorState.Paused) {
                                        _state.value = OrchestratorState.Running()
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
            val result = withTimeoutOrNull(PREREQUISITE_TIMEOUT_MS) {
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
            if (_state.value !is OrchestratorState.Running) return
            hardwareAdapter.sendCommand(DeviceCommand.PauseWorkout)
            _state.value = OrchestratorState.Paused
            logger.i(TAG, "Orchestrator paused")
        }
    }

    suspend fun resume() {
        mutex.withLock {
            if (_state.value !is OrchestratorState.Paused) return
            hardwareAdapter.sendCommand(DeviceCommand.ResumeWorkout)
            _state.value = OrchestratorState.Running()
            logger.i(TAG, "Orchestrator resumed")
        }
    }

    suspend fun stop(saveRide: Boolean = true) {
        mutex.withLock {
            val current = _state.value
            if (current is OrchestratorState.Idle || current is OrchestratorState.Stopping) return

            _state.value = OrchestratorState.Stopping
            preservedElapsedSeconds = 0L

            rideRecorder.stop(save = saveRide)

            commandPipelineJob?.cancel()
            commandPipelineJob = null
            broadcastManager.disconnectDataSource()

            hardwareMonitorJob?.cancel()
            hardwareMonitorJob = null
            isReconnecting = false

            hardwareAdapter.disconnect()

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
        isReconnecting = false

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
        const val PREREQUISITE_TIMEOUT_MS = 10_000L
        const val REFRESH_TIMEOUT_MS = 5_000L
    }
}
