package com.nettarion.hyperborea.broadcast.wftnp

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.BaseBroadcastAdapter
import com.nettarion.hyperborea.core.BroadcastId
import com.nettarion.hyperborea.core.DeviceInfo
import com.nettarion.hyperborea.core.ExerciseData
import com.nettarion.hyperborea.core.Prerequisite
import com.nettarion.hyperborea.core.SystemSnapshot
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

@Singleton
class WftnpAdapter @Inject constructor(
    private val nsdRegistrar: NsdRegistrar,
    logger: AppLogger,
    @Named("deviceName") private val deviceName: () -> String?,
) : BaseBroadcastAdapter(logger, TAG) {

    override val id: BroadcastId = BroadcastId.WFTNP
    override val prerequisites: List<Prerequisite> = emptyList()

    override fun canOperate(snapshot: SystemSnapshot): Boolean =
        snapshot.status.isWifiEnabled

    private var server: WftnpServer? = null

    override suspend fun onStart(
        scope: CoroutineScope,
        deviceInfo: DeviceInfo,
    ): (ExerciseData) -> Unit {
        val serviceDef = WftnpServiceDefinition(deviceInfo)
        val wftnpServer = WftnpServer(
            logger = logger,
            scope = scope,
            deviceType = deviceInfo.type,
            serviceDef = serviceDef,
            onClientChange = { clients -> updateClients(clients) },
            onCommand = { command -> emitCommand(command) },
        )
        server = wftnpServer
        wftnpServer.start()

        nsdRegistrar.register(WftnpServer.PORT, deviceName() ?: DEFAULT_DEVICE_NAME)

        return { data -> wftnpServer.broadcastData(data) }
    }

    override fun onStop() {
        nsdRegistrar.unregister()
        server?.stop()
        server = null
    }

    private companion object {
        const val TAG = "WftnpAdapter"
        const val DEFAULT_DEVICE_NAME = "Hyperborea"
    }
}
