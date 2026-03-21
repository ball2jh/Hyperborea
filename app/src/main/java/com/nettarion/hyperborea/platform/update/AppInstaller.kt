package com.nettarion.hyperborea.platform.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.nettarion.hyperborea.core.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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

                // Delete APK before commit — data is already in the session's
                // internal storage. PackageManager kills our process during
                // package replacement, so cleanup must happen first.
                // The system sends ACTION_MY_PACKAGE_REPLACED after install,
                // which UpdateRestartReceiver handles to relaunch the activity.
                file.delete()

                val intent = Intent()
                val pendingIntent = PendingIntent.getBroadcast(
                    context, sessionId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                s.commit(pendingIntent.intentSender)
            }

            // Process will likely be killed before reaching here.
            logger.i(TAG, "Commit sent, awaiting process restart")
            InstallResult.Success
        } catch (e: Exception) {
            logger.e(TAG, "APK install exception", e)
            InstallResult.Failed("Install exception: ${e.message}", e)
        }
    }

    override suspend fun finalize(path: String) = withContext(Dispatchers.IO) {
        // Normally unreachable for self-updates (process dies during install).
        // Clean up in case it runs.
        logger.i(TAG, "Finalizing app update")
        File(path).delete()
        Unit
    }

    companion object {
        private const val TAG = "AppInstaller"
    }
}
