package com.nettarion.hyperborea.platform.support

import com.nettarion.hyperborea.BuildConfig
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.platform.net.HttpHelper
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HttpUrlConnectionSupportClient @Inject constructor(
    private val logger: AppLogger,
) : SupportHttpClient {

    override fun upload(authToken: String, jsonBody: String): String? {
        val connection = HttpHelper.openConnection(
            url = "${BuildConfig.SERVER_URL}/api/support/upload",
            method = "POST",
            headers = mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer $authToken",
            ),
            connectTimeoutMs = TIMEOUT_MS,
            readTimeoutMs = TIMEOUT_MS,
        )
        try {
            connection.doOutput = true
            connection.outputStream.bufferedWriter().use { it.write(jsonBody) }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                logger.w(TAG, "Support upload returned HTTP ${connection.responseCode}")
                return null
            }
            return HttpHelper.readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "Support"
        private const val TIMEOUT_MS = 30_000
    }
}
