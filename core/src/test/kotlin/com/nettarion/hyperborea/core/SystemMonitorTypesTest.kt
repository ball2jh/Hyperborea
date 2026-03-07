package com.nettarion.hyperborea.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SystemMonitorTypesTest {

    @Test
    fun `SystemStatus data class equality`() {
        val a = SystemStatus(
            isBluetoothLeEnabled = true,
            isBluetoothLeAdvertisingSupported = false,
            isWifiEnabled = false,
            wifiIpAddress = null,
            isUsbHostAvailable = true,
            isAdbEnabled = false,
            isRootAvailable = false,
            isSeLinuxEnforcing = false,
        )
        val b = SystemStatus(
            isBluetoothLeEnabled = true,
            isBluetoothLeAdvertisingSupported = false,
            isWifiEnabled = false,
            wifiIpAddress = null,
            isUsbHostAvailable = true,
            isAdbEnabled = false,
            isRootAvailable = false,
            isSeLinuxEnforcing = false,
        )
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `SystemStatus inequality on different fields`() {
        val base = SystemStatus(
            isBluetoothLeEnabled = true,
            isBluetoothLeAdvertisingSupported = false,
            isWifiEnabled = false,
            wifiIpAddress = null,
            isUsbHostAvailable = true,
            isAdbEnabled = false,
            isRootAvailable = false,
            isSeLinuxEnforcing = false,
        )
        assertThat(base).isNotEqualTo(base.copy(isWifiEnabled = true))
    }

    @Test
    fun `InstalledPackage data class equality`() {
        val a = InstalledPackage("com.example", "1.0", 1L, false)
        val b = InstalledPackage("com.example", "1.0", 1L, false)
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `InstalledPackage with null versionName`() {
        val pkg = InstalledPackage("com.example", null, 1L, true)
        assertThat(pkg.versionName).isNull()
        assertThat(pkg.isSystemApp).isTrue()
    }

    @Test
    fun `ComponentType enum entries`() {
        assertThat(ComponentType.entries).containsExactly(
            ComponentType.ACTIVITY,
            ComponentType.SERVICE,
            ComponentType.BROADCAST_RECEIVER,
            ComponentType.CONTENT_PROVIDER,
        ).inOrder()
    }

    @Test
    fun `ComponentType valueOf round-trip`() {
        for (type in ComponentType.entries) {
            assertThat(type).isEqualTo(ComponentType.valueOf(type.name))
        }
    }

    @Test
    fun `ComponentState enum entries`() {
        assertThat(ComponentState.entries).containsExactly(
            ComponentState.ENABLED,
            ComponentState.RUNNING,
            ComponentState.RUNNING_FOREGROUND,
            ComponentState.DISABLED,
        ).inOrder()
    }

    @Test
    fun `ComponentState valueOf round-trip`() {
        for (state in ComponentState.entries) {
            assertThat(state).isEqualTo(ComponentState.valueOf(state.name))
        }
    }

    @Test
    fun `DeclaredComponent data class equality`() {
        val a = DeclaredComponent("com.example", "com.example.MyService", ComponentType.SERVICE, ComponentState.RUNNING)
        val b = DeclaredComponent("com.example", "com.example.MyService", ComponentType.SERVICE, ComponentState.RUNNING)
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `DeclaredComponent inequality on different state`() {
        val a = DeclaredComponent("com.example", "com.example.MyService", ComponentType.SERVICE, ComponentState.RUNNING)
        val b = DeclaredComponent("com.example", "com.example.MyService", ComponentType.SERVICE, ComponentState.ENABLED)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `DeclaredComponent inequality on different type`() {
        val a = DeclaredComponent("com.example", "com.example.Foo", ComponentType.SERVICE, ComponentState.ENABLED)
        val b = DeclaredComponent("com.example", "com.example.Foo", ComponentType.ACTIVITY, ComponentState.ENABLED)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `SystemSnapshot data class equality`() {
        val status = SystemStatus(
            isBluetoothLeEnabled = false,
            isBluetoothLeAdvertisingSupported = false,
            isWifiEnabled = false,
            wifiIpAddress = null,
            isUsbHostAvailable = false,
            isAdbEnabled = false,
            isRootAvailable = false,
            isSeLinuxEnforcing = false,
        )
        val a = SystemSnapshot(status, emptyList(), emptyList(), emptyList(), 100L)
        val b = SystemSnapshot(status, emptyList(), emptyList(), emptyList(), 100L)
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `SystemSnapshot inequality on different timestamp`() {
        val status = SystemStatus(
            isBluetoothLeEnabled = false,
            isBluetoothLeAdvertisingSupported = false,
            isWifiEnabled = false,
            wifiIpAddress = null,
            isUsbHostAvailable = false,
            isAdbEnabled = false,
            isRootAvailable = false,
            isSeLinuxEnforcing = false,
        )
        val a = SystemSnapshot(status, emptyList(), emptyList(), emptyList(), 100L)
        val b = SystemSnapshot(status, emptyList(), emptyList(), emptyList(), 200L)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `SystemSnapshot contains packages and components`() {
        val pkg = InstalledPackage("com.example", "1.0", 1L, false)
        val comp = DeclaredComponent("com.example", "com.example.Svc", ComponentType.SERVICE, ComponentState.ENABLED)
        val snapshot = SystemSnapshot(
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
            packages = listOf(pkg),
            components = listOf(comp),
            usbDevices = emptyList(),
            timestamp = 0L,
        )
        assertThat(snapshot.packages).containsExactly(pkg)
        assertThat(snapshot.components).containsExactly(comp)
    }
}
