package com.nettarion.hyperborea.platform.net

import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class Tls12SocketFactory : SSLSocketFactory() {
    private val delegate: SSLSocketFactory = SSLContext.getInstance("TLSv1.2")
        .apply { init(null, null, null) }
        .socketFactory

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        delegate.createSocket(s, host, port, autoClose).enforce()

    override fun createSocket(host: String, port: Int): Socket =
        delegate.createSocket(host, port).enforce()

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        delegate.createSocket(host, port, localHost, localPort).enforce()

    override fun createSocket(host: InetAddress, port: Int): Socket =
        delegate.createSocket(host, port).enforce()

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        delegate.createSocket(address, port, localAddress, localPort).enforce()

    private fun Socket.enforce(): Socket = apply {
        if (this is SSLSocket) enabledProtocols = arrayOf("TLSv1.2")
    }
}
