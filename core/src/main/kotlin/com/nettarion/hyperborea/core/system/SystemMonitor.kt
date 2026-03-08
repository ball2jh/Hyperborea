package com.nettarion.hyperborea.core.system

import kotlinx.coroutines.flow.StateFlow

data class SystemStatus(
    val isBluetoothLeEnabled: Boolean,
    val isBluetoothLeAdvertisingSupported: Boolean,
    val isWifiEnabled: Boolean,
    val wifiIpAddress: String?,
    val isUsbHostAvailable: Boolean,
    val isAdbEnabled: Boolean,
    val isRootAvailable: Boolean,
    val isSeLinuxEnforcing: Boolean,
)

data class UsbDeviceInfo(
    val vendorId: Int,
    val productId: Int,
    val deviceName: String?,
    val manufacturerName: String?,
    val productName: String?,
)

data class InstalledPackage(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val isSystemApp: Boolean,
)

enum class ComponentType {
    ACTIVITY,
    SERVICE,
    BROADCAST_RECEIVER,
    CONTENT_PROVIDER,
}

enum class ComponentState {
    ENABLED,
    RUNNING,
    RUNNING_FOREGROUND,
    DISABLED,
}

data class DeclaredComponent(
    val packageName: String,
    val className: String,
    val type: ComponentType,
    val state: ComponentState,
)

data class SystemSnapshot(
    val status: SystemStatus,
    val packages: List<InstalledPackage>,
    val components: List<DeclaredComponent>,
    val usbDevices: List<UsbDeviceInfo>,
    val timestamp: Long,
)

interface SystemMonitor {
    val snapshot: StateFlow<SystemSnapshot>
    suspend fun refresh()
}
