package com.nettarion.hyperborea.platform.license

import com.nettarion.hyperborea.BuildConfig
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.platform.net.HttpHelper
import org.json.JSONObject
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HttpUrlConnectionLicenseClient @Inject constructor(
    private val logger: AppLogger,
) : LicenseHttpClient {

    override fun fetchStatus(authToken: String, nonce: String): String? {
        val connection = HttpHelper.openConnection(
            url = "${BuildConfig.SERVER_URL}/api/device/status",
            headers = mapOf(
                "Authorization" to "Bearer $authToken",
                "X-Nonce" to nonce,
            ),
            connectTimeoutMs = TIMEOUT_MS,
            readTimeoutMs = TIMEOUT_MS,
        )
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                logger.w(TAG, "Status check returned HTTP ${connection.responseCode}")
                return null
            }
            return HttpHelper.readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    override fun requestPairing(deviceUuid: String): String? {
        val connection = HttpHelper.openConnection(
            url = "${BuildConfig.SERVER_URL}/api/device/pair",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            connectTimeoutMs = TIMEOUT_MS,
            readTimeoutMs = TIMEOUT_MS,
        )
        try {
            connection.doOutput = true
            val json = JSONObject(mapOf("deviceUuid" to deviceUuid)).toString()
            connection.outputStream.bufferedWriter().use { it.write(json) }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                logger.w(TAG, "Pair request returned HTTP ${connection.responseCode}")
                return null
            }
            return HttpHelper.readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    override fun pollPairingStatus(pairingToken: String): String? {
        val connection = HttpHelper.openConnection(
            url = "${BuildConfig.SERVER_URL}/api/device/pair/status",
            headers = mapOf("X-Pairing-Token" to pairingToken),
            connectTimeoutMs = TIMEOUT_MS,
            readTimeoutMs = TIMEOUT_MS,
        )
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                logger.w(TAG, "Pair status returned HTTP ${connection.responseCode}")
                return null
            }
            return HttpHelper.readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    override fun unlink(authToken: String): Boolean {
        val connection = HttpHelper.openConnection(
            url = "${BuildConfig.SERVER_URL}/api/device/unlink",
            method = "POST",
            headers = mapOf(
                "Authorization" to "Bearer $authToken",
                "Content-Type" to "application/json",
            ),
            connectTimeoutMs = TIMEOUT_MS,
            readTimeoutMs = TIMEOUT_MS,
        )
        try {
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
        private const val TAG = "License"
        private const val TIMEOUT_MS = 15_000
    }
}
