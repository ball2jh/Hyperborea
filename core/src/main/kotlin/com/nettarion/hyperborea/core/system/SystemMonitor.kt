package com.nettarion.hyperborea.core.system

import kotlinx.coroutines.flow.StateFlow

data class SystemStatus(
    val isBluetoothLeEnabled: Boolean,
    val isBluetoothLeAdvertisingSupported: Boolean,
    val isNetworkConnected: Boolean,
    val isWifiEnabled: Boolean,
    val wifiIpAddress: String?,
    val isUsbHostAvailable: Boolean,
    val isAdbEnabled: Boolean,
    val isRootAvailable: Boolean,
    val isSeLinuxEnforcing: Boolean,
    val isImmersiveModeEnabled: Boolean,
    val isUserSetupComplete: Boolean,
)

data class UsbDeviceInfo(
    val vendorId: Int,
    val productId: Int,
    val deviceName: String?,
    val manufacturerName: String?,
    val productName: String?,
    /** Null when the device reports none OR when we lack permission to read it — see [hasPermission]. */
    val serialNumber: String? = null,
    val hasPermission: Boolean = true,
    val interfaces: List<UsbInterfaceInfo> = emptyList(),
)

data class UsbInterfaceInfo(
    val id: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val endpoints: List<UsbEndpointInfo>,
) {
    fun describe(): String = "if=%d cls=%02x/%02x/%02x eps=[%s]".format(
        id, interfaceClass, interfaceSubclass, interfaceProtocol,
        endpoints.joinToString(", ") { it.describe() },
    )
}

data class UsbEndpointInfo(
    // Full bEndpointAddress — bit 7 is the direction (1 = IN)
    val address: Int,
    // bmAttributes transfer type: 0=control, 1=isochronous, 2=bulk, 3=interrupt
    val type: Int,
    val maxPacketSize: Int,
) {
    val isInput: Boolean get() = address and 0x80 != 0

    fun describe(): String {
        val direction = if (isInput) "in" else "out"
        val typeName = when (type) {
            0 -> "ctrl"
            1 -> "iso"
            2 -> "bulk"
            3 -> "int"
            else -> "type$type"
        }
        return "0x%02x/%s/%s/%d".format(address, direction, typeName, maxPacketSize)
    }
}

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
