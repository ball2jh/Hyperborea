package com.nettarion.hyperborea.platform.update

import com.nettarion.hyperborea.core.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class UpdateTrack internal constructor(
    private val name: String,
    private val downloadDir: String,
    private val downloadFilename: String,
    private val installer: UpdateInstaller,
    private val httpClient: UpdateHttpClient,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<TrackState>(TrackState.Idle)
    val state: StateFlow<TrackState> = _state.asStateFlow()

    private var activeJob: Job? = null

    fun setAvailable(info: UpdateInfo) {
        cancelActiveJob()
        _state.value = TrackState.Available(info)
    }

    fun download() {
        val current = _state.value
        if (current !is TrackState.Available) {
            logger.w(TAG, "$name: download() called in invalid state: ${current::class.simpleName}")
            return
        }
        val info = current.info
        activeJob = scope.launch {
            try {
                _state.value = TrackState.Downloading(info, DownloadProgress(0, 0))
                logger.i(TAG, "$name: Starting download from ${info.url}")

                val dir = File(downloadDir)
                dir.mkdirs()
                val file = File(dir, downloadFilename)

                val downloadStream = httpClient.openDownload(info.url)
                val totalBytes = downloadStream.contentLength
                val digest = MessageDigest.getInstance("SHA-256")
                var bytesDownloaded = 0L

                downloadStream.inputStream.use { input ->
                    FileOutputStream(file).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val bytesRead = input.read(buffer)
                            if (bytesRead == -1) break
                            output.write(buffer, 0, bytesRead)
                            digest.update(buffer, 0, bytesRead)
                            bytesDownloaded += bytesRead
                            _state.value = TrackState.Downloading(
                                info,
                                DownloadProgress(bytesDownloaded, totalBytes),
                            )
                        }
                    }
                }

                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualSha256.equals(info.sha256, ignoreCase = true)) {
                    file.delete()
                    val msg = "SHA-256 mismatch: expected ${info.sha256}, got $actualSha256"
                    logger.e(TAG, "$name: $msg")
                    _state.value = TrackState.Error(msg)
                    return@launch
                }

                logger.i(TAG, "$name: Download complete, SHA-256 verified ($bytesDownloaded bytes)")
                _state.value = TrackState.ReadyToInstall(info, file.absolutePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "$name: Download failed", e)
                _state.value = TrackState.Error("Download failed: ${e.message}", e)
            }
        }
    }

    fun install() {
        val current = _state.value
        if (current !is TrackState.ReadyToInstall) {
            logger.w(TAG, "$name: install() called in invalid state: ${current::class.simpleName}")
            return
        }
        val info = current.info
        val path = current.path
        activeJob = scope.launch {
            try {
                _state.value = TrackState.Installing(info)
                logger.i(TAG, "$name: Installing from $path")

                when (val result = installer.install(path)) {
                    is InstallResult.Success -> {
                        logger.i(TAG, "$name: Install succeeded")
                        _state.value = TrackState.Installed(info)
                    }
                    is InstallResult.Failed -> {
                        logger.e(TAG, "$name: Install failed: ${result.reason}")
                        _state.value = TrackState.Error(result.reason, result.cause)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "$name: Install exception", e)
                _state.value = TrackState.Error("Install failed: ${e.message}", e)
            }
        }
    }

    fun finalizeInstall() {
        val current = _state.value
        if (current !is TrackState.Installed) {
            logger.w(TAG, "$name: finalizeInstall() called in invalid state: ${current::class.simpleName}")
            return
        }
        val path = File(downloadDir, downloadFilename).absolutePath
        logger.i(TAG, "$name: Finalizing install")
        scope.launch { installer.finalize(path) }
    }

    fun dismiss() {
        cancelActiveJob()
        logger.i(TAG, "$name: Dismissed")
        _state.value = TrackState.Idle
    }

    private fun cancelActiveJob() {
        activeJob?.cancel()
        activeJob = null
    }

    companion object {
        private const val TAG = "Update"
        private const val BUFFER_SIZE = 8192
    }
}
