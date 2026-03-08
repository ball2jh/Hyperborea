package com.nettarion.hyperborea.broadcast.ftms

import android.content.Context
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.BaseBroadcastAdapter
import com.nettarion.hyperborea.core.BroadcastId
import com.nettarion.hyperborea.core.DeviceInfo
import com.nettarion.hyperborea.core.ExerciseData
import com.nettarion.hyperborea.core.Prerequisite
import com.nettarion.hyperborea.core.SystemSnapshot
import kotlinx.coroutines.CoroutineScope

class FtmsAdapter(
    private val context: Context,
    logger: AppLogger,
    private val deviceName: () -> String?,
) : BaseBroadcastAdapter(logger, TAG) {

    override val id: BroadcastId = BroadcastId.FTMS
    override val prerequisites: List<Prerequisite> = emptyList()

    override fun canOperate(snapshot: SystemSnapshot): Boolean =
        snapshot.status.isBluetoothLeAdvertisingSupported

    private var server: FtmsBleServer? = null

    override suspend fun onStart(
        scope: CoroutineScope,
        deviceInfo: DeviceInfo,
    ): (ExerciseData) -> Unit {
        val bleServer = FtmsBleServer(
            context = context,
            deviceInfo = deviceInfo,
            logger = logger,
            onClientChange = { clients -> updateClients(clients) },
            onCommand = { command -> emitCommand(command) },
            onError = { msg -> setError(msg) },
        )
        server = bleServer
        bleServer.start(deviceName() ?: DEFAULT_DEVICE_NAME)
        return { data -> bleServer.broadcastData(data) }
    }

    override fun onStop() {
        server?.stop()
        server = null
    }

    private companion object {
        const val TAG = "FtmsAdapter"
        const val DEFAULT_DEVICE_NAME = "Hyperborea"
    }
}
