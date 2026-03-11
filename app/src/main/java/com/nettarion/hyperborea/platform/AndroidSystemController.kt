package com.nettarion.hyperborea.platform

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.system.SystemController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidSystemController @Inject constructor(
    private val context: Context,
    private val logger: AppLogger,
) : SystemController {

    @Volatile
    private var cachedUsbDevice: UsbDevice? = null

    fun onUsbDeviceAttached(device: UsbDevice) {
        cachedUsbDevice = device
        logger.d(TAG, "Cached USB device: vid=${device.vendorId}, pid=${device.productId}")
    }

    override suspend fun stopService(packageName: String, className: String): Boolean {
        logger.d(TAG, "stopService: $packageName/$className")
        return executeShellCommand("am stopservice $packageName/$className").success
    }

    @Suppress("DEPRECATION")
    override suspend fun forceStopPackage(packageName: String): Boolean {
        logger.d(TAG, "forceStopPackage: $packageName")
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val method = ActivityManager::class.java.getMethod("forceStopPackage", String::class.java)
            method.invoke(am, packageName)
            true
        } catch (e: Exception) {
            logger.w(TAG, "Reflection forceStopPackage failed, falling back to shell: ${e.message}")
            executeShellCommand("am force-stop $packageName").success
        }
    }

    override suspend fun disablePackage(packageName: String): Boolean {
        logger.d(TAG, "disablePackage: $packageName")
        return executeShellCommand("pm disable $packageName").success
    }

    override suspend fun enablePackage(packageName: String): Boolean {
        logger.d(TAG, "enablePackage: $packageName")
        return executeShellCommand("pm enable $packageName").success
    }

    override suspend fun uninstallPackage(packageName: String): Boolean {
        logger.d(TAG, "uninstallPackage: $packageName")
        return executeShellCommand("pm uninstall $packageName").success
    }

    override suspend fun disableComponent(packageName: String, className: String): Boolean {
        logger.d(TAG, "disableComponent: $packageName/$className")
        return try {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(packageName, className),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            true
        } catch (e: Exception) {
            logger.w(TAG, "PM disableComponent failed, falling back to shell: ${e.message}")
            executeShellCommand("pm disable $packageName/$className").success
        }
    }

    override suspend fun enableComponent(packageName: String, className: String): Boolean {
        logger.d(TAG, "enableComponent: $packageName/$className")
        return try {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(packageName, className),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            true
        } catch (e: Exception) {
            logger.w(TAG, "PM enableComponent failed, falling back to shell: ${e.message}")
            executeShellCommand("pm enable $packageName/$className").success
        }
    }

    @SuppressLint("SoonBlockedPrivateApi") // API 25 target — no hidden API restrictions
    override suspend fun grantUsbPermission(packageName: String): Boolean {
        logger.d(TAG, "grantUsbPermission: $packageName")
        return try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val device = cachedUsbDevice ?: usbManager.deviceList.values.firstOrNull()
            if (device == null) {
                logger.w(TAG, "No USB device available for permission grant")
                return false
            }
            val uid = context.packageManager.getPackageUid(packageName, 0)
            val serviceField = UsbManager::class.java.getDeclaredField("mService")
            serviceField.isAccessible = true
            val service = serviceField.get(usbManager)
            val grantMethod = service.javaClass.getMethod(
                "grantDevicePermission",
                UsbDevice::class.java,
                Int::class.javaPrimitiveType,
            )
            grantMethod.invoke(service, device, uid)
            logger.i(TAG, "USB permission granted to $packageName (uid=$uid)")
            true
        } catch (e: Exception) {
            logger.e(TAG, "grantUsbPermission failed: ${e.message}", e)
            false
        }
    }

    override suspend fun revokeUsbPermissions(packageName: String): Boolean {
        logger.d(TAG, "revokeUsbPermissions: $packageName (delegating to forceStopPackage)")
        return forceStopPackage(packageName)
    }

    private suspend fun executeShellCommand(command: String): ShellResult =
        withContext(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                val output = process.inputStream.bufferedReader().readText().trim()
                val error = process.errorStream.bufferedReader().readText().trim()
                val exitCode = process.waitFor()
                process.destroy()
                if (exitCode != 0) {
                    logger.w(TAG, "Shell command failed (exit=$exitCode): $command — $error")
                }
                ShellResult(exitCode == 0, exitCode, output, error)
            } catch (e: Exception) {
                logger.e(TAG, "Shell command exception: $command — ${e.message}", e)
                ShellResult(false, -1, "", e.message ?: "")
            }
        }

    private data class ShellResult(
        val success: Boolean,
        val exitCode: Int,
        val output: String,
        val error: String,
    )

    private companion object {
        const val TAG = "SystemController"
    }
}
