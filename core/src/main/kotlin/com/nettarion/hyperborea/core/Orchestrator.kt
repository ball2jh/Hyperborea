package com.nettarion.hyperborea.core

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
import kotlinx.coroutines.flow.merge
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
    private val broadcastAdapters: Set<BroadcastAdapter>,
    private val userPreferences: UserPreferences,
    private val rideRecorder: RideRecorder,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<OrchestratorState>(OrchestratorState.Idle)
    val state: StateFlow<OrchestratorState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var commandPipelineJob: Job? = null
    private var hardwareMonitorJob: Job? = null
    private var broadcastMonitorJobs: List<Job> = emptyList()
    private var activeBroadcasts: List<BroadcastAdapter> = emptyList()

    private val broadcastRetryPolicy = RetryPolicy(maxAttempts = 3, initialDelayMs = 2000)
    private val broadcastRetryAttempts = mutableMapOf<BroadcastId, Int>()
    private val hardwareRetryPolicy = RetryPolicy(maxAttempts = 5, initialDelayMs = 2000, maxDelayMs = 15000)
    @Volatile
    private var isReconnecting = false
    @Volatile
    private var activeDeviceInfo: DeviceInfo? = null

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

        // Start broadcasts
        _state.value = OrchestratorState.Preparing("Starting broadcasts")
        val dataSource = hardwareAdapter.exerciseData.filterNotNull()
        val started = mutableListOf<BroadcastAdapter>()
        val enabledIds = userPreferences.enabledBroadcasts.value
        val deviceInfo = hardwareAdapter.deviceInfo.value
            ?: throw IllegalStateException("Hardware connected but deviceInfo is null")
        activeDeviceInfo = deviceInfo

        for (adapter in broadcastAdapters) {
            if (adapter.id !in enabledIds) {
                logger.i(TAG, "Skipped broadcast (disabled by user): ${adapter.id.displayName}")
                continue
            }
            if (adapter.canOperate(snapshot)) {
                adapter.start(dataSource, deviceInfo)
                started.add(adapter)
                logger.i(TAG, "Started broadcast: ${adapter.id.displayName}")
            } else {
                logger.i(TAG, "Skipped broadcast (cannot operate): ${adapter.id.displayName}")
            }
        }
        activeBroadcasts = started

        // Command pipeline
        if (started.isNotEmpty()) {
            val commandFlows = started.map { it.incomingCommands }
            commandPipelineJob = scope.launch {
                commandFlows.merge()
                    .catch { e -> logger.e(TAG, "Command pipeline error", e) }
                    .collect { command ->
                        try {
                            logger.d(TAG, "Forwarding command: $command")
                            hardwareAdapter.sendCommand(command)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { logger.e(TAG, "Failed to forward command", e) }
                    }
            }
        }

        _state.value = OrchestratorState.Running()
        rideRecorder.start(hardwareAdapter.exerciseData.filterNotNull())
        logger.i(TAG, "Orchestrator running (${started.size}/${broadcastAdapters.size} broadcasts)")

        // Broadcast adapter monitors — retry on error
        broadcastRetryAttempts.clear()
        broadcastMonitorJobs = started.map { adapter ->
            scope.launch {
                adapter.state.collect { adapterState ->
                    if (adapterState is AdapterState.Error) {
                        val attempts = broadcastRetryAttempts.getOrDefault(adapter.id, 0)
                        if (attempts < broadcastRetryPolicy.maxAttempts) {
                            val delayMs = broadcastRetryPolicy.delayForAttempt(attempts + 1)
                            logger.i(TAG, "Retrying ${adapter.id.displayName} in ${delayMs}ms (attempt ${attempts + 1})")
                            delay(delayMs)
                            broadcastRetryAttempts[adapter.id] = attempts + 1
                            adapter.stop()
                            val currentSnapshot = systemMonitor.snapshot.value
                            if (adapter.canOperate(currentSnapshot)) {
                                adapter.start(hardwareAdapter.exerciseData.filterNotNull(), activeDeviceInfo!!)
                            }
                        } else {
                            logger.e(TAG, "${adapter.id.displayName} exhausted retries")
                            updateDegradedState()
                        }
                    } else if (adapterState is AdapterState.Active) {
                        broadcastRetryAttempts.remove(adapter.id)
                    }
                }
            }
        }

        // Hardware disconnect monitor — reconnect with backoff
        hardwareMonitorJob = scope.launch {
            hardwareAdapter.state.collect { hwState ->
                if ((hwState is AdapterState.Error || hwState is AdapterState.Inactive)
                    && (_state.value is OrchestratorState.Running || _state.value is OrchestratorState.Paused) && !isReconnecting) {
                    isReconnecting = true
                    try {
                        val reason = if (hwState is AdapterState.Error) hwState.message else "Hardware disconnected"
                        logger.w(TAG, "$reason -- attempting reconnect")
                        _state.value = OrchestratorState.Running(degraded = reason)

                        var reconnected = false
                        for (attempt in 1..hardwareRetryPolicy.maxAttempts) {
                            delay(hardwareRetryPolicy.delayForAttempt(attempt))
                            hardwareAdapter.disconnect()
                            hardwareAdapter.connect()
                            if (hardwareAdapter.state.value is AdapterState.Active) {
                                logger.i(TAG, "Hardware reconnected on attempt $attempt")
                                _state.value = OrchestratorState.Running()
                                reconnected = true
                                break
                            }
                        }

                        if (!reconnected) {
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

            rideRecorder.stop(save = saveRide)

            broadcastMonitorJobs.forEach { it.cancel() }
            broadcastMonitorJobs = emptyList()
            broadcastRetryAttempts.clear()

            hardwareMonitorJob?.cancel()
            hardwareMonitorJob = null
            isReconnecting = false

            commandPipelineJob?.cancel()
            commandPipelineJob = null

            for (adapter in activeBroadcasts) {
                try {
                    adapter.stop()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    logger.e(TAG, "Failed to stop ${adapter.id.displayName}", e)
                }
            }
            activeBroadcasts = emptyList()

            hardwareAdapter.disconnect()

            _state.value = OrchestratorState.Idle
            logger.i(TAG, "Orchestrator stopped")
        }
    }

    private suspend fun stopInternal(reason: String) = withContext(NonCancellable) {
        rideRecorder.stop()

        broadcastMonitorJobs.forEach { it.cancel() }
        broadcastMonitorJobs = emptyList()
        broadcastRetryAttempts.clear()

        hardwareMonitorJob?.cancel()
        hardwareMonitorJob = null
        isReconnecting = false

        commandPipelineJob?.cancel()
        commandPipelineJob = null

        for (adapter in activeBroadcasts) {
            try {
                adapter.stop()
            } catch (e: Exception) {
                logger.e(TAG, "Failed to stop ${adapter.id.displayName}", e)
            }
        }
        activeBroadcasts = emptyList()

        _state.value = OrchestratorState.Error(reason)
        logger.i(TAG, "Orchestrator stopped: $reason")
    }

    private fun updateDegradedState() {
        if (_state.value !is OrchestratorState.Running && _state.value !is OrchestratorState.Paused) return
        val errors = activeBroadcasts.filter { it.state.value is AdapterState.Error }
        val msg = if (errors.isNotEmpty()) "${errors.size} broadcast(s) in error" else null
        _state.value = OrchestratorState.Running(degraded = msg)
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
