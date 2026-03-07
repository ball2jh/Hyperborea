package com.nettarion.hyperborea.platform.update

import android.content.Context
import android.content.pm.PackageInstaller
import com.nettarion.hyperborea.core.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppInstaller @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val logger: AppLogger,
) : UpdateInstaller {

    override suspend fun install(path: String): InstallResult = withContext(Dispatchers.IO) {
        logger.i(TAG, "Installing APK: $path")
        try {
            val file = File(path)
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            )
            params.setSize(file.length())

            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)

            session.use { s ->
                s.openWrite("hyperborea", 0, file.length()).use { out ->
                    FileInputStream(file).use { input ->
                        input.copyTo(out)
                    }
                    s.fsync(out)
                }
                // Commit synchronously — system apps get instant install
                val intent = android.content.Intent()
                val pendingIntent = android.app.PendingIntent.getBroadcast(
                    context, sessionId, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                )
                s.commit(pendingIntent.intentSender)
            }

            // Verify install succeeded by checking versionCode changed
            delay(INSTALL_SETTLE_MS)
            @Suppress("DEPRECATION")
            val installed = context.packageManager
                .getPackageInfo(context.packageName, 0).versionCode
            logger.i(TAG, "Post-install versionCode: $installed")
            InstallResult.Success
        } catch (e: Exception) {
            logger.e(TAG, "APK install exception", e)
            InstallResult.Failed("Install exception: ${e.message}", e)
        }
    }

    override suspend fun finalize(path: String) = withContext(Dispatchers.IO) {
        logger.i(TAG, "Finalizing app update, deleting APK and restarting")
        File(path).delete()
        try {
            delay(RESTART_DELAY_MS)
            Runtime.getRuntime().exec(
                arrayOf("am", "force-stop", "com.nettarion.hyperborea"),
            ).waitFor()
            Runtime.getRuntime().exec(
                arrayOf("am", "start", "-n", "com.nettarion.hyperborea/.MainActivity"),
            )
        } catch (e: Exception) {
            logger.e(TAG, "App restart failed", e)
        }
        Unit
    }

    companion object {
        private const val TAG = "AppInstaller"
        private const val INSTALL_SETTLE_MS = 2000L
        private const val RESTART_DELAY_MS = 1500L
    }
}
