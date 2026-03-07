package com.nettarion.hyperborea.platform

import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.provider.Settings
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.ComponentState
import com.nettarion.hyperborea.core.ComponentType
import com.nettarion.hyperborea.core.DeclaredComponent
import com.nettarion.hyperborea.core.InstalledPackage
import com.nettarion.hyperborea.core.SystemMonitor
import com.nettarion.hyperborea.core.SystemSnapshot
import com.nettarion.hyperborea.core.SystemStatus
import com.nettarion.hyperborea.core.UsbDeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidSystemMonitor @Inject constructor(
    private val context: Context,
    private val logger: AppLogger,
) : SystemMonitor {

    private val _snapshot = MutableStateFlow(EMPTY_SNAPSHOT)
    override val snapshot: StateFlow<SystemSnapshot> = _snapshot.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            logger.d(TAG, "Broadcast received: ${intent.action}")
            updateStatus()
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(receiver, filter)
        logger.i(TAG, "System monitor started")
    }

    override suspend fun refresh() {
        withContext(Dispatchers.IO) {
            _snapshot.value = captureFullSnapshot()
        }
        logger.d(TAG, "Full snapshot captured: ${_snapshot.value.status}")
    }

    /**
     * Lightweight update: only refreshes system status and USB devices.
     * Called reactively from broadcast receivers — no component/package scan.
     */
    private fun updateStatus() {
        val current = _snapshot.value
        _snapshot.value = current.copy(
            status = captureStatus(),
            usbDevices = captureUsbDevices(),
            timestamp = System.currentTimeMillis(),
        )
    }

    private fun captureFullSnapshot(): SystemSnapshot {
        return SystemSnapshot(
            status = captureStatus(),
            packages = capturePackages(),
            components = captureComponents(),
            usbDevices = captureUsbDevices(),
            timestamp = System.currentTimeMillis(),
        )
    }

    @Suppress("DEPRECATION")
    private fun captureStatus(): SystemStatus {
        val pm = context.packageManager

        // Bluetooth
        val btAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val bleEnabled = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) &&
            btAdapter?.isEnabled == true
        val bleAdvertisingSupported = bleEnabled &&
            (btAdapter?.isMultipleAdvertisementSupported == true ||
                btAdapter?.bluetoothLeAdvertiser != null)

        // WiFi + IP
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiEnabled = pm.hasSystemFeature(PackageManager.FEATURE_WIFI) &&
            wifiManager?.isWifiEnabled == true
        val wifiIp = if (wifiEnabled) {
            val ip = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (ip != 0) formatIpAddress(ip) else null
        } else null

        // USB
        val usbHost = pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

        // ADB
        val adbEnabled = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED) == 1
        } catch (_: Settings.SettingNotFoundException) {
            false
        }

        // Root
        val rootAvailable = checkRootAvailable()

        // SELinux
        val seLinuxEnforcing = checkSeLinuxEnforcing()

        return SystemStatus(
            isBluetoothLeEnabled = bleEnabled,
            isBluetoothLeAdvertisingSupported = bleAdvertisingSupported,
            isWifiEnabled = wifiEnabled,
            wifiIpAddress = wifiIp,
            isUsbHostAvailable = usbHost,
            isAdbEnabled = adbEnabled,
            isRootAvailable = rootAvailable,
            isSeLinuxEnforcing = seLinuxEnforcing,
        )
    }

    private fun formatIpAddress(ip: Int): String =
        "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"

    private fun checkRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val exitCode = process.waitFor()
            process.destroy()
            exitCode == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun checkSeLinuxEnforcing(): Boolean {
        return try {
            val file = File("/sys/fs/selinux/enforce")
            if (file.exists()) file.readText().trim() == "1" else false
        } catch (_: Exception) {
            // Fallback: try getenforce command
            try {
                val process = Runtime.getRuntime().exec("getenforce")
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                process.destroy()
                output.equals("Enforcing", ignoreCase = true)
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun captureUsbDevices(): List<UsbDeviceInfo> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        return usbManager?.deviceList?.values?.map { dev ->
            UsbDeviceInfo(
                vendorId = dev.vendorId,
                productId = dev.productId,
                deviceName = dev.deviceName,
                manufacturerName = dev.manufacturerName,
                productName = dev.productName,
            )
        } ?: emptyList()
    }

    @Suppress("DEPRECATION")
    private fun capturePackages(): List<InstalledPackage> {
        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS
        val packages = context.packageManager.getInstalledPackages(flags)
        return packages.map { it.toInstalledPackage() }
    }

    @Suppress("DEPRECATION")
    private fun captureComponents(): List<DeclaredComponent> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningServices = am.getRunningServices(Int.MAX_VALUE)
        val runningSet = runningServices.associate {
            "${it.service.packageName}/${it.service.className}" to it.foreground
        }

        val pm = context.packageManager
        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS
        val packages = pm.getInstalledPackages(flags)
        val result = mutableListOf<DeclaredComponent>()

        for (pkg in packages) {
            pkg.activities?.forEach { info ->
                result.add(info.toDeclaredComponent(ComponentType.ACTIVITY, pm))
            }
            pkg.services?.forEach { info ->
                val key = "${info.packageName}/${info.name}"
                val serviceState = when {
                    isDisabled(info, pm) -> ComponentState.DISABLED
                    key in runningSet && runningSet[key] == true -> ComponentState.RUNNING_FOREGROUND
                    key in runningSet -> ComponentState.RUNNING
                    else -> ComponentState.ENABLED
                }
                result.add(
                    DeclaredComponent(
                        packageName = info.packageName,
                        className = info.name,
                        type = ComponentType.SERVICE,
                        state = serviceState,
                    ),
                )
            }
            pkg.receivers?.forEach { info ->
                result.add(info.toDeclaredComponent(ComponentType.BROADCAST_RECEIVER, pm))
            }
            pkg.providers?.forEach { info ->
                result.add(info.toProviderComponent(pm))
            }
        }

        return result
    }

    private fun ComponentInfo.toDeclaredComponent(
        type: ComponentType,
        pm: PackageManager,
    ): DeclaredComponent = DeclaredComponent(
        packageName = packageName,
        className = name,
        type = type,
        state = if (isDisabled(this, pm)) ComponentState.DISABLED else ComponentState.ENABLED,
    )

    private fun ProviderInfo.toProviderComponent(pm: PackageManager): DeclaredComponent =
        DeclaredComponent(
            packageName = packageName,
            className = name,
            type = ComponentType.CONTENT_PROVIDER,
            state = if (isDisabled(this, pm)) ComponentState.DISABLED else ComponentState.ENABLED,
        )

    private fun isDisabled(info: ComponentInfo, pm: PackageManager): Boolean {
        val setting = pm.getComponentEnabledSetting(
            android.content.ComponentName(info.packageName, info.name),
        )
        return setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.toInstalledPackage() = InstalledPackage(
        packageName = packageName,
        versionName = versionName,
        versionCode = versionCode.toLong(),
        isSystemApp = applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0,
    )

    private companion object {
        const val TAG = "SystemMonitor"
        val EMPTY_SNAPSHOT = SystemSnapshot(
            status = SystemStatus(
                isBluetoothLeEnabled = false,
                isBluetoothLeAdvertisingSupported = false,
                isWifiEnabled = false,
                wifiIpAddress = null,
                isUsbHostAvailable = false,
                isAdbEnabled = false,
                isRootAvailable = false,
                isSeLinuxEnforcing = false,
            ),
            packages = emptyList(),
            components = emptyList(),
            usbDevices = emptyList(),
            timestamp = 0L,
        )
    }
}
