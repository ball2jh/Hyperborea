package com.nettarion.hyperborea.platform.license

import android.content.SharedPreferences
import com.nettarion.hyperborea.BuildConfig
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.Ed25519Verifier
import com.nettarion.hyperborea.core.LicenseChecker
import com.nettarion.hyperborea.core.LicenseState
import com.nettarion.hyperborea.core.LinkResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LicenseCheckerImpl @Inject constructor(
    private val prefs: SharedPreferences,
    private val logger: AppLogger,
) : LicenseChecker {

    private val _state = MutableStateFlow<LicenseState>(LicenseState.Checking)
    override val state: StateFlow<LicenseState> = _state.asStateFlow()

    override suspend fun check() {
        _state.value = LicenseState.Checking
        val authToken = prefs.getString(KEY_AUTH_TOKEN, null)
        if (authToken == null) {
            _state.value = LicenseState.Unlicensed
            return
        }

        try {
            val response = withContext(Dispatchers.IO) {
                fetchStatus(authToken)
            }

            if (response == null) {
                // Network error — check cache
                useCachedState()
                return
            }

            val payload = response.getString("payload")
            val signature = response.getString("signature")

            // Verify Ed25519 signature
            val publicKey = Ed25519Verifier.hexToBytes(BuildConfig.LICENSE_PUBLIC_KEY)
            val sigBytes = Ed25519Verifier.hexToBytes(signature)
            val valid = Ed25519Verifier.verify(payload.toByteArray(), sigBytes, publicKey)

            if (!valid) {
                logger.w(TAG, "License response signature verification failed")
                _state.value = LicenseState.Unlicensed
                return
            }

            val payloadJson = JSONObject(payload)
            val active = payloadJson.getBoolean("active")
            val expiresAt = payloadJson.getString("expiresAt")
            val expiresAtMillis = parseIso8601(expiresAt)

            if (active && expiresAtMillis > System.currentTimeMillis()) {
                prefs.edit().putLong(KEY_CACHED_EXPIRES_AT, expiresAtMillis).apply()
                _state.value = LicenseState.Licensed(expiresAtMillis)
            } else {
                prefs.edit().remove(KEY_CACHED_EXPIRES_AT).apply()
                _state.value = LicenseState.Unlicensed
            }
        } catch (e: Exception) {
            logger.e(TAG, "License check failed", e)
            useCachedState()
        }
    }

    override suspend fun linkWithCode(code: String): LinkResult {
        return link(mapOf("deviceUuid" to getDeviceUuid(), "code" to code))
    }

    override suspend fun linkWithQrToken(qrToken: String): LinkResult {
        return link(mapOf("deviceUuid" to getDeviceUuid(), "qrToken" to qrToken))
    }

    private suspend fun link(body: Map<String, String>): LinkResult {
        return try {
            val result = withContext(Dispatchers.IO) {
                postLink(body)
            }
            if (result != null) {
                prefs.edit().putString(KEY_AUTH_TOKEN, result).apply()
                check()
                LinkResult.Success(result)
            } else {
                LinkResult.Error("Invalid or expired code")
            }
        } catch (e: Exception) {
            logger.e(TAG, "Device linking failed", e)
            LinkResult.Error(e.message ?: "Unknown error")
        }
    }

    private fun fetchStatus(authToken: String): JSONObject? {
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

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun postLink(body: Map<String, String>): String? {
        val url = URL("${BuildConfig.SERVER_URL}/api/device/link")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val json = JSONObject(body).toString()
            connection.outputStream.bufferedWriter().use { it.write(json) }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                logger.w(TAG, "Link returned HTTP ${connection.responseCode}")
                return null
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(responseBody).getString("authToken")
        } finally {
            connection.disconnect()
        }
    }

    private fun useCachedState() {
        val cachedExpiresAt = prefs.getLong(KEY_CACHED_EXPIRES_AT, 0)
        if (cachedExpiresAt > System.currentTimeMillis()) {
            logger.i(TAG, "Using cached license (expires at $cachedExpiresAt)")
            _state.value = LicenseState.Licensed(cachedExpiresAt)
        } else {
            _state.value = LicenseState.Unlicensed
        }
    }

    private fun getDeviceUuid(): String {
        var uuid = prefs.getString(KEY_DEVICE_UUID, null)
        if (uuid == null) {
            uuid = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_UUID, uuid).apply()
        }
        return uuid
    }

    private fun parseIso8601(iso: String): Long {
        // SimpleDateFormat for API 25 compatibility (no java.time)
        // Try with fractional seconds first, then without
        val formats = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
        )
        for (pattern in formats) {
            try {
                val format = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                format.timeZone = java.util.TimeZone.getTimeZone("UTC")
                return format.parse(iso)?.time ?: continue
            } catch (_: java.text.ParseException) {
                continue
            }
        }
        logger.w(TAG, "Failed to parse ISO 8601 date: $iso")
        return 0
    }

    companion object {
        private const val TAG = "Hyperborea.License"
        private const val KEY_AUTH_TOKEN = "license_auth_token"
        private const val KEY_DEVICE_UUID = "license_device_uuid"
        private const val KEY_CACHED_EXPIRES_AT = "license_cached_expires_at"
        private const val TIMEOUT_MS = 15_000
    }
}
