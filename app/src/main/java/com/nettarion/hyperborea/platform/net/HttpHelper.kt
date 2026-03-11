package com.nettarion.hyperborea.platform.net

import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

object HttpHelper {

    fun openConnection(
        url: String,
        method: String = "GET",
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 15_000,
        headers: Map<String, String> = emptyMap(),
    ): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        if (connection is HttpsURLConnection) {
            connection.sslSocketFactory = Tls12SocketFactory()
        }
        connection.requestMethod = method
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        return connection
    }

    fun readResponse(connection: HttpURLConnection): String {
        return connection.inputStream.bufferedReader().use { it.readText() }
    }
}
