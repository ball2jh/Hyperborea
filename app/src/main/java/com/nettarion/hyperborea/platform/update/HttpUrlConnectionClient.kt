package com.nettarion.hyperborea.platform.update

import com.nettarion.hyperborea.platform.net.HttpHelper
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HttpUrlConnectionClient @Inject constructor() : UpdateHttpClient {

    override fun fetchManifest(url: String, headers: Map<String, String>): String {
        val connection = openConnection(url, headers)
        try {
            return HttpHelper.readResponse(connection)
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
        val connection = HttpHelper.openConnection(
            url = url,
            method = "GET",
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            headers = headers,
        )
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
