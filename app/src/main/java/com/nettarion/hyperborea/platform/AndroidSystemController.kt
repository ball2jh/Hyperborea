package com.nettarion.hyperborea.platform

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.system.SystemController
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidSystemController @Inject constructor(
    private val context: Context,
    private val logger: AppLogger,
) : SystemController {

    override suspend fun requestUsbPermission(): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbManager == null) {
            logger.w(TAG, "UsbManager unavailable")
            return false
        }
        val device = usbManager.deviceList.values.firstOrNull { it.vendorId == FITPRO_VENDOR_ID }
        if (device == null) {
            logger.w(TAG, "No FitPro USB device attached; nothing to request permission for")
            return false
        }
        if (usbManager.hasPermission(device)) {
            logger.d(TAG, "USB permission already granted for vid=${device.vendorId} pid=${device.productId}")
            return true
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags,
        )
        return try {
            usbManager.requestPermission(device, pendingIntent)
            logger.i(TAG, "USB permission dialog requested for vid=${device.vendorId} pid=${device.productId}")
            true
        } catch (e: Exception) {
            logger.e(TAG, "requestUsbPermission failed: ${e.message}", e)
            false
        }
    }

    private companion object {
        const val TAG = "SystemController"
        const val FITPRO_VENDOR_ID = 0x213C
        const val ACTION_USB_PERMISSION = "com.nettarion.hyperborea.USB_PERMISSION"
    }
}
