package com.nettarion.hyperborea.platform.update

import android.content.SharedPreferences
import com.nettarion.hyperborea.BuildConfig
import com.nettarion.hyperborea.core.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class UpdateManager @Inject constructor(
    private val httpClient: UpdateHttpClient,
    private val appInstaller: UpdateInstaller,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
    private val versionProvider: VersionProvider,
    @param:Named("updateDir") private val downloadDir: String,
) {
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    val appTrack: UpdateTrack = UpdateTrack(
        name = "app",
        downloadDir = downloadDir,
        downloadFilename = "hyperborea.apk",
        installer = appInstaller,
        httpClient = httpClient,
        logger = logger,
        scope = scope,
    )

    fun checkForUpdates() {
        if (_checking.value) {
            logger.w(TAG, "Already checking for updates, skipping")
            return
        }
        scope.launch {
            _checking.value = true
            try {
                val manifestUrl = "${BuildConfig.SERVER_URL}/api/device/manifest"
                val authToken = prefs.getString("license_auth_token", null)
                val headers = if (authToken != null) {
                    mapOf("Authorization" to "Bearer $authToken")
                } else {
                    emptyMap()
                }
                logger.i(TAG, "Checking for updates at $manifestUrl")
                val json = httpClient.fetchManifest(manifestUrl, headers)
                val manifest = UpdateManifest.parse(json)

                manifest.app?.let { app ->
                    val currentVersionCode = versionProvider.getVersionCode()
                    if (app.versionCode > currentVersionCode) {
                        logger.i(TAG, "App update available: ${app.versionName} (code ${app.versionCode})")
                        appTrack.setAvailable(
                            UpdateInfo(
                                version = app.versionName,
                                url = app.url,
                                sha256 = app.sha256,
                                releaseNotes = app.releaseNotes,
                            ),
                        )
                    } else {
                        logger.d(TAG, "App is up to date (code $currentVersionCode)")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "Update check failed", e)
            } finally {
                _checking.value = false
            }
        }
    }

    companion object {
        private const val TAG = "Update"
    }
}
