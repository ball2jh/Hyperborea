package com.nettarion.hyperborea.platform.license

import com.nettarion.hyperborea.BuildConfig
import com.nettarion.hyperborea.core.AppLogger
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HttpUrlConnectionLicenseClient @Inject constructor(
    private val logger: AppLogger,
) : LicenseHttpClient {

    override fun fetchStatus(authToken: String): String? {
        val url = URL("${BuildConfig.SERVER_URL}/api/device/status")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $authToken")

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                logger.w(TAG, "Status check returned HTTP ${connection.responseCode}")
                return null
            }

            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    override fun requestPairing(deviceUuid: String): String? {
        val url = URL("${BuildConfig.SERVER_URL}/api/device/pair")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val json = JSONObject(mapOf("deviceUuid" to deviceUuid)).toString()
            connection.outputStream.bufferedWriter().use { it.write(json) }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                logger.w(TAG, "Pair request returned HTTP ${connection.responseCode}")
                return null
            }

            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    override fun pollPairingStatus(pairingToken: String): String? {
        val url = URL("${BuildConfig.SERVER_URL}/api/device/pair/status?token=$pairingToken")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                logger.w(TAG, "Pair status returned HTTP ${connection.responseCode}")
                return null
            }

            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    override fun unlink(authToken: String): Boolean {
        val url = URL("${BuildConfig.SERVER_URL}/api/device/unlink")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $authToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.close()

            val code = connection.responseCode
            if (code !in 200..299) {
                logger.w(TAG, "Unlink returned HTTP $code")
            }
            return code in 200..299
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "Hyperborea.License"
        private const val TIMEOUT_MS = 15_000
    }
}
