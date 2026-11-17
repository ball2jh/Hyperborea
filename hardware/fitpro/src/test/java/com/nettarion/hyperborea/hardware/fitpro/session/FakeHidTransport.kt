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

    var closeCount = 0
        private set

    override suspend fun close() {
        closeCount++
        _isOpen = false
        incomingChannel.close()
    }

    /** The next this-many [write] calls throw, like a USB OUT transfer the controller refused. */
    var failNextWrites = 0

    override suspend fun write(data: ByteArray) {
        if (failNextWrites > 0) {
            failNextWrites--
            throw java.io.IOException("USB write failed")
        }
        writtenPackets.add(data.copyOf())
    }

    override suspend fun readPacket(): ByteArray? = incomingChannel.receiveCatching().getOrNull()

    override suspend fun clearBuffer() { /* no-op in tests */ }

    override fun incoming(): Flow<ByteArray> = incomingChannel.receiveAsFlow()

    suspend fun emitIncoming(data: ByteArray) {
        incomingChannel.send(data)
    }

    /** Mimics a USB detach: the transport reports closed without close() having been called. */
    fun simulateDetach() {
        _isOpen = false
    }

    fun closeIncoming() {
        incomingChannel.close()
    }
}
