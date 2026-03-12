package com.nettarion.hyperborea.sensor.hrm

import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import com.nettarion.hyperborea.core.adapter.DiscoveredSensor
import kotlinx.coroutines.channels.SendChannel

internal class HrmScanCallback(
    private val channel: SendChannel<DiscoveredSensor>,
) : ScanCallback() {

    override fun onScanResult(callbackType: Int, result: ScanResult) {
        val sensor = DiscoveredSensor(
            name = result.device.name,
            address = result.device.address,
            rssi = result.rssi,
        )
        channel.trySend(sensor)
    }

    override fun onBatchScanResults(results: List<ScanResult>) {
        for (result in results) {
            onScanResult(0, result)
        }
    }

    override fun onScanFailed(errorCode: Int) {
        channel.close(IllegalStateException("BLE scan failed with error code $errorCode"))
    }
}
