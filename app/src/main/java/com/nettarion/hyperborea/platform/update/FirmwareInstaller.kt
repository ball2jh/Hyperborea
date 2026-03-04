package com.nettarion.hyperborea.platform.update

import android.content.Context
import android.os.PowerManager
import com.nettarion.hyperborea.core.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileWriter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirmwareInstaller @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val logger: AppLogger,
) : UpdateInstaller {

    override fun install(path: String): InstallResult {
        logger.i(TAG, "Writing recovery command for OTA: $path")
        return try {
            val commandDir = File(RECOVERY_COMMAND_DIR)
            commandDir.mkdirs()
            val commandFile = File(commandDir, "command")
            FileWriter(commandFile).use { writer ->
                writer.write("--update_package=$path\n")
            }
            logger.i(TAG, "Recovery command written successfully")
            InstallResult.Success
        } catch (e: Exception) {
            logger.e(TAG, "Failed to write recovery command", e)
            InstallResult.Failed("Failed to write recovery command: ${e.message}", e)
        }
    }

    override fun finalize(path: String) {
        logger.i(TAG, "Rebooting into recovery to apply firmware update")
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        powerManager.reboot("recovery")
    }

    companion object {
        private const val TAG = "FirmwareInstaller"
        private const val RECOVERY_COMMAND_DIR = "/cache/recovery"
    }
}
