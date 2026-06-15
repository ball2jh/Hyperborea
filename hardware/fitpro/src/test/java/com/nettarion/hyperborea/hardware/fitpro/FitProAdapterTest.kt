package com.nettarion.hyperborea.hardware.fitpro

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.adapter.AdapterState
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.model.Metric
import com.nettarion.hyperborea.core.orchestration.FulfillResult
import com.nettarion.hyperborea.core.profile.DeviceConfigRepository
import com.nettarion.hyperborea.core.system.SystemController
import com.nettarion.hyperborea.core.system.UsbDeviceInfo
import com.nettarion.hyperborea.core.test.buildSystemSnapshot
import com.nettarion.hyperborea.core.test.TestAppLogger
import com.nettarion.hyperborea.hardware.fitpro.session.FakeHidTransport
import com.nettarion.hyperborea.hardware.fitpro.transport.HidTransportFactory
import com.nettarion.hyperborea.hardware.fitpro.transport.HidTransportResult
import com.nettarion.hyperborea.hardware.fitpro.v1.V1Codec
import com.nettarion.hyperborea.hardware.fitpro.v2.V2FeatureId
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    private val fakeDeviceConfigRepo = FakeDeviceConfigRepository()

    private fun createAdapter(scope: TestScope, productId: Int = 2): FitProAdapter {
        val testFactory = object : HidTransportFactory {
            override fun create(vendorId: Int, @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE") pid: Int) =
                HidTransportResult(transport, productId)
        }
        return FitProAdapter(testFactory, logger, scope.backgroundScope, fakeDeviceConfigRepo)
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
    fun `prerequisite is NOT met when device present but no permission`() {
        val adapter = createAdapter(TestScope())
        val snapshot = buildSystemSnapshot(
            usbDevices = listOf(fitProUsbDevice(productId = 2).copy(hasPermission = false)),
        )
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
    fun `prerequisite fulfill calls requestUsbPermission`() = runTest {
        val adapter = createAdapter(this)
        var called = false
        val controller = stubController(
            onRequestUsbPermission = {
                called = true
                true
            },
        )
        val prereq = adapter.prerequisites.single()
        prereq.fulfill!!.invoke(controller)
        assertThat(called).isTrue()
    }

    @Test
    fun `prerequisite fulfill returns Success when requestUsbPermission succeeds`() = runTest {
        val adapter = createAdapter(this)
        val controller = stubController(onRequestUsbPermission = { true })
        val result = adapter.prerequisites.single().fulfill!!.invoke(controller)
        assertThat(result).isEqualTo(FulfillResult.Success)
    }

    @Test
    fun `prerequisite fulfill returns Failed when USB permission is not granted`() = runTest {
        val adapter = createAdapter(this)
        val controller = stubController(onRequestUsbPermission = { false })
        val result = adapter.prerequisites.single().fulfill!!.invoke(controller)
        assertThat(result).isInstanceOf(FulfillResult.Failed::class.java)
        assertThat((result as FulfillResult.Failed).reason).contains("not granted")
    }

    @Test
    fun `prerequisite allows extra time for the USB permission dialog`() {
        val adapter = createAdapter(TestScope())
        val timeout = adapter.prerequisites.single().fulfillTimeoutMs
        assertThat(timeout).isNotNull()
        // Has to outlast a user wandering back to the bike — well past the
        // 10 s the orchestrator gives the pm/am-call prerequisites.
        assertThat(timeout!!).isAtLeast(60_000L)
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

        // V2 identity has null model → fallback, keyed by the synthetic product-id config key
        assertThat(adapter.deviceInfo.value).isNotNull()
        assertThat(adapter.deviceInfo.value!!.name).isEqualTo("FitPro Device")
        assertThat(adapter.deviceInfo.value!!.type).isEqualTo(DeviceType.BIKE)
        assertThat(adapter.deviceInfo.value!!.configKey).isEqualTo(-3)
    }

    @Test
    fun `V2 custom config saved under the synthetic product key is applied on connect`() = runTest {
        // Model-less console: the config screen saves under -productId; connect must apply it —
        // including the user's chosen type winning over session detection.
        val customInfo = DeviceInfo(
            name = "My Treadmill",
            type = DeviceType.TREADMILL,
            supportedMetrics = setOf(Metric.SPEED, Metric.INCLINE),
            maxResistance = 0, minResistance = 0,
            minIncline = 0f, maxIncline = 12f,
            maxPower = 1200, minPower = 0, powerStep = 1,
            resistanceStep = 1.0f, inclineStep = 0.5f,
            speedStep = 0.5f, maxSpeed = 20f,
        )
        fakeDeviceConfigRepo.configs[-3] = customInfo

        val adapter = createAdapter(this, productId = 3)
        adapter.connect()
        advanceUntilIdle()

        assertThat(adapter.deviceInfo.value).isNotNull()
        assertThat(adapter.deviceInfo.value!!.name).isEqualTo("My Treadmill")
        assertThat(adapter.deviceInfo.value!!.type).isEqualTo(DeviceType.TREADMILL)
        assertThat(adapter.deviceInfo.value!!.maxSpeed).isEqualTo(20f)
        assertThat(adapter.deviceInfo.value!!.configKey).isEqualTo(-3)
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

        // V1 handshake: DeviceInfo → Connect → SupportedCommands → SystemInfo → VersionInfo → Security → Capabilities
        backgroundScope.launch {
            transport.emitIncoming(buildDeviceInfoResponse())
            transport.emitIncoming(buildSupportedCommandsResponse())
            transport.emitIncoming(buildSystemInfoResponse())
            transport.emitIncoming(buildVersionInfoResponse())
            transport.emitIncoming(buildSecurityUnlockedResponse())
            transport.emitIncoming(buildCapabilityResponse())
            respondToConsoleStartup()
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
    fun `deviceInfo reflects fallback after V1 connect`() = runTest {
        val adapter = createAdapter(this, productId = 2)

        backgroundScope.launch {
            transport.emitIncoming(buildDeviceInfoResponse())
            transport.emitIncoming(buildSupportedCommandsResponse())
            transport.emitIncoming(buildSystemInfoResponse(model = 2117))
            transport.emitIncoming(buildVersionInfoResponse())
            transport.emitIncoming(buildSecurityUnlockedResponse())
            transport.emitIncoming(buildCapabilityResponse())
            respondToConsoleStartup()
        }

        adapter.connect()
        advanceUntilIdle()

        assertThat(adapter.state.value).isEqualTo(AdapterState.Active)
        assertThat(adapter.deviceInfo.value).isNotNull()
        assertThat(adapter.deviceInfo.value!!.name).isEqualTo("FitPro Device")
        assertThat(adapter.deviceInfo.value!!.maxResistance).isEqualTo(24)
    }

    @Test
    fun `connect sets Error for transport factory failure`() = runTest {
        val failingFactory = object : HidTransportFactory {
            override fun create(vendorId: Int, productId: Int): HidTransportResult {
                throw IllegalStateException("No USB device found")
            }
        }
        val adapter = FitProAdapter(failingFactory, logger, backgroundScope, fakeDeviceConfigRepo)
        adapter.connect()
        advanceUntilIdle()

        assertThat(adapter.state.value).isInstanceOf(AdapterState.Error::class.java)
    }

    // --- Device config repository ---

    @Test
    fun `user config takes priority over DeviceDatabase after V1 connect`() = runTest {
        val customInfo = DeviceInfo(
            name = "My Custom Bike",
            type = DeviceType.BIKE,
            supportedMetrics = setOf(Metric.POWER, Metric.CADENCE),
            maxResistance = 30, minResistance = 5,
            minIncline = -5f, maxIncline = 15f,
            maxPower = 1500, minPower = 50, powerStep = 5,
            resistanceStep = 2.0f, inclineStep = 1.0f,
            speedStep = 1.0f, maxSpeed = 50f,
        )
        fakeDeviceConfigRepo.configs[2117] = customInfo

        val adapter = createAdapter(this, productId = 2)
        backgroundScope.launch {
            transport.emitIncoming(buildDeviceInfoResponse())
            transport.emitIncoming(buildSupportedCommandsResponse())
            transport.emitIncoming(buildSystemInfoResponse(model = 2117))
            transport.emitIncoming(buildVersionInfoResponse())
            transport.emitIncoming(buildSecurityUnlockedResponse())
            transport.emitIncoming(buildCapabilityResponse())
            respondToConsoleStartup()
        }

        adapter.connect()
        advanceUntilIdle()

        assertThat(adapter.deviceInfo.value).isNotNull()
        assertThat(adapter.deviceInfo.value!!.name).isEqualTo("My Custom Bike")
        assertThat(adapter.deviceInfo.value!!.maxResistance).isEqualTo(30)
    }

    @Test
    fun `falls back to DeviceDatabase when no user config`() = runTest {
        // fakeDeviceConfigRepo is empty by default
        val adapter = createAdapter(this, productId = 2)
        backgroundScope.launch {
            transport.emitIncoming(buildDeviceInfoResponse())
            transport.emitIncoming(buildSupportedCommandsResponse())
            transport.emitIncoming(buildSystemInfoResponse(model = 2117))
            transport.emitIncoming(buildVersionInfoResponse())
            transport.emitIncoming(buildSecurityUnlockedResponse())
            transport.emitIncoming(buildCapabilityResponse())
            respondToConsoleStartup()
        }

        adapter.connect()
        advanceUntilIdle()

        assertThat(adapter.deviceInfo.value).isNotNull()
        assertThat(adapter.deviceInfo.value!!.name).isEqualTo("FitPro Device")
        assertThat(adapter.deviceInfo.value!!.maxResistance).isEqualTo(24)
    }

    @Test
    fun `refreshDeviceInfo re-resolves with updated config`() = runTest {
        val adapter = createAdapter(this, productId = 2)
        backgroundScope.launch {
            transport.emitIncoming(buildDeviceInfoResponse())
            transport.emitIncoming(buildSupportedCommandsResponse())
            transport.emitIncoming(buildSystemInfoResponse(model = 2117))
            transport.emitIncoming(buildVersionInfoResponse())
            transport.emitIncoming(buildSecurityUnlockedResponse())
            transport.emitIncoming(buildCapabilityResponse())
            respondToConsoleStartup()
        }

        adapter.connect()
        advanceUntilIdle()

        // Initially uses DeviceDatabase fallback
        assertThat(adapter.deviceInfo.value!!.name).isEqualTo("FitPro Device")

        // Save a custom config and refresh
        val customInfo = adapter.deviceInfo.value!!.copy(name = "Renamed Bike", maxResistance = 32)
        fakeDeviceConfigRepo.configs[2117] = customInfo
        adapter.refreshDeviceInfo()

        assertThat(adapter.deviceInfo.value!!.name).isEqualTo("Renamed Bike")
        assertThat(adapter.deviceInfo.value!!.maxResistance).isEqualTo(32)
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
        onRequestUsbPermission: suspend () -> Boolean = { false },
    ) = object : SystemController {
        override suspend fun requestUsbPermission() = onRequestUsbPermission()
    }

    // --- V1 handshake packet builders ---

    private fun buildSupportedCommandsResponse(): ByteArray {
        // device=8, len, cmd=0x88, status=0x02, SystemInfo/VersionInfo/VerifySecurity opcodes, checksum
        val data = byteArrayOf(0x08, 0x08, 0x88.toByte(), 0x02, 0x82.toByte(), 0x84.toByte(), 0x90.toByte())
        return data + V1Codec.checksum(data)
    }

    private fun buildDeviceInfoResponse(): ByteArray {
        // byte0=FITNESS_BIKE(7) — the device's own equipment type; sw=80 (>75, triggers security),
        // hw=3, serial=0x01020304; then [sectionCount=14, 14 mask bytes] declaring REQUIRE_START_REQUESTED (idx 108).
        val mask = ByteArray(14).also { it[13] = 0x10 } // bit 4 of section 13 → bitfield index 108
        val body = byteArrayOf(
            0x07, 0, 0x81.toByte(), 0x02,
            80, 3,
            0x04, 0x03, 0x02, 0x01,
            0, 0,
            14,
        ) + mask
        body[1] = (body.size + 1).toByte()
        return body + V1Codec.checksum(body)
    }

    /** Responses for prepareConsole() + transitionToRunning() that follow the V1 handshake in start(). */
    private suspend fun respondToConsoleStartup() {
        // prepareConsole: REQUIRE_START_REQUESTED write — minimal ReadWriteData ack (status DONE).
        val ack = byteArrayOf(0x07, 0x05, 0x02, 0x02)
        transport.emitIncoming(ack + V1Codec.checksum(ack))
        // transitionToRunning: WARM_UP(10) confirmed, then RUNNING(2) confirmed.
        for (mode in intArrayOf(10, 2)) {
            val resp = byteArrayOf(0x07, 0x06, 0x02, 0x02, mode.toByte())
            transport.emitIncoming(resp + V1Codec.checksum(resp))
        }
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

    private fun buildCapabilityResponse(maxResistance: Int = 0): ByteArray {
        // Capability fields sorted by fieldIndex:
        // MAX_GRADE(27,2), MIN_GRADE(28,2), MAX_KPH(30,2), MIN_KPH(31,2),
        // MAX_RESISTANCE_LEVEL(42,1), MOTOR_TOTAL_DISTANCE(69,4), TOTAL_TIME(70,4)
        // Total = 17 bytes
        val fieldData = ByteArray(17)
        // MAX_RESISTANCE_LEVEL at offset 8 (after 2+2+2+2)
        fieldData[8] = maxResistance.toByte()
        val totalLen = 4 + fieldData.size + 1
        val header = byteArrayOf(0x08, totalLen.toByte(), 0x02, 0x02)
        val withoutChecksum = header + fieldData
        return withoutChecksum + V1Codec.checksum(withoutChecksum)
    }

    @Test
    fun `capabilities merge includes maxResistance from MCU`() = runTest {
        val adapter = createAdapter(this, productId = 2)
        backgroundScope.launch {
            transport.emitIncoming(buildDeviceInfoResponse())
            transport.emitIncoming(buildSupportedCommandsResponse())
            transport.emitIncoming(buildSystemInfoResponse(model = 2117))
            transport.emitIncoming(buildVersionInfoResponse())
            transport.emitIncoming(buildSecurityUnlockedResponse())
            transport.emitIncoming(buildCapabilityResponse(maxResistance = 30))
            respondToConsoleStartup()
        }

        adapter.connect()
        advanceUntilIdle()

        assertThat(adapter.deviceInfo.value).isNotNull()
        assertThat(adapter.deviceInfo.value!!.maxResistance).isEqualTo(30)
    }

    @Test
    fun `V2 MCU-reported limits overlay onto deviceInfo with a type-derived name`() = runTest {
        val adapter = createAdapter(this, productId = 3)
        // The console declares its features, then reports its type and physical limits as events.
        buildV2SupportedFeatures(
            V2FeatureId.DEVICE_TYPE, V2FeatureId.TARGET_KPH, V2FeatureId.MAX_KPH,
            V2FeatureId.MIN_GRADE_PERCENT, V2FeatureId.MAX_GRADE_PERCENT, V2FeatureId.WORKOUT_STATE,
        ).forEach { transport.emitIncoming(it) }
        transport.emitIncoming(buildV2Event(V2FeatureId.DEVICE_TYPE, 4f)) // treadmill
        transport.emitIncoming(buildV2Event(V2FeatureId.MAX_KPH, 20f))
        transport.emitIncoming(buildV2Event(V2FeatureId.MIN_GRADE_PERCENT, 0f))
        transport.emitIncoming(buildV2Event(V2FeatureId.MAX_GRADE_PERCENT, 12f))

        adapter.connect()
        advanceUntilIdle()

        val info = adapter.deviceInfo.value!!
        assertThat(info.type).isEqualTo(DeviceType.TREADMILL)
        assertThat(info.maxSpeed).isEqualTo(20f)
        assertThat(info.minIncline).isEqualTo(0f)
        assertThat(info.maxIncline).isEqualTo(12f)
        // Uncatalogued device → type-derived name, not a hardcoded model name.
        assertThat(info.name).isEqualTo("FitPro Treadmill")
    }

    @Test
    fun `user config wins over V2 MCU-reported limits`() = runTest {
        fakeDeviceConfigRepo.configs[-3] = DeviceInfo(
            name = "My Treadmill",
            type = DeviceType.TREADMILL,
            supportedMetrics = setOf(Metric.SPEED, Metric.INCLINE),
            maxResistance = 0, minResistance = 0,
            minIncline = 0f, maxIncline = 10f,
            maxPower = 1200, minPower = 0, powerStep = 1,
            resistanceStep = 1.0f, inclineStep = 0.5f,
            speedStep = 0.5f, maxSpeed = 15f,
        )
        val adapter = createAdapter(this, productId = 3)
        // MCU reports a HIGHER max speed than the user's config — the user's value must still win.
        buildV2SupportedFeatures(
            V2FeatureId.DEVICE_TYPE, V2FeatureId.MAX_KPH, V2FeatureId.WORKOUT_STATE,
        ).forEach { transport.emitIncoming(it) }
        transport.emitIncoming(buildV2Event(V2FeatureId.DEVICE_TYPE, 4f))
        transport.emitIncoming(buildV2Event(V2FeatureId.MAX_KPH, 30f))

        adapter.connect()
        advanceUntilIdle()

        assertThat(adapter.deviceInfo.value!!.name).isEqualTo("My Treadmill")
        assertThat(adapter.deviceInfo.value!!.maxSpeed).isEqualTo(15f)
    }

    /** Supported-features frames (one content frame + empty terminator) for a V2 console. */
    private fun buildV2SupportedFeatures(vararg features: V2FeatureId): List<ByteArray> {
        val frame = ByteArray(features.size * 2)
        features.forEachIndexed { i, f -> frame[i * 2] = f.wireLo; frame[i * 2 + 1] = f.wireHi }
        return listOf(
            byteArrayOf(0x02, 0x21, frame.size.toByte(), *frame),
            byteArrayOf(0x02, 0x21, 0), // end-of-list terminator
        )
    }

    private fun buildV2Event(feature: V2FeatureId, value: Float): ByteArray {
        val payload = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .put(feature.wireLo).put(feature.wireHi).putFloat(value).array()
        return byteArrayOf(0x02, 0x25, payload.size.toByte(), *payload)
    }

}

private class FakeDeviceConfigRepository : DeviceConfigRepository {
    val configs = mutableMapOf<Int, DeviceInfo>()

    override suspend fun getConfig(modelNumber: Int): DeviceInfo? = configs[modelNumber]
    override suspend fun saveConfig(modelNumber: Int, config: DeviceInfo) { configs[modelNumber] = config }
    override suspend fun deleteConfig(modelNumber: Int) { configs.remove(modelNumber) }
    override suspend fun hasConfig(modelNumber: Int): Boolean = modelNumber in configs
}
