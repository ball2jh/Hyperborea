package com.nettarion.hyperborea.platform.update

import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HttpUrlConnectionClient @Inject constructor() : UpdateHttpClient {

    override fun fetchManifest(url: String): String {
        val connection = openConnection(url)
        try {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    override fun openDownload(url: String): DownloadStream {
        val connection = openConnection(url)
        val contentLength = connection.contentLength.toLong()
        return DownloadStream(
            inputStream = connection.inputStream,
            contentLength = contentLength,
        )
    }

    private fun openConnection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "GET"
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
