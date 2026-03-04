package com.nettarion.hyperborea.hardware.fitpro

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.AdapterState
import com.nettarion.hyperborea.core.DeviceCommand
import com.nettarion.hyperborea.core.DeviceType
import com.nettarion.hyperborea.core.FulfillResult
import com.nettarion.hyperborea.core.Metric
import com.nettarion.hyperborea.core.SystemController
import com.nettarion.hyperborea.core.UsbDeviceInfo
import com.nettarion.hyperborea.core.test.buildSystemSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class FitProAdapterTest {

    private lateinit var adapter: FitProAdapter

    @Before
    fun setUp() {
        adapter = FitProAdapter()
    }

    // --- Prerequisites ---

    @Test
    fun `prerequisite is met when FitPro USB device is present`() {
        val snapshot = buildSystemSnapshot(
            usbDevices = listOf(fitProUsbDevice()),
        )
        val prereq = adapter.prerequisites.single()
        assertThat(prereq.isMet(snapshot)).isTrue()
    }

    @Test
    fun `prerequisite is NOT met when no USB devices`() {
        val snapshot = buildSystemSnapshot(usbDevices = emptyList())
        val prereq = adapter.prerequisites.single()
        assertThat(prereq.isMet(snapshot)).isFalse()
    }

    @Test
    fun `prerequisite is NOT met when different USB device present`() {
        val snapshot = buildSystemSnapshot(
            usbDevices = listOf(
                UsbDeviceInfo(vendorId = 0x1234, productId = 5, null, null, null),
            ),
        )
        val prereq = adapter.prerequisites.single()
        assertThat(prereq.isMet(snapshot)).isFalse()
    }

    @Test
    fun `prerequisite has fulfill defined`() {
        val prereq = adapter.prerequisites.single()
        assertThat(prereq.fulfill).isNotNull()
    }

    @Test
    fun `prerequisite fulfill calls grantUsbPermission`() = runTest {
        var capturedPackage: String? = null
        val controller = stubController(
            onGrantUsbPermission = { pkg ->
                capturedPackage = pkg
                true
            },
        )
        val prereq = adapter.prerequisites.single()
        prereq.fulfill!!.invoke(controller)
        assertThat(capturedPackage).isEqualTo("com.nettarion.hyperborea")
    }

    @Test
    fun `prerequisite fulfill returns Success when grantUsbPermission succeeds`() = runTest {
        val controller = stubController(onGrantUsbPermission = { true })
        val result = adapter.prerequisites.single().fulfill!!.invoke(controller)
        assertThat(result).isEqualTo(FulfillResult.Success)
    }

    @Test
    fun `prerequisite fulfill returns Failed when grantUsbPermission fails`() = runTest {
        val controller = stubController(onGrantUsbPermission = { false })
        val result = adapter.prerequisites.single().fulfill!!.invoke(controller)
        assertThat(result).isInstanceOf(FulfillResult.Failed::class.java)
        assertThat((result as FulfillResult.Failed).reason).contains("USB permission not granted")
    }

    @Test
    fun `prerequisite id is usb-device-accessible`() {
        val prereq = adapter.prerequisites.single()
        assertThat(prereq.id).isEqualTo("usb-device-accessible")
    }

    // --- canOperate ---

    @Test
    fun `canOperate returns true when USB host is available`() {
        val snapshot = buildSystemSnapshot(isUsbHostAvailable = true)
        assertThat(adapter.canOperate(snapshot)).isTrue()
    }

    @Test
    fun `canOperate returns false when USB host is not available`() {
        val snapshot = buildSystemSnapshot(isUsbHostAvailable = false)
        assertThat(adapter.canOperate(snapshot)).isFalse()
    }

    // --- Device info ---

    @Test
    fun `device name is NordicTrack S22i`() {
        assertThat(adapter.deviceInfo.name).isEqualTo("NordicTrack S22i")
    }

    @Test
    fun `device type is BIKE`() {
        assertThat(adapter.deviceInfo.type).isEqualTo(DeviceType.BIKE)
    }

    @Test
    fun `supported metrics include expected values`() {
        assertThat(adapter.deviceInfo.supportedMetrics).containsExactly(
            Metric.POWER, Metric.CADENCE, Metric.SPEED,
            Metric.RESISTANCE, Metric.INCLINE,
            Metric.DISTANCE, Metric.CALORIES,
        )
    }

    @Test
    fun `supported metrics do NOT include HEART_RATE`() {
        assertThat(adapter.deviceInfo.supportedMetrics).doesNotContain(Metric.HEART_RATE)
    }

    // --- State transitions ---

    @Test
    fun `initial state is Inactive`() {
        assertThat(adapter.state.value).isEqualTo(AdapterState.Inactive)
    }

    @Test
    fun `connect transitions through Activating to Active`() = runTest {
        adapter.connect()
        assertThat(adapter.state.value).isEqualTo(AdapterState.Active)
    }

    @Test
    fun `disconnect transitions to Inactive`() = runTest {
        adapter.connect()
        adapter.disconnect()
        assertThat(adapter.state.value).isEqualTo(AdapterState.Inactive)
    }

    @Test
    fun `double connect is a no-op`() = runTest {
        adapter.connect()
        assertThat(adapter.state.value).isEqualTo(AdapterState.Active)
        adapter.connect()
        assertThat(adapter.state.value).isEqualTo(AdapterState.Active)
    }

    @Test
    fun `disconnect when Inactive is a no-op`() = runTest {
        assertThat(adapter.state.value).isEqualTo(AdapterState.Inactive)
        adapter.disconnect()
        assertThat(adapter.state.value).isEqualTo(AdapterState.Inactive)
    }

    // --- Exercise data ---

    @Test
    fun `exerciseData is null initially`() {
        assertThat(adapter.exerciseData.value).isNull()
    }

    @Test
    fun `exerciseData is cleared on disconnect`() = runTest {
        adapter.connect()
        adapter.disconnect()
        assertThat(adapter.exerciseData.value).isNull()
    }

    // --- Commands ---

    @Test
    fun `sendCommand does not throw for SetResistance`() = runTest {
        adapter.sendCommand(DeviceCommand.SetResistance(level = 10))
    }

    @Test
    fun `sendCommand does not throw for SetIncline`() = runTest {
        adapter.sendCommand(DeviceCommand.SetIncline(percent = 5.0f))
    }

    // --- Helpers ---

    private fun fitProUsbDevice() = UsbDeviceInfo(
        vendorId = 0x213C,
        productId = 2,
        deviceName = "/dev/bus/usb/001/002",
        manufacturerName = "ICON Fitness",
        productName = "FitPro",
    )

    private fun stubController(
        onGrantUsbPermission: suspend (String) -> Boolean = { false },
    ) = object : SystemController {
        override suspend fun stopService(packageName: String, className: String) = false
        override suspend fun forceStopPackage(packageName: String) = false
        override suspend fun disablePackage(packageName: String) = false
        override suspend fun enablePackage(packageName: String) = false
        override suspend fun uninstallPackage(packageName: String) = false
        override suspend fun disableComponent(packageName: String, className: String) = false
        override suspend fun enableComponent(packageName: String, className: String) = false
        override suspend fun grantUsbPermission(packageName: String) = onGrantUsbPermission(packageName)
        override suspend fun revokeUsbPermissions(packageName: String) = false
    }
}
