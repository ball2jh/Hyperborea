package com.nettarion.hyperborea.platform

import com.nettarion.hyperborea.BuildConfig

import com.nettarion.hyperborea.core.adapter.AdapterState
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.adapter.BroadcastAdapter
import com.nettarion.hyperborea.core.system.CaptureConfig
import com.nettarion.hyperborea.core.system.ComponentState
import com.nettarion.hyperborea.core.system.ComponentType
import com.nettarion.hyperborea.core.adapter.HardwareAdapter
import com.nettarion.hyperborea.core.system.SystemLogCapture
import com.nettarion.hyperborea.core.system.SystemMonitor
import com.nettarion.hyperborea.platform.LogExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiagnosticBootstrap @Inject constructor(
    private val logger: AppLogger,
    private val systemMonitor: SystemMonitor,
    private val systemLogCapture: SystemLogCapture,
    private val hardwareAdapter: HardwareAdapter,
    private val broadcastAdapters: Set<@JvmSuppressWildcards BroadcastAdapter>,
    private val logExporter: LogExporter,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            lockUpdateZip()

            logger.i(TAG, "=== Hyperborea diagnostic boot ===")
            logger.i(TAG, "Build: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")

            // Start system log capture (logcat main+system buffers)
            systemLogCapture.start(CaptureConfig(logcat = true, dmesg = false))

            // Capture initial system snapshot
            systemMonitor.refresh()
            val snapshot = systemMonitor.snapshot.value
            val s = snapshot.status
            logger.i(TAG, "System status — BLE: ${s.isBluetoothLeEnabled}, BLE advertising: ${s.isBluetoothLeAdvertisingSupported}, WiFi: ${s.isWifiEnabled}, USB: ${s.isUsbHostAvailable}, ADB: ${s.isAdbEnabled}")
            logger.i(TAG, "WiFi IP: ${s.wifiIpAddress ?: "none"}")
            logger.i(TAG, "Root: ${s.isRootAvailable}, SELinux enforcing: ${s.isSeLinuxEnforcing}")
            logger.i(TAG, "USB devices: ${snapshot.usbDevices.size}")
            for (dev in snapshot.usbDevices) {
                logger.i(TAG, "  USB ${dev.manufacturerName ?: "?"} / ${dev.productName ?: "?"} (vid=${dev.vendorId}, pid=${dev.productId}) at ${dev.deviceName}")
            }

            logger.i(TAG, "Installed packages: ${snapshot.packages.size}")

            // Log component counts by type
            val byType = snapshot.components.groupBy { it.type }
            logger.i(
                TAG,
                "Components: ${byType[ComponentType.ACTIVITY]?.size ?: 0} activities, " +
                    "${byType[ComponentType.SERVICE]?.size ?: 0} services, " +
                    "${byType[ComponentType.BROADCAST_RECEIVER]?.size ?: 0} receivers, " +
                    "${byType[ComponentType.CONTENT_PROVIDER]?.size ?: 0} providers",
            )

            // Log running services
            val running = snapshot.components.filter {
                it.type == ComponentType.SERVICE &&
                    (it.state == ComponentState.RUNNING || it.state == ComponentState.RUNNING_FOREGROUND)
            }
            logger.i(TAG, "Running services: ${running.size}")
            for (svc in running.take(20)) {
                logger.d(TAG, "  ${svc.packageName}/${svc.className} [${svc.state}]")
            }

            // Log disabled components
            val disabled = snapshot.components.filter { it.state == ComponentState.DISABLED }
            if (disabled.isNotEmpty()) {
                logger.i(TAG, "Disabled components: ${disabled.size}")
                for (comp in disabled.take(20)) {
                    logger.d(TAG, "  ${comp.packageName}/${comp.className} [${comp.type}]")
                }
            }

            // Log adapter states
            logger.i(TAG, "Hardware adapter: ${hardwareAdapter.state.value.describe()}")
            logger.i(TAG, "Hardware prerequisites: ${hardwareAdapter.prerequisites.size}")
            for (prereq in hardwareAdapter.prerequisites) {
                logger.i(TAG, "  ${prereq.description} — met: ${prereq.isMet(snapshot)}")
            }
            logger.i(TAG, "Hardware canOperate: ${hardwareAdapter.canOperate(snapshot)}")

            for (adapter in broadcastAdapters) {
                val name = adapter::class.simpleName ?: "unknown"
                logger.i(TAG, "Broadcast adapter $name: ${adapter.state.value.describe()}, canOperate: ${adapter.canOperate(snapshot)}")
            }

            // One-time component dump
            try {
                val file = logExporter.exportComponents(snapshot)
                logger.i(TAG, "Components exported to ${file.absolutePath}")
            } catch (e: Exception) {
                logger.e(TAG, "Component export failed: ${e.message}", e)
            }

            logger.i(TAG, "=== Diagnostic boot complete ===")

            // Auto-export combined logs every 30 seconds
            while (isActive) {
                delay(EXPORT_INTERVAL_MS)
                try {
                    logExporter.exportCombined()
                } catch (e: Exception) {
                    logger.e(TAG, "Log export failed: ${e.message}", e)
                }
            }
        }
    }

    private suspend fun lockUpdateZip() = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf(
                "su", "-c", "touch /data/update.zip && toybox chattr +i /data/update.zip",
            ))
            val error = process.errorStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            process.destroy()
            if (exitCode == 0 && error.isEmpty()) {
                logger.i(TAG, "Locked /data/update.zip (immutable)")
            } else {
                logger.w(TAG, "Failed to lock /data/update.zip (exit=$exitCode): $error")
            }
        } catch (e: Exception) {
            logger.w(TAG, "Failed to lock /data/update.zip: ${e.message}")
        }
    }

    private fun AdapterState.describe(): String = when (this) {
        is AdapterState.Inactive -> "Inactive"
        is AdapterState.Activating -> "Activating"
        is AdapterState.Active -> "Active"
        is AdapterState.Error -> "Error: $message"
    }

    private companion object {
        const val TAG = "Diagnostic"
        const val EXPORT_INTERVAL_MS = 30_000L
    }
}
