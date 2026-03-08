package com.nettarion.hyperborea.platform.update

import com.nettarion.hyperborea.core.AppLogger
import java.io.ByteArrayInputStream
import java.io.IOException

class FakeUpdateHttpClient : UpdateHttpClient {
    var manifestResponse: String? = null
    var manifestException: Exception? = null
    var downloadBytes: ByteArray = ByteArray(0)
    var downloadException: Exception? = null

    override fun fetchManifest(url: String, headers: Map<String, String>): String {
        manifestException?.let { throw it }
        return manifestResponse ?: throw IOException("No manifest configured")
    }

    override fun openDownload(url: String, headers: Map<String, String>): DownloadStream {
        downloadException?.let { throw it }
        return DownloadStream(
            inputStream = ByteArrayInputStream(downloadBytes),
            contentLength = downloadBytes.size.toLong(),
        )
    }
}

class FakeUpdateInstaller : UpdateInstaller {
    var installResult: InstallResult = InstallResult.Success
    var installCalled = false
    var finalizeCalled = false
    var lastInstallPath: String? = null
    var lastFinalizePath: String? = null

    override suspend fun install(path: String): InstallResult {
        installCalled = true
        lastInstallPath = path
        return installResult
    }

    override suspend fun finalize(path: String) {
        finalizeCalled = true
        lastFinalizePath = path
    }
}

class FakeAppLogger : AppLogger {
    override fun d(tag: String, message: String) {}
    override fun i(tag: String, message: String) {}
    override fun w(tag: String, message: String) {}
    override fun e(tag: String, message: String, throwable: Throwable?) {}
}
