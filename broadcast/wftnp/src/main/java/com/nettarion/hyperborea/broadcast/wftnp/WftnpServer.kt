package com.nettarion.hyperborea.broadcast.wftnp

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.model.ClientInfo
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.model.ExerciseData
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WftnpServer(
    private val logger: AppLogger,
    private val scope: CoroutineScope,
    private val deviceType: DeviceType,
    private val serviceDef: WftnpServiceDefinition,
    private val onClientChange: (Set<ClientInfo>) -> Unit,
    private val onCommand: (DeviceCommand) -> Unit,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val clients = ConcurrentHashMap<String, WftnpClientHandler>()

    fun start(port: Int = PORT) {
        if (serverSocket != null) return

        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(java.net.InetSocketAddress(port))
        serverSocket = socket
        logger.i(TAG, "TCP server started on port $port")

        acceptJob = scope.launch {
            withContext(Dispatchers.IO) {
                while (isActive && !socket.isClosed) {
                    try {
                        val clientSocket = socket.accept()
                        clientSocket.tcpNoDelay = true
                        clientSocket.keepAlive = true
                        val clientId = clientSocket.remoteSocketAddress.toString()
                        logger.i(TAG, "Client connected: $clientId")

                        val handler = WftnpClientHandler(
                            clientId = clientId,
                            input = clientSocket.getInputStream(),
                            output = clientSocket.getOutputStream(),
                            deviceType = deviceType,
                            serviceDef = serviceDef,
                            scope = scope,
                            onCommand = onCommand,
                            logger = logger,
                        )
                        handler.startSendLoop()
                        clients[clientId] = handler
                        notifyClientChange()

                        scope.launch {
                            try {
                                handler.runReadLoop()
                            } finally {
                                clients.remove(clientId)
                                handler.close()
                                runCatching { clientSocket.close() }
                                logger.i(TAG, "Client disconnected: $clientId")
                                notifyClientChange()
                            }
                        }
                    } catch (e: Exception) {
                        if (isActive && serverSocket != null) {
                            logger.e(TAG, "Accept error", e)
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null

        for ((_, handler) in clients) {
            handler.close()
        }
        clients.clear()

        runCatching { serverSocket?.close() }
        serverSocket = null

        logger.i(TAG, "TCP server stopped")
        notifyClientChange()
    }

    suspend fun broadcastData(data: ExerciseData) {
        val deadClients = mutableListOf<String>()
        for ((id, handler) in clients) {
            if (handler.isClosed) {
                deadClients.add(id)
                continue
            }
            handler.updateData(data)
        }
        if (deadClients.isNotEmpty()) {
            deadClients.forEach { clients.remove(it) }
            notifyClientChange()
        }
    }

    private fun notifyClientChange() {
        val infos = clients.entries.map { (id, _) ->
            ClientInfo(id = id, protocol = "WFTNP", connectedAt = System.currentTimeMillis())
        }.toSet()
        onClientChange(infos)
    }

    companion object {
        const val PORT = 36866
        private const val TAG = "WftnpServer"
    }
}
