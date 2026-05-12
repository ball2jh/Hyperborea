package com.nettarion.hyperborea.platform

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.system.SystemController
import com.nettarion.hyperborea.core.system.SystemMonitor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class AndroidSystemController @Inject constructor(
    private val context: Context,
    private val logger: AppLogger,
    private val systemMonitor: SystemMonitor,
) : SystemController {

    override suspend fun requestUsbPermission(): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbManager == null) {
            logger.w(TAG, "UsbManager unavailable")
            return false
        }
        val device = awaitFitProDevice(usbManager)
        if (usbManager.hasPermission(device)) {
            logger.d(TAG, "USB permission already granted for vid=${device.vendorId} pid=${device.productId}")
            return true
        }

        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            pendingIntentFlags,
        )

        // requestPermission() reports the outcome asynchronously via the ACTION_USB_PERMISSION
        // broadcast. Register a receiver, fire the dialog, then suspend until the user answers
        // (or the caller's timeout cancels us) so the prerequisite isn't reported fulfilled
        // before the device is actually accessible.
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    runCatching { context.unregisterReceiver(this) }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) &&
                        usbManager.hasPermission(device)
                    logger.i(
                        TAG,
                        "USB permission ${if (granted) "granted" else "denied"} " +
                            "for vid=${device.vendorId} pid=${device.productId}",
                    )
                    if (cont.isActive) cont.resume(granted)
                }
            }
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }

            try {
                usbManager.requestPermission(device, pendingIntent)
                logger.i(TAG, "USB permission dialog requested for vid=${device.vendorId} pid=${device.productId}")
            } catch (e: Exception) {
                logger.e(TAG, "requestUsbPermission failed: ${e.message}", e)
                runCatching { context.unregisterReceiver(receiver) }
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    /**
     * Returns the currently-attached FitPro [UsbDevice], waiting for one to show up if none is
     * present right now. The hardware power-cycles its USB link roughly every 20 s, so a START
     * tapped during an "off" window shouldn't dead-end — it should just wait for the next attach.
     * Cancellable: the orchestrator wraps the prerequisite's [requestUsbPermission] call in a
     * [kotlinx.coroutines.withTimeoutOrNull] (10 min for this prereq), so a never-arriving device
     * cancels this coroutine and the prerequisite times out instead of hanging forever.
     */
    private suspend fun awaitFitProDevice(usbManager: UsbManager): UsbDevice {
        while (true) {
            usbManager.deviceList.values.firstOrNull {
                it.vendorId == FITPRO_VENDOR_ID && it.productId in FITPRO_PRODUCT_IDS
            }?.let { return it }
            logger.i(TAG, "No FitPro USB device attached — waiting for one to appear…")
            // SystemMonitor observes USB_DEVICE_ATTACHED/DETACHED and republishes its snapshot;
            // suspend until it reports a matching device, then loop to grab the real UsbDevice
            // (the snapshot carries UsbDeviceInfo, not the UsbDevice itself).
            systemMonitor.snapshot.first { snap ->
                snap.usbDevices.any { it.vendorId == FITPRO_VENDOR_ID && it.productId in FITPRO_PRODUCT_IDS }
            }
        }
    }

    private companion object {
        const val TAG = "SystemController"
        const val FITPRO_VENDOR_ID = 0x213C
        // Mirrors FitProAdapter.FITPRO_PRODUCT_IDS (V1=2, V2=3, V2_FTDI=4).
        val FITPRO_PRODUCT_IDS = setOf(2, 3, 4)
        const val ACTION_USB_PERMISSION = "com.nettarion.hyperborea.USB_PERMISSION"
    }
}
