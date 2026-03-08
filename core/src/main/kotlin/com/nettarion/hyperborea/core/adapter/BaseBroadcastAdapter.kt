package com.nettarion.hyperborea.core.adapter

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.model.ClientInfo
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.ExerciseData

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

abstract class BaseBroadcastAdapter(
    protected val logger: AppLogger,
    private val tag: String,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : BroadcastAdapter {

    private val _state = MutableStateFlow<AdapterState>(AdapterState.Inactive)
    override val state: StateFlow<AdapterState> = _state.asStateFlow()

    private val _connectedClients = MutableStateFlow<Set<ClientInfo>>(emptySet())
    override val connectedClients: StateFlow<Set<ClientInfo>> = _connectedClients.asStateFlow()

    private val _incomingCommands = MutableSharedFlow<DeviceCommand>(extraBufferCapacity = 16)
    override val incomingCommands: Flow<DeviceCommand> = _incomingCommands.asSharedFlow()

    private var dataCollectJob: Job? = null
    private var adapterScope: CoroutineScope? = null

    override suspend fun start(dataSource: Flow<ExerciseData>, deviceInfo: DeviceInfo) {
        if (_state.value is AdapterState.Active || _state.value is AdapterState.Activating) return
        _state.value = AdapterState.Activating

        try {
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            adapterScope = scope

            val broadcaster = onStart(scope, deviceInfo)

            dataCollectJob = scope.launch {
                try {
                    dataSource.collect { data -> broadcaster(data) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    logger.e(tag, "Data collection failed", e)
                    _state.value = AdapterState.Error("Data collection failed: ${e.message}", e)
                }
            }

            _state.value = AdapterState.Active
            logger.i(tag, "$tag started")
        } catch (e: Exception) {
            logger.e(tag, "Failed to start $tag", e)
            cleanup()
            _state.value = AdapterState.Error("Failed to start: ${e.message}", e)
        }
    }

    override suspend fun stop() {
        if (_state.value is AdapterState.Inactive) return
        cleanup()
        _state.value = AdapterState.Inactive
        logger.i(tag, "$tag stopped")
    }

    /**
     * Set up the broadcast infrastructure and return a function that will be called
     * for each [ExerciseData] emitted by the data source.
     *
     * Use [updateClients], [emitCommand], and [setError] to communicate state changes.
     */
    protected abstract suspend fun onStart(
        scope: CoroutineScope,
        deviceInfo: DeviceInfo,
    ): suspend (ExerciseData) -> Unit

    /**
     * Release broadcast-specific resources. Called during both normal stop and error cleanup.
     */
    protected abstract fun onStop()

    protected fun setError(message: String) {
        _state.value = AdapterState.Error(message)
    }

    protected fun updateClients(clients: Set<ClientInfo>) {
        _connectedClients.value = clients
    }

    protected fun emitCommand(command: DeviceCommand) {
        _incomingCommands.tryEmit(command)
    }

    private fun cleanup() {
        dataCollectJob?.cancel()
        dataCollectJob = null

        onStop()

        adapterScope?.cancel()
        adapterScope = null

        _connectedClients.value = emptySet()
    }
}
