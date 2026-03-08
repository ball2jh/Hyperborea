package com.nettarion.hyperborea.platform.update

import java.io.InputStream

interface UpdateHttpClient {
    fun fetchManifest(url: String, headers: Map<String, String> = emptyMap()): String
    fun openDownload(url: String, headers: Map<String, String> = emptyMap()): DownloadStream
}

data class DownloadStream(
    val inputStream: InputStream,
    val contentLength: Long,
)
