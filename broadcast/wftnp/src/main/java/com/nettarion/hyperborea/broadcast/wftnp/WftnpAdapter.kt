package com.nettarion.hyperborea.broadcast.wftnp

import com.nettarion.hyperborea.core.AdapterState
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.BroadcastAdapter
import com.nettarion.hyperborea.core.BroadcastId
import com.nettarion.hyperborea.core.ClientInfo
import com.nettarion.hyperborea.core.DeviceCommand
import com.nettarion.hyperborea.core.DeviceInfo
import com.nettarion.hyperborea.core.ExerciseData
import com.nettarion.hyperborea.core.Prerequisite
import com.nettarion.hyperborea.core.SystemSnapshot
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Singleton
class WftnpAdapter @Inject constructor(
    private val nsdRegistrar: NsdRegistrar,
    private val logger: AppLogger,
    @Named("deviceName") private val deviceName: () -> String?,
) : BroadcastAdapter {

    override val id: BroadcastId = BroadcastId.WFTNP

    override val prerequisites: List<Prerequisite> = emptyList()

    override fun canOperate(snapshot: SystemSnapshot): Boolean =
        snapshot.status.isWifiEnabled

    private val _state = MutableStateFlow<AdapterState>(AdapterState.Inactive)
    override val state: StateFlow<AdapterState> = _state.asStateFlow()

    private val _connectedClients = MutableStateFlow<Set<ClientInfo>>(emptySet())
    override val connectedClients: StateFlow<Set<ClientInfo>> = _connectedClients.asStateFlow()

    private val _incomingCommands = MutableSharedFlow<DeviceCommand>(extraBufferCapacity = 16)
    override val incomingCommands: Flow<DeviceCommand> = _incomingCommands.asSharedFlow()

    private var server: WftnpServer? = null
    private var dataCollectJob: Job? = null
    private var adapterScope: CoroutineScope? = null

    override suspend fun start(dataSource: Flow<ExerciseData>, deviceInfo: DeviceInfo) {
        if (_state.value is AdapterState.Active || _state.value is AdapterState.Activating) return
        _state.value = AdapterState.Activating

        try {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            adapterScope = scope

            val serviceDef = WftnpServiceDefinition(deviceInfo)
            val wftnpServer = WftnpServer(
                logger = logger,
                scope = scope,
                deviceType = deviceInfo.type,
                serviceDef = serviceDef,
                onClientChange = { clients -> _connectedClients.value = clients },
                onCommand = { command -> _incomingCommands.tryEmit(command) },
            )
            server = wftnpServer
            wftnpServer.start()

            nsdRegistrar.register(WftnpServer.PORT, deviceName() ?: DEFAULT_DEVICE_NAME)

            dataCollectJob = scope.launch {
                try {
                    dataSource.collect { data ->
                        wftnpServer.broadcastData(data)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    logger.e(TAG, "Data collection failed", e)
                    _state.value = AdapterState.Error("Data collection failed: ${e.message}", e)
                }
            }

            _state.value = AdapterState.Active
            logger.i(TAG, "WFTNP adapter started on port ${WftnpServer.PORT}")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to start WFTNP adapter", e)
            stopInternal()
            _state.value = AdapterState.Error("Failed to start: ${e.message}", e)
        }
    }

    override suspend fun stop() {
        if (_state.value is AdapterState.Inactive) return
        stopInternal()
        _state.value = AdapterState.Inactive
        logger.i(TAG, "WFTNP adapter stopped")
    }

    private fun stopInternal() {
        dataCollectJob?.cancel()
        dataCollectJob = null

        nsdRegistrar.unregister()

        server?.stop()
        server = null

        adapterScope?.cancel()
        adapterScope = null

        _connectedClients.value = emptySet()
    }

    private companion object {
        const val TAG = "WftnpAdapter"
        const val DEFAULT_DEVICE_NAME = "Hyperborea"
    }
}
