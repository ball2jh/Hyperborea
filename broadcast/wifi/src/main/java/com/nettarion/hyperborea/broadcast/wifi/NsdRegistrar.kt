package com.nettarion.hyperborea.broadcast.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.Settings
import com.nettarion.hyperborea.core.AppLogger
import java.net.NetworkInterface

class NsdRegistrar(
    private val context: Context,
    private val logger: AppLogger,
    private val onRegistrationFailed: ((Int) -> Unit)? = null,
) {
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun register(port: Int, deviceName: String) {
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (manager == null) {
            logger.e(TAG, "NsdManager not available")
            return
        }
        nsdManager = manager

        val mac = getWifiMacAddress()
        val suffix = macSuffix(mac)
        val macFormatted = mac.joinToString(":") { "%02X".format(it) }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$deviceName $suffix"
            serviceType = SERVICE_TYPE
            setPort(port)
            // TXT keys are fixed by the discovery protocol the fitness apps implement — clients
            // look these names up verbatim, so the framework's "key lengths > 9 are discouraged"
            // logcat warning cannot be avoided by renaming them.
            setAttribute("ble-service-uuids", "00001826-0000-1000-8000-00805F9B34FB,00001818-0000-1000-8000-00805F9B34FB")
            setAttribute("mac-address", macFormatted)
            setAttribute("serial-number", mac.joinToString("") { "%02X".format(it) })
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                logger.e(TAG, "mDNS registration failed: error=$errorCode")
                onRegistrationFailed?.invoke(errorCode)
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                logger.e(TAG, "mDNS unregistration failed: error=$errorCode")
            }

            override fun onServiceRegistered(info: NsdServiceInfo) {
                logger.i(TAG, "mDNS registered: ${info.serviceName} on port $port")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                logger.i(TAG, "mDNS unregistered: ${info.serviceName}")
            }
        }
        registrationListener = listener

        manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun unregister() {
        val listener = registrationListener ?: return
        try {
            nsdManager?.unregisterService(listener)
        } catch (e: IllegalArgumentException) {
            logger.d(TAG, "mDNS already unregistered")
        }
        registrationListener = null
        nsdManager = null
    }

    // HardwareIds: ANDROID_ID is only the fallback when wlan0's real MAC is unavailable, and it's
    // used solely to derive a stable mDNS service identifier for the fitness protocol — not for
    // tracking/advertising. No alternative identifier fits the role.
    @SuppressLint("HardwareIds")
    private fun getWifiMacAddress(): ByteArray {
        try {
            val iface = NetworkInterface.getByName("wlan0")
            if (iface != null) {
                val hw = iface.hardwareAddress
                if (hw != null && hw.size == 6) return hw
            }
        } catch (e: Exception) {
            logger.w(TAG, "Failed to read wlan0 MAC: ${e.message}")
        }
        // Fallback: deterministic from ANDROID_ID to avoid deprecated serial APIs.
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ).orEmpty().ifBlank { "unknown" }
        val hash = androidId.hashCode()
        return byteArrayOf(
            0x02, // locally administered bit set
            ((hash shr 24) and 0xFF).toByte(),
            ((hash shr 16) and 0xFF).toByte(),
            ((hash shr 8) and 0xFF).toByte(),
            (hash and 0xFF).toByte(),
            ((hash ushr 4) and 0xFF).toByte(),
        )
    }

    private fun macSuffix(mac: ByteArray): String =
        "%02X%02X".format(mac[4], mac[5])

    companion object {
        private const val TAG = "NsdRegistrar"
        const val SERVICE_TYPE = "_wahoo-fitness-tnp._tcp"
    }
}
