package com.nettarion.hyperborea.platform.support

import com.nettarion.hyperborea.BuildConfig
import com.nettarion.hyperborea.core.LogStore
import com.nettarion.hyperborea.core.adapter.BroadcastAdapter
import com.nettarion.hyperborea.core.adapter.HardwareAdapter
import com.nettarion.hyperborea.core.system.SystemLogStore
import com.nettarion.hyperborea.core.system.SystemMonitor
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SupportDiagnosticsBuilder(
    private val logStore: LogStore,
    private val systemLogStore: SystemLogStore,
    private val systemMonitor: SystemMonitor,
    private val hardwareAdapter: HardwareAdapter,
    private val broadcastAdapters: Set<BroadcastAdapter>,
) {

    fun build(deviceUuid: String?): JSONObject {
        val snapshot = systemMonitor.snapshot.value
        val identity = hardwareAdapter.deviceIdentity.value

        val diagnostics = JSONObject().apply {
            identity?.let {
                put("serialNumber", it.serialNumber ?: JSONObject.NULL)
                put("firmwareVersion", it.firmwareVersion ?: JSONObject.NULL)
                put("hardwareVersion", it.hardwareVersion ?: JSONObject.NULL)
                put("model", it.model ?: JSONObject.NULL)
            }
            val status = snapshot.status
            put("bleAdvertisingSupported", status.isBluetoothLeAdvertisingSupported)
            put("bleEnabled", status.isBluetoothLeEnabled)
            put("wifiEnabled", status.isWifiEnabled)
            put("usbHostAvailable", status.isUsbHostAvailable)
            put("adbEnabled", status.isAdbEnabled)
            put("rootAvailable", status.isRootAvailable)
            put("usbDevices", JSONArray().apply {
                snapshot.usbDevices.forEach { device ->
                    put(JSONObject().apply {
                        put("vendorId", "%04X".format(device.vendorId))
                        put("productId", "%04X".format(device.productId))
                        put("productName", device.productName ?: JSONObject.NULL)
                    })
                }
            })
            put("broadcastAdapters", JSONArray().apply {
                broadcastAdapters.sortedBy { it.id.ordinal }.forEach { adapter ->
                    put(JSONObject().apply {
                        put("id", adapter.id.name)
                        put("state", adapter.state.value.javaClass.simpleName)
                        put("clientCount", adapter.connectedClients.value.size)
                    })
                }
            })
            put("componentCount", snapshot.components.size)
        }

        return JSONObject().apply {
            put("deviceUuid", deviceUuid ?: JSONObject.NULL)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()))
            put("appLogs", truncateLogExport(logStore.export(), 400_000))
            put("systemLogs", truncateLogExport(systemLogStore.export(), 400_000))
            put("diagnostics", diagnostics)
        }
    }
}
