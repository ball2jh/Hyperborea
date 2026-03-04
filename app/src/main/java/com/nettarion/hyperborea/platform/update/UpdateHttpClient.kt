package com.nettarion.hyperborea.platform.update

import java.io.InputStream

interface UpdateHttpClient {
    fun fetchManifest(url: String): String
    fun openDownload(url: String): DownloadStream
}

data class DownloadStream(
    val inputStream: InputStream,
    val contentLength: Long,
)
