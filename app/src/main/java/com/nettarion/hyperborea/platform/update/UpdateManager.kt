package com.nettarion.hyperborea.platform.update

import android.content.Context
import android.os.Build
import com.nettarion.hyperborea.BuildConfig
import com.nettarion.hyperborea.core.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val httpClient: UpdateHttpClient,
    private val appInstaller: AppInstaller,
    private val firmwareInstaller: FirmwareInstaller,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
) {
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    val appTrack: UpdateTrack = UpdateTrack(
        name = "app",
        downloadDir = context.filesDir.resolve("update").absolutePath,
        downloadFilename = "hyperborea.apk",
        installer = appInstaller,
        httpClient = httpClient,
        logger = logger,
        scope = scope,
    )

    val firmwareTrack: UpdateTrack = UpdateTrack(
        name = "firmware",
        downloadDir = FIRMWARE_DOWNLOAD_DIR,
        downloadFilename = FIRMWARE_FILENAME,
        installer = firmwareInstaller,
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
                logger.i(TAG, "Checking for updates at ${BuildConfig.UPDATE_MANIFEST_URL}")
                val json = httpClient.fetchManifest(BuildConfig.UPDATE_MANIFEST_URL)
                val manifest = UpdateManifest.parse(json)

                manifest.app?.let { app ->
                    @Suppress("DEPRECATION")
                    val currentVersionCode = context.packageManager
                        .getPackageInfo(context.packageName, 0).versionCode
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

                manifest.firmware?.let { firmware ->
                    val currentFirmware = Build.DISPLAY
                    if (firmware.version != currentFirmware) {
                        logger.i(TAG, "Firmware update available: ${firmware.version} (current: $currentFirmware)")
                        firmwareTrack.setAvailable(
                            UpdateInfo(
                                version = firmware.version,
                                url = firmware.url,
                                sha256 = firmware.sha256,
                                releaseNotes = firmware.releaseNotes,
                            ),
                        )
                    } else {
                        logger.d(TAG, "Firmware is up to date ($currentFirmware)")
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
        private const val FIRMWARE_DOWNLOAD_DIR = "/cache"
        private const val FIRMWARE_FILENAME = "hyperborea_ota.zip"
    }
}
