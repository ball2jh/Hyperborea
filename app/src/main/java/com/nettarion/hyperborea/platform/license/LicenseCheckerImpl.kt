package com.nettarion.hyperborea.platform.license

import android.content.SharedPreferences
import com.nettarion.hyperborea.BuildConfig
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.Ed25519Verifier
import com.nettarion.hyperborea.core.LicenseChecker
import com.nettarion.hyperborea.core.LicenseState
import com.nettarion.hyperborea.core.PairingSession
import com.nettarion.hyperborea.core.PairingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LicenseCheckerImpl @Inject constructor(
    private val prefs: SharedPreferences,
    private val logger: AppLogger,
    private val httpClient: LicenseHttpClient,
) : LicenseChecker {

    private val _state = MutableStateFlow<LicenseState>(LicenseState.Checking)
    override val state: StateFlow<LicenseState> = _state.asStateFlow()

    override suspend fun check(silent: Boolean) {
        logger.d(TAG, "check() starting (silent=$silent)")
        if (!silent) _state.value = LicenseState.Checking
        val authToken = prefs.getString(KEY_AUTH_TOKEN, null)
        if (authToken == null) {
            logger.d(TAG, "No auth token, setting Unlicensed")
            _state.value = LicenseState.Unlicensed
            return
        }

        try {
            logger.d(TAG, "Fetching status from server")
            val responseBody = withContext(Dispatchers.IO) {
                httpClient.fetchStatus(authToken)
            }

            if (responseBody == null) {
                logger.w(TAG, "Server returned null, falling back to cache")
                useCachedState()
                return
            }

            logger.d(TAG, "Got response: ${responseBody.take(200)}")
            val response = JSONObject(responseBody)
            val payload = response.getString("payload")
            val signature = response.getString("signature")

            // Verify Ed25519 signature
            val publicKey = Ed25519Verifier.hexToBytes(BuildConfig.LICENSE_PUBLIC_KEY)
            val sigBytes = Ed25519Verifier.hexToBytes(signature)
            val valid = Ed25519Verifier.verify(payload.toByteArray(), sigBytes, publicKey)

            if (!valid) {
                logger.w(TAG, "Signature verification failed")
                _state.value = LicenseState.Unlicensed
                return
            }

            val payloadJson = JSONObject(payload)
            val active = payloadJson.getBoolean("active")
            val expiresAt = payloadJson.getString("expiresAt")
            val expiresAtMillis = parseIso8601(expiresAt)

            logger.d(TAG, "active=$active expiresAtMillis=$expiresAtMillis now=${System.currentTimeMillis()}")

            if (active && expiresAtMillis > System.currentTimeMillis()) {
                prefs.edit().putLong(KEY_CACHED_EXPIRES_AT, expiresAtMillis).apply()
                _state.value = LicenseState.Licensed(expiresAtMillis)
                logger.i(TAG, "Licensed until $expiresAtMillis")
            } else {
                prefs.edit().remove(KEY_CACHED_EXPIRES_AT).apply()
                _state.value = LicenseState.Unlicensed
                logger.w(TAG, "Not active or expired")
            }
        } catch (e: Exception) {
            logger.e(TAG, "License check failed", e)
            useCachedState()
        }
    }

    override suspend fun requestPairing(): PairingSession {
        return try {
            val responseBody = withContext(Dispatchers.IO) {
                httpClient.requestPairing(getDeviceUuid())
            }
            if (responseBody != null) {
                val json = JSONObject(responseBody)
                val pairingToken = json.getString("pairingToken")
                val pairingCode = json.getString("pairingCode")
                val expiresAt = json.getLong("expiresAt")
                _state.value = LicenseState.Pairing(pairingToken, pairingCode, expiresAt)
                PairingSession.Created(pairingToken, pairingCode, expiresAt)
            } else {
                PairingSession.Error("Failed to create pairing request")
            }
        } catch (e: Exception) {
            logger.e(TAG, "Pairing request failed", e)
            PairingSession.Error(e.message ?: "Unknown error")
        }
    }

    override suspend fun pollPairing(pairingToken: String): PairingStatus {
        return try {
            val responseBody = withContext(Dispatchers.IO) {
                httpClient.pollPairingStatus(pairingToken)
            }
            if (responseBody == null) {
                return PairingStatus.Error("Network error")
            }
            val json = JSONObject(responseBody)
            val status = json.getString("status")
            when (status) {
                "pending" -> PairingStatus.Pending
                "linked" -> {
                    val authToken = json.getString("authToken")
                    prefs.edit().putString(KEY_AUTH_TOKEN, authToken).apply()
                    check()
                    PairingStatus.Linked(authToken)
                }
                "expired" -> {
                    _state.value = LicenseState.Unlicensed
                    PairingStatus.Expired
                }
                else -> PairingStatus.Error("Unknown status: $status")
            }
        } catch (e: Exception) {
            logger.e(TAG, "Pairing poll failed", e)
            PairingStatus.Error(e.message ?: "Unknown error")
        }
    }

    override suspend fun unlink() {
        val authToken = prefs.getString(KEY_AUTH_TOKEN, null)
        // Clear local credentials first so the device is unlicensed even if the server call fails
        prefs.edit()
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_CACHED_EXPIRES_AT)
            .apply()
        _state.value = LicenseState.Unlicensed
        logger.i(TAG, "Device unlinked locally")

        if (authToken != null) {
            try {
                withContext(Dispatchers.IO) {
                    httpClient.unlink(authToken)
                }
            } catch (e: Exception) {
                logger.w(TAG, "Server unlink failed (device already unlinked locally): ${e.message}")
            }
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
    }
}
