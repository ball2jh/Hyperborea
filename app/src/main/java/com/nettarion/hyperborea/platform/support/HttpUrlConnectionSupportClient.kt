package com.nettarion.hyperborea.platform.support

import com.nettarion.hyperborea.BuildConfig
import com.nettarion.hyperborea.core.AppLogger
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HttpUrlConnectionSupportClient @Inject constructor(
    private val logger: AppLogger,
) : SupportHttpClient {

    override fun upload(authToken: String, jsonBody: String): String? {
        val url = URL("${BuildConfig.SERVER_URL}/api/support/upload")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $authToken")
            connection.doOutput = true

            connection.outputStream.bufferedWriter().use { it.write(jsonBody) }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                logger.w(TAG, "Support upload returned HTTP ${connection.responseCode}")
                return null
            }

            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "Hyperborea.Support"
        private const val TIMEOUT_MS = 30_000
    }
}
