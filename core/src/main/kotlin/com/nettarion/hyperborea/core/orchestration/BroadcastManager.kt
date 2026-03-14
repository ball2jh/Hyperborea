package com.nettarion.hyperborea.core.orchestration

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.RetryPolicy
import com.nettarion.hyperborea.core.adapter.AdapterState
import com.nettarion.hyperborea.core.adapter.BroadcastAdapter
import com.nettarion.hyperborea.core.adapter.BroadcastId
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.model.FanMode
import com.nettarion.hyperborea.core.profile.UserPreferences
import com.nettarion.hyperborea.core.system.SystemMonitor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalCoroutinesApi::class)
class BroadcastManager(
    private val broadcastAdapters: Set<BroadcastAdapter>,
    private val systemMonitor: SystemMonitor,
    private val userPreferences: UserPreferences,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
) {

    private val mutex = Mutex()
    private val activeBroadcasts = mutableMapOf<BroadcastId, BroadcastAdapter>()
    private val broadcastJobs = mutableMapOf<BroadcastId, BroadcastJobPair>()
    private val broadcastRetryPolicy = RetryPolicy(maxAttempts = 3, initialDelayMs = 2000)
    private val broadcastRetryAttempts = mutableMapOf<BroadcastId, Int>()

    private var preferencesMonitorJob: Job? = null
    private var dataForwardingJob: Job? = null
    private var isStarted = false
    private var currentDeviceInfo: DeviceInfo = DeviceInfo.DEFAULT_INDOOR_BIKE

    private val _dataSource = MutableSharedFlow<ExerciseData>(replay = 1)
    private val _incomingCommands = MutableSharedFlow<DeviceCommand>(extraBufferCapacity = 16)

    val incomingCommands: Flow<DeviceCommand> = _incomingCommands.asSharedFlow()

    private class BroadcastJobPair(val monitorJob: Job, val commandJob: Job)

    suspend fun start() {
        mutex.withLock {
            if (isStarted) return

            val enabledIds = userPreferences.enabledBroadcasts.value
            broadcastRetryAttempts.clear()

            for (adapter in broadcastAdapters) {
                if (adapter.id !in enabledIds) {
                    logger.i(TAG, "Skipped broadcast (disabled by user): ${adapter.id.displayName}")
                    continue
                }
                startBroadcast(adapter)
            }

            isStarted = true
            logger.i(TAG, "BroadcastManager started (${activeBroadcasts.size}/${broadcastAdapters.size} broadcasts)")

            // Preferences observer for runtime broadcast toggling.
            // drop(1) skips the current value (already handled above) and avoids
            // a mutex deadlock since start() holds the mutex during this block.
            preferencesMonitorJob = scope.launch {
                userPreferences.enabledBroadcasts.drop(1).collect { currentEnabledIds ->
                    mutex.withLock {
                        if (!isStarted) return@collect

                        val toStop = activeBroadcasts.keys - currentEnabledIds
                        for (id in toStop) {
                            logger.i(TAG, "Broadcast toggled off: ${id.displayName}")
                            stopBroadcast(id)
                        }

                        val toStart = currentEnabledIds - activeBroadcasts.keys
                        for (id in toStart) {
                            val adapter = broadcastAdapters.find { it.id == id } ?: continue
                            logger.i(TAG, "Broadcast toggled on: ${id.displayName}")
                            startBroadcast(adapter)
                        }
                    }
                }
            }
        }
    }

    suspend fun stop() {
        mutex.withLock {
            if (!isStarted) return

            preferencesMonitorJob?.cancel()
            preferencesMonitorJob = null

            dataForwardingJob?.cancel()
            dataForwardingJob = null
            _dataSource.resetReplayCache()

            broadcastJobs.values.forEach { it.monitorJob.cancel(); it.commandJob.cancel() }
            broadcastJobs.clear()
            broadcastRetryAttempts.clear()

            for (adapter in activeBroadcasts.values) {
                try {
                    adapter.stop()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    logger.e(TAG, "Failed to stop ${adapter.id.displayName}", e)
                }
            }
            activeBroadcasts.clear()

            isStarted = false
            logger.i(TAG, "BroadcastManager stopped")
        }
    }

    fun connectDataSource(source: StateFlow<ExerciseData?>) {
        dataForwardingJob?.cancel()
        dataForwardingJob = scope.launch {
            source.filterNotNull().collect { _dataSource.emit(it) }
        }
        logger.i(TAG, "Data source connected to ${activeBroadcasts.size} broadcast(s)")
    }

    fun disconnectDataSource() {
        dataForwardingJob?.cancel()
        dataForwardingJob = null
        _dataSource.resetReplayCache()
        logger.i(TAG, "Data source disconnected (broadcasts remain active)")
    }

    suspend fun updateDeviceInfo(info: DeviceInfo) {
        mutex.withLock {
            if (info == currentDeviceInfo) return
            currentDeviceInfo = info
            logger.i(TAG, "Device info changed, restarting active broadcasts")

            for ((id, adapter) in activeBroadcasts.toMap()) {
                stopBroadcast(id)
                startBroadcast(adapter)
            }
        }
    }

    /** Start a single broadcast adapter with its command and monitor jobs. Must be called with mutex held. */
    private suspend fun startBroadcast(adapter: BroadcastAdapter) {
        val snapshot = systemMonitor.snapshot.value
        if (!adapter.canOperate(snapshot)) {
            logger.i(TAG, "Skipped broadcast (cannot operate): ${adapter.id.displayName}")
            return
        }

        adapter.start(_dataSource, currentDeviceInfo)
        logger.i(TAG, "Started broadcast: ${adapter.id.displayName}")

        val commandJob = scope.launch {
            adapter.incomingCommands
                .catch { e -> logger.e(TAG, "Command pipeline error for ${adapter.id.displayName}", e) }
                .collect { command ->
                    if (command is DeviceCommand.SetFanSpeed &&
                        userPreferences.fanMode.value != FanMode.WIND_SIMULATION) {
                        logger.d(TAG, "Filtered SetFanSpeed command (fan mode: ${userPreferences.fanMode.value})")
                        return@collect
                    }
                    _incomingCommands.emit(command)
                }
        }

        val monitorJob = scope.launch {
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
                            adapter.start(_dataSource, currentDeviceInfo)
                        }
                    } else {
                        logger.e(TAG, "${adapter.id.displayName} exhausted retries")
                    }
                } else if (adapterState is AdapterState.Active) {
                    broadcastRetryAttempts.remove(adapter.id)
                }
            }
        }

        activeBroadcasts[adapter.id] = adapter
        broadcastJobs[adapter.id] = BroadcastJobPair(monitorJob, commandJob)
    }

    /** Stop a single broadcast adapter and cancel its jobs. Must be called with mutex held. */
    private suspend fun stopBroadcast(id: BroadcastId) {
        broadcastJobs.remove(id)?.let { jobs ->
            jobs.commandJob.cancel()
            jobs.monitorJob.cancel()
        }
        activeBroadcasts.remove(id)?.let { adapter ->
            try {
                adapter.stop()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                logger.e(TAG, "Failed to stop ${adapter.id.displayName}", e)
            }
        }
        broadcastRetryAttempts.remove(id)
    }

    private companion object {
        const val TAG = "BroadcastManager"
    }
}
