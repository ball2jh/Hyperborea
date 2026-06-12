package com.nettarion.hyperborea.hardware.fitpro.transport

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import com.nettarion.hyperborea.core.AppLogger

class UsbHidTransportFactory(
    private val context: Context,
    private val logger: AppLogger,
) : HidTransportFactory {

    override fun create(vendorId: Int, productId: Int): HidTransportResult {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        val device = usbManager.deviceList.values.firstOrNull { d ->
            d.vendorId == vendorId && FITPRO_PRODUCT_IDS.any { pid -> d.productId == pid }
        } ?: throw IllegalStateException(
            "No USB device found with vendor=0x${vendorId.toString(16)}, product in $FITPRO_PRODUCT_IDS"
        )

        val interfaces = (0 until device.interfaceCount).map { device.getInterface(it) }

        // Prefer the HID interface. Some boards (e.g. the "iFIT-LargeX", pid 3) expose the
        // same 64-byte interrupt IN/OUT endpoint pair but declare the interface as
        // vendor-specific instead of HID.
        val usbInterface = interfaces.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
            ?: interfaces.firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC && it.hasInAndOutEndpoints()
            }
            ?: throw IllegalStateException(
                "No HID or vendor-specific data interface on USB device; " +
                    "interfaces: ${interfaces.joinToString { it.describe() }}"
            )

        var inEndpoint: android.hardware.usb.UsbEndpoint? = null
        var outEndpoint: android.hardware.usb.UsbEndpoint? = null

        for (i in 0 until usbInterface.endpointCount) {
            val ep = usbInterface.getEndpoint(i)
            if (ep.direction == UsbConstants.USB_DIR_IN && inEndpoint == null) {
                inEndpoint = ep
            } else if (ep.direction == UsbConstants.USB_DIR_OUT && outEndpoint == null) {
                outEndpoint = ep
            }
        }

        requireNotNull(inEndpoint) { "No IN endpoint found on ${usbInterface.describe()}" }
        requireNotNull(outEndpoint) { "No OUT endpoint found on ${usbInterface.describe()}" }

        logger.i(TAG, "Found device vendor=0x${device.vendorId.toString(16)}, product=${device.productId}")
        logger.i(TAG, "Using ${usbInterface.describe()}, IN=0x${inEndpoint.address.toString(16)}, OUT=0x${outEndpoint.address.toString(16)}")

        val connection = usbManager.openDevice(device)
            ?: throw IllegalStateException("Failed to open USB device (permission denied?)")

        // openDevice() succeeding means we hold permission, so the serial is readable here —
        // the boot diagnostic runs pre-grant and can't see it. Whether the controller reports
        // one at all decides if the OS can remember an "always open" default for it.
        val serial = runCatching { device.serialNumber }.getOrNull()
        logger.i(TAG, "Device serial: ${serial ?: "none reported"}")

        val transport = UsbHidTransport(
            context, connection, usbInterface, inEndpoint, outEndpoint, device.deviceName, logger,
        )
        return HidTransportResult(transport, device.productId)
    }

    private fun android.hardware.usb.UsbInterface.hasInAndOutEndpoints(): Boolean {
        val endpoints = (0 until endpointCount).map { getEndpoint(it) }
        return endpoints.any { it.direction == UsbConstants.USB_DIR_IN } &&
            endpoints.any { it.direction == UsbConstants.USB_DIR_OUT }
    }

    private fun android.hardware.usb.UsbInterface.describe(): String {
        val eps = (0 until endpointCount).joinToString(", ") { i ->
            val ep = getEndpoint(i)
            val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "in" else "out"
            val type = when (ep.type) {
                UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "ctrl"
                UsbConstants.USB_ENDPOINT_XFER_ISOC -> "iso"
                UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                UsbConstants.USB_ENDPOINT_XFER_INT -> "int"
                else -> "type${ep.type}"
            }
            "0x%02x/%s/%s/%d".format(ep.address, dir, type, ep.maxPacketSize)
        }
        return "if=%d cls=%02x/%02x/%02x eps=[%s]".format(
            id, interfaceClass, interfaceSubclass, interfaceProtocol, eps,
        )
    }

    companion object {
        private const val TAG = "UsbHidTransportFactory"
        val FITPRO_PRODUCT_IDS = setOf(2, 3, 4)
    }
}
