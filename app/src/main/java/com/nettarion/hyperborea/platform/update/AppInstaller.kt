package com.nettarion.hyperborea.platform.update

import com.nettarion.hyperborea.core.AppLogger
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppInstaller @Inject constructor(
    private val logger: AppLogger,
) : UpdateInstaller {

    override fun install(path: String): InstallResult {
        logger.i(TAG, "Installing APK: $path")
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "pm install -r $path"))
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            val exitCode = process.waitFor()
            if (exitCode == 0 && output.contains("Success")) {
                logger.i(TAG, "APK install succeeded")
                InstallResult.Success
            } else {
                logger.e(TAG, "APK install failed: exit=$exitCode output=$output")
                InstallResult.Failed("pm install failed: $output")
            }
        } catch (e: Exception) {
            logger.e(TAG, "APK install exception", e)
            InstallResult.Failed("Install exception: ${e.message}", e)
        }
    }

    override fun finalize(path: String) {
        logger.i(TAG, "Finalizing app update, deleting APK and restarting")
        File(path).delete()
        try {
            Thread.sleep(RESTART_DELAY_MS)
            Runtime.getRuntime().exec(
                arrayOf(
                    "su", "-c",
                    "am force-stop com.nettarion.hyperborea && " +
                        "am start -n com.nettarion.hyperborea/.MainActivity",
                ),
            )
        } catch (e: Exception) {
            logger.e(TAG, "App restart failed", e)
        }
    }

    companion object {
        private const val TAG = "AppInstaller"
        private const val RESTART_DELAY_MS = 1500L
    }
}
