package com.nettarion.hyperborea.core.test

import com.nettarion.hyperborea.core.DeclaredComponent
import com.nettarion.hyperborea.core.DeviceInfo
import com.nettarion.hyperborea.core.DeviceType
import com.nettarion.hyperborea.core.ExerciseData
import com.nettarion.hyperborea.core.Metric
import com.nettarion.hyperborea.core.InstalledPackage
import com.nettarion.hyperborea.core.LogLevel
import com.nettarion.hyperborea.core.SystemLogEntry
import com.nettarion.hyperborea.core.SystemLogSource
import com.nettarion.hyperborea.core.SystemSnapshot
import com.nettarion.hyperborea.core.SystemStatus
import com.nettarion.hyperborea.core.UsbDeviceInfo

fun buildSystemSnapshot(
    isBluetoothLeEnabled: Boolean = false,
    isBluetoothLeAdvertisingSupported: Boolean = false,
    isWifiEnabled: Boolean = false,
    wifiIpAddress: String? = null,
    isUsbHostAvailable: Boolean = false,
    isAdbEnabled: Boolean = false,
    isRootAvailable: Boolean = false,
    isSeLinuxEnforcing: Boolean = false,
    packages: List<InstalledPackage> = emptyList(),
    components: List<DeclaredComponent> = emptyList(),
    usbDevices: List<UsbDeviceInfo> = emptyList(),
    timestamp: Long = 0L,
): SystemSnapshot = SystemSnapshot(
    status = SystemStatus(
        isBluetoothLeEnabled = isBluetoothLeEnabled,
        isBluetoothLeAdvertisingSupported = isBluetoothLeAdvertisingSupported,
        isWifiEnabled = isWifiEnabled,
        wifiIpAddress = wifiIpAddress,
        isUsbHostAvailable = isUsbHostAvailable,
        isAdbEnabled = isAdbEnabled,
        isRootAvailable = isRootAvailable,
        isSeLinuxEnforcing = isSeLinuxEnforcing,
    ),
    packages = packages,
    components = components,
    usbDevices = usbDevices,
    timestamp = timestamp,
)

fun buildDeviceInfo(
    name: String = "Test Device",
    type: DeviceType = DeviceType.BIKE,
    supportedMetrics: Set<Metric> = emptySet(),
    maxResistance: Int = 24,
    minResistance: Int = 1,
    minIncline: Float = -6f,
    maxIncline: Float = 40f,
    maxPower: Int = 2000,
): DeviceInfo = DeviceInfo(
    name = name,
    type = type,
    supportedMetrics = supportedMetrics,
    maxResistance = maxResistance,
    minResistance = minResistance,
    minIncline = minIncline,
    maxIncline = maxIncline,
    maxPower = maxPower,
)

fun buildExerciseData(
    power: Int? = null,
    cadence: Int? = null,
    speed: Float? = null,
    resistance: Int? = null,
    incline: Float? = null,
    heartRate: Int? = null,
    distance: Float? = null,
    calories: Int? = null,
    elapsedTime: Long = 0L,
): ExerciseData = ExerciseData(
    power = power,
    cadence = cadence,
    speed = speed,
    resistance = resistance,
    incline = incline,
    heartRate = heartRate,
    distance = distance,
    calories = calories,
    elapsedTime = elapsedTime,
)

fun buildSystemLogEntry(
    timestamp: Long = 0L,
    level: LogLevel = LogLevel.DEBUG,
    tag: String = "TestTag",
    message: String = "test message",
    pid: Int = 1000,
    tid: Int = 1001,
    source: SystemLogSource = SystemLogSource.LOGCAT,
): SystemLogEntry = SystemLogEntry(
    timestamp = timestamp,
    level = level,
    tag = tag,
    message = message,
    pid = pid,
    tid = tid,
    source = source,
)
