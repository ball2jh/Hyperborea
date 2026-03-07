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

        val usbInterface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
            ?: throw IllegalStateException("No HID interface found on USB device")

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

        requireNotNull(inEndpoint) { "No IN endpoint found on HID interface" }
        requireNotNull(outEndpoint) { "No OUT endpoint found on HID interface" }

        logger.i(TAG, "Found device vendor=0x${device.vendorId.toString(16)}, product=${device.productId}")
        logger.d(TAG, "HID interface=${usbInterface.id}, IN=${inEndpoint.address}, OUT=${outEndpoint.address}")

        val connection = usbManager.openDevice(device)
            ?: throw IllegalStateException("Failed to open USB device (permission denied?)")

        val transport = UsbHidTransport(connection, usbInterface, inEndpoint, outEndpoint, logger)
        return HidTransportResult(transport, device.productId)
    }

    companion object {
        private const val TAG = "UsbHidTransportFactory"
        val FITPRO_PRODUCT_IDS = setOf(2, 3, 4)
    }
}
