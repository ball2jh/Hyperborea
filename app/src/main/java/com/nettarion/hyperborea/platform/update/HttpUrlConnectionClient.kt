package com.nettarion.hyperborea.platform.update

import com.nettarion.hyperborea.platform.net.Tls12SocketFactory
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection

@Singleton
class HttpUrlConnectionClient @Inject constructor() : UpdateHttpClient {

    override fun fetchManifest(url: String, headers: Map<String, String>): String {
        val connection = openConnection(url, headers)
        try {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    override fun openDownload(url: String, headers: Map<String, String>): DownloadStream {
        val connection = openConnection(url, headers)
        val contentLength = connection.contentLength.toLong()
        return DownloadStream(
            inputStream = connection.inputStream,
            contentLength = contentLength,
        )
    }

    private fun openConnection(url: String, headers: Map<String, String> = emptyMap()): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        if (connection is HttpsURLConnection) {
            connection.sslSocketFactory = Tls12SocketFactory()
        }
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "GET"
        headers.forEach { (key, value) ->
            connection.setRequestProperty(key, value)
        }
        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            throw UpdateHttpException("HTTP $responseCode from $url")
        }
        return connection
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
    }
}

class UpdateHttpException(message: String) : RuntimeException(message)
