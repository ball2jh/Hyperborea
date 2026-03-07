package com.nettarion.hyperborea.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Orchestrator(
    private val systemMonitor: SystemMonitor,
    private val systemController: SystemController,
    private val ecosystemManager: EcosystemManager,
    private val hardwareAdapter: HardwareAdapter,
    private val broadcastAdapters: Set<BroadcastAdapter>,
    private val userPreferences: UserPreferences,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<OrchestratorState>(OrchestratorState.Idle)
    val state: StateFlow<OrchestratorState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var commandPipelineJob: Job? = null
    private var hardwareMonitorJob: Job? = null
    private var activeBroadcasts: List<BroadcastAdapter> = emptyList()

    suspend fun start() {
        mutex.withLock {
            val current = _state.value
            if (current is OrchestratorState.Running || current is OrchestratorState.Preparing) return

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

        for (adapter in broadcastAdapters) {
            if (adapter.id !in enabledIds) {
                logger.i(TAG, "Skipped broadcast (disabled by user): ${adapter.id.displayName}")
                continue
            }
            if (adapter.canOperate(snapshot)) {
                adapter.start(dataSource)
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
                commandFlows.merge().collect { command ->
                    logger.d(TAG, "Forwarding command: $command")
                    hardwareAdapter.sendCommand(command)
                }
            }
        }

        _state.value = OrchestratorState.Running
        logger.i(TAG, "Orchestrator running (${started.size}/${broadcastAdapters.size} broadcasts)")

        // Hardware disconnect monitor — launched after state is set to Running
        hardwareMonitorJob = scope.launch {
            hardwareAdapter.state.collect { hwState ->
                if (hwState is AdapterState.Error || hwState is AdapterState.Inactive) {
                    mutex.withLock {
                        if (_state.value is OrchestratorState.Running) {
                            val reason = when (hwState) {
                                is AdapterState.Error -> "Hardware error: ${hwState.message}"
                                else -> "Hardware disconnected"
                            }
                            logger.w(TAG, "$reason — stopping broadcasts")
                            stopInternal(reason)
                        }
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
            when (val result = fulfill(systemController)) {
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

    suspend fun stop() {
        mutex.withLock {
            val current = _state.value
            if (current is OrchestratorState.Idle || current is OrchestratorState.Stopping) return

            _state.value = OrchestratorState.Stopping

            hardwareMonitorJob?.cancel()
            hardwareMonitorJob = null

            commandPipelineJob?.cancel()
            commandPipelineJob = null

            for (adapter in activeBroadcasts) {
                adapter.stop()
            }
            activeBroadcasts = emptyList()

            hardwareAdapter.disconnect()

            _state.value = OrchestratorState.Idle
            logger.i(TAG, "Orchestrator stopped")
        }
    }

    private suspend fun stopInternal(reason: String) {
        hardwareMonitorJob?.cancel()
        hardwareMonitorJob = null

        commandPipelineJob?.cancel()
        commandPipelineJob = null

        for (adapter in activeBroadcasts) {
            adapter.stop()
        }
        activeBroadcasts = emptyList()

        _state.value = OrchestratorState.Error(reason)
        logger.i(TAG, "Orchestrator stopped: $reason")
    }

    private suspend fun refreshAndAwait() {
        val before = systemMonitor.snapshot.value.timestamp
        systemMonitor.refresh()
        systemMonitor.snapshot.first { it.timestamp != before }
    }

    private companion object {
        const val TAG = "Orchestrator"
    }
}
