package com.nettarion.hyperborea.platform.update

sealed interface TrackState {
    data object Idle : TrackState
    data class Available(val info: UpdateInfo) : TrackState
    data class Downloading(val info: UpdateInfo, val progress: DownloadProgress) : TrackState
    data class ReadyToInstall(val info: UpdateInfo, val path: String) : TrackState
    data class Installing(val info: UpdateInfo) : TrackState
    data class Installed(val info: UpdateInfo) : TrackState
    data class Error(val message: String, val cause: Throwable? = null) : TrackState
}

data class UpdateInfo(
    val version: String,
    val url: String,
    val sha256: String,
    val releaseNotes: String,
)

data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
)
