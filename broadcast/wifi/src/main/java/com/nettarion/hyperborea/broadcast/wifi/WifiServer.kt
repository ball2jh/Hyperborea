package com.nettarion.hyperborea.broadcast.wifi

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WifiServer(
    private val logger: AppLogger,
    private val scope: CoroutineScope,
    private val deviceType: DeviceType,
    private val serviceDef: WifiServiceDefinition,
    private val onClientChange: (Set<ClientInfo>) -> Unit,
    private val onCommand: (DeviceCommand) -> Unit,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val clients = ConcurrentHashMap<String, WifiClientHandler>()

    suspend fun start(port: Int = PORT) {
        if (serverSocket != null) return

        val socket = bindWithRetry(port)
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

                        val handler = WifiClientHandler(
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

    /**
     * Binds the listen socket, retrying briefly on "address already in use". A rapid stop→start —
     * e.g. a device-info change restarting broadcasts, or the orchestrator re-probing — can outrun
     * the OS releasing the previous listener's port even though we set `SO_REUSEADDR`, so a couple
     * of short retries avoid a spurious failure (and the 2s broadcast-level retry that followed it).
     */
    private suspend fun bindWithRetry(port: Int): ServerSocket {
        var lastError: java.net.BindException? = null
        repeat(BIND_ATTEMPTS) { attempt ->
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(port))
                }
            } catch (e: java.net.BindException) {
                lastError = e
                logger.w(TAG, "Port $port busy (attempt ${attempt + 1}/$BIND_ATTEMPTS), retrying in ${BIND_RETRY_DELAY_MS}ms")
                delay(BIND_RETRY_DELAY_MS)
            }
        }
        throw lastError ?: java.net.BindException("Failed to bind port $port")
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
            ClientInfo(id = id, protocol = "WiFi", connectedAt = System.currentTimeMillis())
        }.toSet()
        onClientChange(infos)
    }

    companion object {
        const val PORT = 36866
        private const val TAG = "WifiServer"
        // Short bind-retry budget to ride out the brief window where a just-closed listener hasn't
        // released the port yet (≈500ms total); the broadcast-level retry remains the backstop.
        private const val BIND_ATTEMPTS = 5
        private const val BIND_RETRY_DELAY_MS = 100L
    }
}
