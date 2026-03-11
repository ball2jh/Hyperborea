package com.nettarion.hyperborea.hardware.fitpro

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.adapter.AdapterState
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.orchestration.FulfillResult
import com.nettarion.hyperborea.core.system.SystemController
import com.nettarion.hyperborea.core.system.UsbDeviceInfo
import com.nettarion.hyperborea.core.test.buildSystemSnapshot
import com.nettarion.hyperborea.core.test.TestAppLogger
import com.nettarion.hyperborea.hardware.fitpro.session.FakeHidTransport
import com.nettarion.hyperborea.hardware.fitpro.transport.HidTransportFactory
import com.nettarion.hyperborea.hardware.fitpro.transport.HidTransportResult
import com.nettarion.hyperborea.hardware.fitpro.v1.V1Codec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FitProAdapterTest {

    private val transport = FakeHidTransport()
    private val logger = TestAppLogger()

    private fun createAdapter(scope: TestScope, productId: Int = 2): FitProAdapter {
        val testFactory = object : HidTransportFactory {
            override fun create(vendorId: Int, @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE") pid: Int) =
                HidTransportResult(transport, productId)
        }
        return FitProAdapter(testFactory, logger, scope.backgroundScope)
    }

    // --- Prerequisites ---

    @Test
    fun `prerequisite is met when FitPro V1 USB device is present`() {
        val adapter = createAdapter(TestScope())
        val snapshot = buildSystemSnapshot(
            usbDevices = listOf(fitProUsbDevice(productId = 2)),
        )
        val prereq = adapter.prerequisites.single()
        assertThat(prereq.isMet(snapshot)).isTrue()
    }

    @Test
    fun `prerequisite is met when FitPro V2 USB device is present`() {
        val adapter = createAdapter(TestScope())
        val snapshot = buildSystemSnapshot(
            usbDevices = listOf(fitProUsbDevice(productId = 3)),
        )
        val prereq = adapter.prerequisites.single()
        assertThat(prereq.isMet(snapshot)).isTrue()
    }

    @Test
    fun `prerequisite is met when FitPro V2 FTDI USB device is present`() {
        val adapter = createAdapter(TestScope())
        val snapshot = buildSystemSnapshot(
            usbDevices = listOf(fitProUsbDevice(productId = 4)),
        )
        val prereq = adapter.prerequisites.single()
        assertThat(prereq.isMet(snapshot)).isTrue()
    }

    @Test
    fun `prerequisite is NOT met when no USB devices`() {
        val adapter = createAdapter(TestScope())
        val snapshot = buildSystemSnapshot(usbDevices = emptyList())
        val prereq = adapter.prerequisites.single()
        assertThat(prereq.isMet(snapshot)).isFalse()
    }

    @Test
    fun `prerequisite is NOT met when different USB device present`() {
        val adapter = createAdapter(TestScope())
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
        val adapter = createAdapter(TestScope())
        val prereq = adapter.prerequisites.single()
        assertThat(prereq.fulfill).isNotNull()
    }

    @Test
    fun `prerequisite fulfill calls grantUsbPermission`() = runTest {
        val adapter = createAdapter(this)
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
        val adapter = createAdapter(this)
        val controller = stubController(onGrantUsbPermission = { true })
        val result = adapter.prerequisites.single().fulfill!!.invoke(controller)
        assertThat(result).isEqualTo(FulfillResult.Success)
    }

    @Test
    fun `prerequisite fulfill returns Failed when grantUsbPermission fails`() = runTest {
        val adapter = createAdapter(this)
        val controller = stubController(onGrantUsbPermission = { false })
        val result = adapter.prerequisites.single().fulfill!!.invoke(controller)
        assertThat(result).isInstanceOf(FulfillResult.Failed::class.java)
        assertThat((result as FulfillResult.Failed).reason).contains("USB permission not granted")
    }

    @Test
    fun `prerequisite id is usb-device-accessible`() {
        val adapter = createAdapter(TestScope())
        val prereq = adapter.prerequisites.single()
        assertThat(prereq.id).isEqualTo("usb-device-accessible")
    }

    // --- canOperate ---

    @Test
    fun `canOperate returns true when USB host is available`() {
        val adapter = createAdapter(TestScope())
        val snapshot = buildSystemSnapshot(isUsbHostAvailable = true)
        assertThat(adapter.canOperate(snapshot)).isTrue()
    }

    @Test
    fun `canOperate returns false when USB host is not available`() {
        val adapter = createAdapter(TestScope())
        val snapshot = buildSystemSnapshot(isUsbHostAvailable = false)
        assertThat(adapter.canOperate(snapshot)).isFalse()
    }

    // --- Device info ---

    @Test
    fun `deviceInfo is null initially`() {
        val adapter = createAdapter(TestScope())
        assertThat(adapter.deviceInfo.value).isNull()
    }

    @Test
    fun `deviceInfo is null after disconnect`() = runTest {
        val adapter = createAdapter(this)
        adapter.disconnect()
        assertThat(adapter.deviceInfo.value).isNull()
    }

    @Test
    fun `deviceInfo is fallback after V2 connect`() = runTest {
        val adapter = createAdapter(this, productId = 3)
        adapter.connect()
        advanceUntilIdle()

        // V2 identity has null model → fallback
        assertThat(adapter.deviceInfo.value).isNotNull()
        assertThat(adapter.deviceInfo.value!!.name).isEqualTo("FitPro Device")
        assertThat(adapter.deviceInfo.value!!.type).isEqualTo(DeviceType.BIKE)
    }

    // --- State transitions ---

    @Test
    fun `initial state is Inactive`() {
        val adapter = createAdapter(TestScope())
        assertThat(adapter.state.value).isEqualTo(AdapterState.Inactive)
    }

    @Test
    fun `disconnect transitions to Inactive`() = runTest {
        val adapter = createAdapter(this)
        adapter.disconnect()
        assertThat(adapter.state.value).isEqualTo(AdapterState.Inactive)
    }

    @Test
    fun `disconnect when Inactive is a no-op`() = runTest {
        val adapter = createAdapter(this)
        assertThat(adapter.state.value).isEqualTo(AdapterState.Inactive)
        adapter.disconnect()
        assertThat(adapter.state.value).isEqualTo(AdapterState.Inactive)
    }

    // --- Exercise data ---

    @Test
    fun `exerciseData is null initially`() {
        val adapter = createAdapter(TestScope())
        assertThat(adapter.exerciseData.value).isNull()
    }

    @Test
    fun `exerciseData is cleared on disconnect`() = runTest {
        val adapter = createAdapter(this)
        adapter.disconnect()
        assertThat(adapter.exerciseData.value).isNull()
    }

    // --- Device identity ---

    @Test
    fun `deviceIdentity is null initially`() {
        val adapter = createAdapter(TestScope())
        assertThat(adapter.deviceIdentity.value).isNull()
    }

    @Test
    fun `deviceIdentity is cleared on disconnect`() = runTest {
        val adapter = createAdapter(this)
        adapter.disconnect()
        assertThat(adapter.deviceIdentity.value).isNull()
    }

    // --- Commands ---

    @Test
    fun `sendCommand does not throw for SetResistance when no session`() = runTest {
        val adapter = createAdapter(this)
        adapter.sendCommand(DeviceCommand.SetResistance(level = 10))
    }

    @Test
    fun `sendCommand does not throw for SetIncline when no session`() = runTest {
        val adapter = createAdapter(this)
        adapter.sendCommand(DeviceCommand.SetIncline(percent = 5.0f))
    }

    // --- Protocol selection ---

    @Test
    fun `connect with product ID 2 uses V1 session`() = runTest {
        val adapter = createAdapter(this, productId = 2)

        // V1 handshake requires: ConnectAck, DeviceInfoResponse, SystemInfoResponse,
        // VersionInfoResponse, and SecurityResponse (when sw > 75)
        backgroundScope.launch {
            transport.emitIncoming(buildConnectAck())
            transport.emitIncoming(buildDeviceInfoResponse())
            transport.emitIncoming(buildSystemInfoResponse())
            transport.emitIncoming(buildVersionInfoResponse())
            transport.emitIncoming(buildSecurityUnlockedResponse())
        }

        adapter.connect()
        advanceUntilIdle()

        // Should eventually reach Active state
        assertThat(adapter.state.value).isEqualTo(AdapterState.Active)
    }

    @Test
    fun `connect with product ID 3 uses V2 session`() = runTest {
        val adapter = createAdapter(this, productId = 3)
        adapter.connect()
        advanceUntilIdle()

        // V2 should reach Active (no handshake response needed to reach STREAMING)
        assertThat(adapter.state.value).isEqualTo(AdapterState.Active)
    }

    @Test
    fun `connect with product ID 4 uses V2 session`() = runTest {
        val adapter = createAdapter(this, productId = 4)
        adapter.connect()
        advanceUntilIdle()

        assertThat(adapter.state.value).isEqualTo(AdapterState.Active)
    }

    @Test
    fun `deviceInfo reflects S22i after V1 connect with model 2117`() = runTest {
        val adapter = createAdapter(this, productId = 2)

        backgroundScope.launch {
            transport.emitIncoming(buildConnectAck())
            transport.emitIncoming(buildDeviceInfoResponse())
            transport.emitIncoming(buildSystemInfoResponse(model = 2117))
            transport.emitIncoming(buildVersionInfoResponse())
            transport.emitIncoming(buildSecurityUnlockedResponse())
        }

        adapter.connect()
        advanceUntilIdle()

        assertThat(adapter.state.value).isEqualTo(AdapterState.Active)
        assertThat(adapter.deviceInfo.value).isNotNull()
        assertThat(adapter.deviceInfo.value!!.name).isEqualTo("NordicTrack S22i")
        assertThat(adapter.deviceInfo.value!!.maxResistance).isEqualTo(24)
    }

    @Test
    fun `connect sets Error for transport factory failure`() = runTest {
        val failingFactory = object : HidTransportFactory {
            override fun create(vendorId: Int, productId: Int): HidTransportResult {
                throw IllegalStateException("No USB device found")
            }
        }
        val adapter = FitProAdapter(failingFactory, logger, backgroundScope)
        adapter.connect()
        advanceUntilIdle()

        assertThat(adapter.state.value).isInstanceOf(AdapterState.Error::class.java)
    }

    // --- Helpers ---

    private fun fitProUsbDevice(productId: Int = 2) = UsbDeviceInfo(
        vendorId = 0x213C,
        productId = productId,
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

    // --- V1 handshake packet builders ---

    private fun buildConnectAck(): ByteArray {
        val data = byteArrayOf(0x07, 0x04, 0x04)
        return data + V1Codec.checksum(data)
    }

    private fun buildDeviceInfoResponse(): ByteArray {
        // sw=80 (>75, triggers security), hw=3, serial=0x01020304
        val data = byteArrayOf(
            0x02, 0x0F, 0x81.toByte(), 0x02,
            80, 3,
            0x04, 0x03, 0x02, 0x01,
            0, 0,
            1, 0,
        )
        return data + V1Codec.checksum(data)
    }

    private fun buildSystemInfoResponse(model: Int = 100): ByteArray {
        val data = byteArrayOf(
            0x02, 0x10, 0x82.toByte(), 0x02,
            0, 0,
            0,
            (model and 0xFF).toByte(), ((model shr 8) and 0xFF).toByte(), ((model shr 16) and 0xFF).toByte(), ((model shr 24) and 0xFF).toByte(),
            200.toByte(), 0, 0, 0,
        )
        return data + V1Codec.checksum(data)
    }

    private fun buildVersionInfoResponse(): ByteArray {
        val data = byteArrayOf(
            0x02, 0x08, 0x84.toByte(), 0x02,
            10,
            1, 0,
        )
        return data + V1Codec.checksum(data)
    }

    private fun buildSecurityUnlockedResponse(): ByteArray {
        val data = byteArrayOf(
            0x02, 0x06, 0x90.toByte(), 0x02,
            0x01,
        )
        return data + V1Codec.checksum(data)
    }
}
