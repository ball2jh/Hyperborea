package com.nettarion.hyperborea.platform.update

import com.nettarion.hyperborea.BuildConfig
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.orchestration.OrchestratorState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class UpdateManager @Inject constructor(
    private val httpClient: UpdateHttpClient,
    private val appInstaller: UpdateInstaller,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
    private val versionProvider: VersionProvider,
    @param:Named("updateDir") private val downloadDir: String,
    @param:Named("orchestratorState") private val orchestratorState: StateFlow<@JvmSuppressWildcards OrchestratorState>,
) {
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private var autoUpdateJob: Job? = null

    val appTrack: UpdateTrack = UpdateTrack(
        name = "app",
        downloadDir = downloadDir,
        downloadFilename = "hyperborea.apk",
        installer = appInstaller,
        httpClient = httpClient,
        logger = logger,
        scope = scope,
        urlResolver = { info -> resolveUrl(info) },
    )

    fun checkForUpdates() {
        if (_checking.value) {
            logger.w(TAG, "Already checking for updates, skipping")
            return
        }
        scope.launch { checkForUpdatesInternal() }
    }

    fun startAutoUpdate() {
        if (BuildConfig.SERVER_URL.isBlank()) {
            logger.i(TAG, "Self-update is not configured in this build — auto-update disabled")
            return
        }
        if (autoUpdateJob != null) {
            logger.d(TAG, "Auto-update already running, skipping")
            return
        }
        logger.i(TAG, "Starting auto-update (interval=${AUTO_UPDATE_INTERVAL_MS / 3600000}h)")
        autoUpdateJob = scope.launch {
            checkForUpdatesInternal()
            while (true) {
                delay(AUTO_UPDATE_INTERVAL_MS)
                runAutoUpdateCycle()
            }
        }
    }

    fun applyUpdate() {
        scope.launch { applyUpdateInternal() }
    }

    internal suspend fun applyUpdateInternal() {
        val current = appTrack.state.value
        if (current !is TrackState.Available) {
            logger.w(TAG, "applyUpdate called but track is ${current::class.simpleName}")
            return
        }
        logger.i(TAG, "Applying update: ${current.info.version}")

        appTrack.download()
        val afterDownload = appTrack.state.first { it !is TrackState.Downloading }
        if (afterDownload !is TrackState.ReadyToInstall) {
            logger.e(TAG, "Download did not complete: ${afterDownload::class.simpleName}")
            return
        }

        appTrack.install()
        val afterInstall = appTrack.state.first { it !is TrackState.Installing }
        if (afterInstall !is TrackState.Installed) {
            logger.e(TAG, "Install did not complete: ${afterInstall::class.simpleName}")
            return
        }

        awaitIdleAndFinalize()
    }

    internal suspend fun checkForUpdatesInternal() {
        _checking.value = true
        try {
            val manifestUrl = "${BuildConfig.SERVER_URL}/api/device/manifest"
            logger.i(TAG, "Checking for updates at $manifestUrl")
            val json = httpClient.fetchManifest(manifestUrl, emptyMap())
            val manifest = UpdateManifest.parse(json)

            val app = manifest.app
            if (app == null) {
                logger.i(TAG, "No release available")
            } else {
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
                    logger.i(TAG, "App is up to date (code $currentVersionCode)")
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

    private suspend fun runAutoUpdateCycle() {
        val trackState = appTrack.state.value
        if (trackState !is TrackState.Idle && trackState !is TrackState.Error) {
            logger.d(TAG, "Auto-update: skipping, track is ${trackState::class.simpleName}")
            return
        }
        if (trackState is TrackState.Error) {
            appTrack.dismiss()
        }
        try {
            logger.d(TAG, "Auto-update cycle starting")
            checkForUpdatesInternal()
            if (appTrack.state.value is TrackState.Available) {
                applyUpdateInternal()
            }
            logger.d(TAG, "Auto-update cycle complete")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Auto-update cycle failed", e)
        }
    }

    private suspend fun awaitIdleAndFinalize() {
        val currentState = orchestratorState.value
        if (currentState is OrchestratorState.Running || currentState is OrchestratorState.Paused) {
            logger.i(TAG, "Orchestrator is active, waiting for idle before restart")
            val idled = withTimeoutOrNull(IDLE_WAIT_TIMEOUT_MS) {
                orchestratorState.first { it is OrchestratorState.Idle || it is OrchestratorState.Error }
            }
            if (idled == null) {
                logger.w(TAG, "Timed out waiting for orchestrator idle, restarting anyway")
            }
        }
        appTrack.finalizeInstall()
    }

    private suspend fun resolveUrl(info: UpdateInfo): String {
        if (BuildConfig.SERVER_URL.isBlank()) return info.url
        val manifestUrl = "${BuildConfig.SERVER_URL}/api/device/manifest"
        val json = httpClient.fetchManifest(manifestUrl, emptyMap())
        val manifest = UpdateManifest.parse(json)
        val freshUrl = manifest.app?.url
        if (freshUrl == null) {
            logger.w(TAG, "URL resolver: no app entry in manifest, using cached URL")
            return info.url
        }
        return freshUrl
    }

    companion object {
        private const val TAG = "Update"
        internal const val AUTO_UPDATE_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 hours
        private const val IDLE_WAIT_TIMEOUT_MS = 4 * 60 * 60 * 1000L // 4 hours
    }
}
