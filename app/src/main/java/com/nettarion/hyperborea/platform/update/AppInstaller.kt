package com.nettarion.hyperborea.platform.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.nettarion.hyperborea.core.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands a downloaded APK to the system package installer.
 *
 * The previous implementation used `PackageInstaller.Session.commit()` for a silent
 * install, which required `android.permission.INSTALL_PACKAGES` — a `signature|privileged`
 * permission only available when installed in `/system/priv-app/`. As a regular user
 * app we just fire `Intent.ACTION_VIEW` with the APK URI; Android shows its standard
 * "Install update?" confirmation dialog and proceeds on user accept. The package
 * replacement still triggers `ACTION_MY_PACKAGE_REPLACED`, which `UpdateRestartReceiver`
 * handles to relaunch the activity.
 *
 * On API 26+ the system requires the user to grant "Install unknown apps" once per
 * source app. If they haven't, `startActivity(ACTION_VIEW)` produces an "Install blocked"
 * dialog with no actionable path — so we pre-flight `canRequestPackageInstalls()` and
 * route the user to the right Settings screen the first time. Once granted, the
 * preference persists per package.
 *   https://developer.android.com/about/versions/oreo/android-8.0-changes#unknown-sources
 */
@Singleton
class AppInstaller @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val logger: AppLogger,
) : UpdateInstaller {

    override suspend fun install(path: String): InstallResult = withContext(Dispatchers.IO) {
        logger.i(TAG, "Installing APK: $path")
        val file = File(path)
        if (!file.exists()) {
            return@withContext InstallResult.Failed("Update APK not found at $path")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return@withContext launchUnknownSourcesSettings()
        }
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
            logger.i(TAG, "System installer dialog launched; awaiting user confirmation")
            InstallResult.Success
        } catch (e: Exception) {
            logger.e(TAG, "Failed to launch system installer", e)
            InstallResult.Failed("Failed to launch system installer: ${e.message}", e)
        }
    }

    private fun launchUnknownSourcesSettings(): InstallResult {
        logger.w(TAG, "Install Unknown Apps not granted; routing user to Settings")
        return try {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            InstallResult.Failed(
                "Allow installing unknown apps for Hyperborea, then re-run the update.",
            )
        } catch (e: Exception) {
            logger.e(TAG, "Failed to open Install Unknown Apps settings", e)
            InstallResult.Failed(
                "Allow installing unknown apps for Hyperborea in Settings, then re-run the update.",
                e,
            )
        }
    }

    override suspend fun finalize(path: String) = withContext(Dispatchers.IO) {
        // Reached when the user cancels the system installer or after install
        // completes without process restart. Drop the staged APK either way.
        logger.i(TAG, "Finalizing app update")
        File(path).delete()
        Unit
    }

    companion object {
        private const val TAG = "AppInstaller"
    }
}
