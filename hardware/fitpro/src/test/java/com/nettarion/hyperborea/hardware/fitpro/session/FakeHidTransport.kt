package com.nettarion.hyperborea.hardware.fitpro.session

import com.nettarion.hyperborea.hardware.fitpro.transport.HidTransport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class FakeHidTransport : HidTransport {

    private var _isOpen = false
    override val isOpen: Boolean get() = _isOpen

    val writtenPackets = mutableListOf<ByteArray>()
    private val incomingChannel = Channel<ByteArray>(Channel.UNLIMITED)

    override suspend fun open() {
        _isOpen = true
    }

    override suspend fun close() {
        _isOpen = false
        incomingChannel.close()
    }

    override suspend fun write(data: ByteArray) {
        writtenPackets.add(data.copyOf())
    }

    override suspend fun readPacket(): ByteArray? = incomingChannel.receiveCatching().getOrNull()

    override suspend fun clearBuffer() { /* no-op in tests */ }

    override fun incoming(): Flow<ByteArray> = incomingChannel.receiveAsFlow()

    suspend fun emitIncoming(data: ByteArray) {
        incomingChannel.send(data)
    }

    fun closeIncoming() {
        incomingChannel.close()
    }
}
