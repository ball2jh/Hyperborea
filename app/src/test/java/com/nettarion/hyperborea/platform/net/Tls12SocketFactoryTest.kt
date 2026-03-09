package com.nettarion.hyperborea.platform.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLSocket

class Tls12SocketFactoryTest {

    private val factory = Tls12SocketFactory()

    @Test
    fun `wrapping a plain socket enforces TLS 1_2 only`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port = server.localPort
        val plainSocket = Socket(InetAddress.getLoopbackAddress(), port)
        try {
            val sslSocket = factory.createSocket(plainSocket, "localhost", port, true)
            assertTrue(sslSocket is SSLSocket)
            assertArrayEquals(arrayOf("TLSv1.2"), (sslSocket as SSLSocket).enabledProtocols)
            sslSocket.close()
        } finally {
            plainSocket.close()
            server.close()
        }
    }

    @Test
    fun `default cipher suites are not empty`() {
        assertTrue(factory.defaultCipherSuites.isNotEmpty())
    }

    @Test
    fun `supported cipher suites are not empty`() {
        assertTrue(factory.supportedCipherSuites.isNotEmpty())
    }
}
