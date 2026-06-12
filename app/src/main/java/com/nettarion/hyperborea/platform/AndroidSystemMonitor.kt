package com.nettarion.hyperborea.platform

import android.Manifest
import android.annotation.SuppressLint
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
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.system.ComponentState
import com.nettarion.hyperborea.core.system.ComponentType
import com.nettarion.hyperborea.core.system.DeclaredComponent
import com.nettarion.hyperborea.core.system.InstalledPackage
import com.nettarion.hyperborea.core.system.SystemMonitor
import com.nettarion.hyperborea.core.system.SystemSnapshot
import com.nettarion.hyperborea.core.system.SystemStatus
import com.nettarion.hyperborea.core.system.UsbDeviceInfo
import com.nettarion.hyperborea.core.system.UsbEndpointInfo
import com.nettarion.hyperborea.core.system.UsbInterfaceInfo
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

    // Declared before init: the init block's updateStatus() reads these lazy delegates.
    private val rootAvailable by lazy { checkRootAvailable() }
    private val seLinuxEnforcing by lazy { checkSeLinuxEnforcing() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            logger.d(TAG, "Broadcast received: ${intent.action}")
            updateStatus()
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(receiver, filter)
        updateStatus()
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

        // Bluetooth — BluetoothAdapter.isEnabled and the advertiser query require BLUETOOTH_CONNECT
        // on API 31+; report "unavailable" rather than throw SecurityException if it isn't granted.
        val btAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val btConnectGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val bleEnabled = btConnectGranted &&
            pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) &&
            btAdapter?.isEnabled == true
        val bleAdvertisingSupported = bleEnabled &&
            (btAdapter.isMultipleAdvertisementSupported ||
                btAdapter.bluetoothLeAdvertiser != null)

        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val networkConnected = connectivityManager?.activeNetworkInfo?.isConnected == true

        // WiFi + IP — look WIFI_SERVICE up on the application context (avoids the pre-N leak).
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiEnabled = pm.hasSystemFeature(PackageManager.FEATURE_WIFI) &&
            wifiManager?.isWifiEnabled == true
        val wifiIp = if (wifiEnabled) {
            val ip = wifiManager.connectionInfo?.ipAddress ?: 0
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

        // Immersive mode
        val immersiveModeEnabled = try {
            Settings.Global.getString(context.contentResolver, "policy_control") == "immersive.full=*"
        } catch (_: Exception) {
            false
        }

        // User setup complete (privileged mode)
        val userSetupComplete = try {
            Settings.Secure.getInt(context.contentResolver, "user_setup_complete", 0) == 1
        } catch (_: Exception) {
            false
        }

        // Root + SELinux — probed once and cached: neither changes while the app is running,
        // and re-probing on every status update spawns a `su` process and re-reads selinuxfs
        // (audit-log spam on every connectivity broadcast).
        val rootAvailable = this.rootAvailable
        val seLinuxEnforcing = this.seLinuxEnforcing

        return SystemStatus(
            isBluetoothLeEnabled = bleEnabled,
            isBluetoothLeAdvertisingSupported = bleAdvertisingSupported,
            isNetworkConnected = networkConnected,
            isWifiEnabled = wifiEnabled,
            wifiIpAddress = wifiIp,
            isUsbHostAvailable = usbHost,
            isAdbEnabled = adbEnabled,
            isRootAvailable = rootAvailable,
            isSeLinuxEnforcing = seLinuxEnforcing,
            isImmersiveModeEnabled = immersiveModeEnabled,
            isUserSetupComplete = userSetupComplete,
        )
    }

    private fun formatIpAddress(ip: Int): String =
        "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"

    private fun checkRootAvailable(): Boolean {
        val process = try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        } catch (_: Exception) {
            return false
        }
        return try {
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        } finally {
            try { process.inputStream.close() } catch (_: Exception) {}
            try { process.errorStream.close() } catch (_: Exception) {}
            try { process.outputStream.close() } catch (_: Exception) {}
            process.destroy()
        }
    }

    private fun checkSeLinuxEnforcing(): Boolean {
        return try {
            val file = File("/sys/fs/selinux/enforce")
            if (file.exists()) file.readText().trim() == "1" else false
        } catch (_: Exception) {
            // Fallback: try getenforce command
            val process = try {
                Runtime.getRuntime().exec("getenforce")
            } catch (_: Exception) {
                return false
            }
            try {
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                output.equals("Enforcing", ignoreCase = true)
            } catch (_: Exception) {
                false
            } finally {
                try { process.inputStream.close() } catch (_: Exception) {}
                try { process.errorStream.close() } catch (_: Exception) {}
                try { process.outputStream.close() } catch (_: Exception) {}
                process.destroy()
            }
        }
    }

    private fun captureUsbDevices(): List<UsbDeviceInfo> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return emptyList()
        return usbManager.deviceList?.values?.map { dev ->
            UsbDeviceInfo(
                vendorId = dev.vendorId,
                productId = dev.productId,
                deviceName = dev.deviceName,
                manufacturerName = dev.manufacturerName,
                productName = dev.productName,
                hasPermission = usbManager.hasPermission(dev),
                interfaces = (0 until dev.interfaceCount).map { i ->
                    val iface = dev.getInterface(i)
                    UsbInterfaceInfo(
                        id = iface.id,
                        interfaceClass = iface.interfaceClass,
                        interfaceSubclass = iface.interfaceSubclass,
                        interfaceProtocol = iface.interfaceProtocol,
                        endpoints = (0 until iface.endpointCount).map { e ->
                            val ep = iface.getEndpoint(e)
                            UsbEndpointInfo(
                                address = ep.address,
                                type = ep.type,
                                maxPacketSize = ep.maxPacketSize,
                            )
                        },
                    )
                },
            )
        } ?: emptyList()
    }

    // QueryPermissionsNeeded: this is a diagnostic snapshot — on API 30+ it sees this app, system
    // packages, and the com.ifit.* packages listed in the manifest <queries> block, which is
    // exactly what the ecosystem checks need; full visibility (QUERY_ALL_PACKAGES) is neither
    // wanted nor allowed here.
    @SuppressLint("QueryPermissionsNeeded")
    @Suppress("DEPRECATION")
    private fun capturePackages(): List<InstalledPackage> {
        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS
        val packages = context.packageManager.getInstalledPackages(flags)
        return packages.map { it.toInstalledPackage() }
    }

    @SuppressLint("QueryPermissionsNeeded") // see capturePackages()
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
                isNetworkConnected = false,
                isWifiEnabled = false,
                wifiIpAddress = null,
                isUsbHostAvailable = false,
                isAdbEnabled = false,
                isRootAvailable = false,
                isSeLinuxEnforcing = false,
                isImmersiveModeEnabled = false,
                isUserSetupComplete = false,
            ),
            packages = emptyList(),
            components = emptyList(),
            usbDevices = emptyList(),
            timestamp = 0L,
        )
    }
}
