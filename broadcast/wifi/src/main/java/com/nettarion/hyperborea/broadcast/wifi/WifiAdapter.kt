package com.nettarion.hyperborea.broadcast.wifi

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.adapter.BaseBroadcastAdapter
import com.nettarion.hyperborea.core.adapter.BroadcastId
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.orchestration.Prerequisite
import com.nettarion.hyperborea.core.system.SystemSnapshot
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

@Singleton
class WifiAdapter internal constructor(
    private val nsdRegistrar: NsdRegistrar,
    logger: AppLogger,
    /** Listen port. Production always uses [WifiServer.PORT]; tests pass 0 for an OS-assigned one. */
    private val port: Int,
) : BaseBroadcastAdapter(logger, TAG) {

    @Inject constructor(nsdRegistrar: NsdRegistrar, logger: AppLogger) : this(nsdRegistrar, logger, WifiServer.PORT)

    override val id: BroadcastId = BroadcastId.WIFI
    override val prerequisites: List<Prerequisite> = emptyList()

    override fun canOperate(snapshot: SystemSnapshot): Boolean =
        snapshot.status.isWifiEnabled

    private var server: WifiServer? = null

    override suspend fun onStart(
        scope: CoroutineScope,
        deviceInfo: DeviceInfo,
    ): suspend (ExerciseData) -> Unit {
        val serviceDef = WifiServiceDefinition(deviceInfo)
        val wifiServer = WifiServer(
            logger = logger,
            scope = scope,
            deviceType = deviceInfo.type,
            serviceDef = serviceDef,
            onClientChange = { clients -> updateClients(clients) },
            onCommand = { command -> emitCommand(command) },
        )
        server = wifiServer
        wifiServer.start(port)

        nsdRegistrar.register(port, deviceInfo.name, deviceInfo.type)

        return { data -> wifiServer.broadcastData(data) }
    }

    override fun onStop() {
        nsdRegistrar.unregister()
        server?.stop()
        server = null
    }

    private companion object {
        const val TAG = "WifiAdapter"
    }
}
