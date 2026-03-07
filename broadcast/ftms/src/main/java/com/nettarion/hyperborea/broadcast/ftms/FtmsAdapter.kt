package com.nettarion.hyperborea.broadcast.ftms

import android.content.Context
import com.nettarion.hyperborea.core.AdapterState
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.BroadcastAdapter
import com.nettarion.hyperborea.core.BroadcastId
import com.nettarion.hyperborea.core.ClientInfo
import com.nettarion.hyperborea.core.DeviceCommand
import com.nettarion.hyperborea.core.ExerciseData
import com.nettarion.hyperborea.core.Prerequisite
import com.nettarion.hyperborea.core.SystemSnapshot
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

class FtmsAdapter(
    private val context: Context,
    private val logger: AppLogger,
    private val deviceName: () -> String?,
) : BroadcastAdapter {

    override val id: BroadcastId = BroadcastId.FTMS

    override val prerequisites: List<Prerequisite> = emptyList()

    override fun canOperate(snapshot: SystemSnapshot): Boolean =
        snapshot.status.isBluetoothLeAdvertisingSupported

    private val _state = MutableStateFlow<AdapterState>(AdapterState.Inactive)
    override val state: StateFlow<AdapterState> = _state.asStateFlow()

    private val _connectedClients = MutableStateFlow<Set<ClientInfo>>(emptySet())
    override val connectedClients: StateFlow<Set<ClientInfo>> = _connectedClients.asStateFlow()

    private val _incomingCommands = MutableSharedFlow<DeviceCommand>(extraBufferCapacity = 16)
    override val incomingCommands: Flow<DeviceCommand> = _incomingCommands.asSharedFlow()

    private var server: FtmsBleServer? = null
    private var dataCollectJob: Job? = null
    private var adapterScope: CoroutineScope? = null

    override suspend fun start(dataSource: Flow<ExerciseData>) {
        if (_state.value is AdapterState.Active || _state.value is AdapterState.Activating) return
        _state.value = AdapterState.Activating

        try {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            adapterScope = scope

            val bleServer = FtmsBleServer(
                context = context,
                logger = logger,
                onClientChange = { clients -> _connectedClients.value = clients },
                onCommand = { command -> _incomingCommands.tryEmit(command) },
            )
            server = bleServer
            bleServer.start(deviceName() ?: DEFAULT_DEVICE_NAME)

            dataCollectJob = scope.launch {
                dataSource.collect { data ->
                    bleServer.broadcastData(data)
                }
            }

            _state.value = AdapterState.Active
            logger.i(TAG, "FTMS adapter started")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to start FTMS adapter", e)
            stopInternal()
            _state.value = AdapterState.Error("Failed to start: ${e.message}", e)
        }
    }

    override suspend fun stop() {
        if (_state.value is AdapterState.Inactive) return
        stopInternal()
        _state.value = AdapterState.Inactive
        logger.i(TAG, "FTMS adapter stopped")
    }

    private fun stopInternal() {
        dataCollectJob?.cancel()
        dataCollectJob = null

        server?.stop()
        server = null

        adapterScope?.cancel()
        adapterScope = null

        _connectedClients.value = emptySet()
    }

    private companion object {
        const val TAG = "FtmsAdapter"
        const val DEFAULT_DEVICE_NAME = "Hyperborea"
    }
}
