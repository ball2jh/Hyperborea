package com.nettarion.hyperborea.broadcast.wifi

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.adapter.AdapterState
import com.nettarion.hyperborea.core.test.TestAppLogger
import com.nettarion.hyperborea.core.test.buildDeviceInfo
import com.nettarion.hyperborea.core.test.buildSystemSnapshot
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class WifiAdapterTest {

    private lateinit var adapter: WifiAdapter
    private lateinit var nsdRegistrar: NsdRegistrar
    private val logger = TestAppLogger()

    @Before
    fun setUp() {
        nsdRegistrar = mockk(relaxed = true)
        adapter = WifiAdapter(nsdRegistrar, logger, deviceName = { "Test Bike" })
    }

    @After
    fun tearDown() = runTest {
        adapter.stop()
    }

    // --- Prerequisites ---

    @Test
    fun `prerequisites is empty`() {
        assertThat(adapter.prerequisites).isEmpty()
    }

    // --- canOperate ---

    @Test
    fun `canOperate returns true when WiFi is enabled`() {
        val snapshot = buildSystemSnapshot(isWifiEnabled = true)
        assertThat(adapter.canOperate(snapshot)).isTrue()
    }

    @Test
    fun `canOperate returns false when WiFi is disabled`() {
        val snapshot = buildSystemSnapshot(isWifiEnabled = false)
        assertThat(adapter.canOperate(snapshot)).isFalse()
    }

    // --- State transitions ---

    @Test
    fun `initial state is Inactive`() {
        assertThat(adapter.state.value).isEqualTo(AdapterState.Inactive)
    }

    @Test
    fun `start transitions to Active`() = runTest {
        adapter.start(emptyFlow(), buildDeviceInfo())
        assertThat(adapter.state.value).isEqualTo(AdapterState.Active)
    }

    @Test
    fun `stop transitions to Inactive`() = runTest {
        adapter.start(emptyFlow(), buildDeviceInfo())
        adapter.stop()
        assertThat(adapter.state.value).isEqualTo(AdapterState.Inactive)
    }

    @Test
    fun `double start is a no-op`() = runTest {
        adapter.start(emptyFlow(), buildDeviceInfo())
        assertThat(adapter.state.value).isEqualTo(AdapterState.Active)
        adapter.start(emptyFlow(), buildDeviceInfo())
        assertThat(adapter.state.value).isEqualTo(AdapterState.Active)
    }

    @Test
    fun `stop when Inactive is a no-op`() = runTest {
        assertThat(adapter.state.value).isEqualTo(AdapterState.Inactive)
        adapter.stop()
        assertThat(adapter.state.value).isEqualTo(AdapterState.Inactive)
    }

    // --- Connected clients ---

    @Test
    fun `connectedClients is empty initially`() {
        assertThat(adapter.connectedClients.value).isEmpty()
    }

    @Test
    fun `connectedClients resets to empty on stop`() = runTest {
        adapter.start(emptyFlow(), buildDeviceInfo())
        adapter.stop()
        assertThat(adapter.connectedClients.value).isEmpty()
    }

    // --- mDNS ---

    @Test
    fun `start registers mDNS`() = runTest {
        adapter.start(emptyFlow(), buildDeviceInfo())
        verify { nsdRegistrar.register(WifiServer.PORT, "Test Bike") }
    }

    @Test
    fun `stop unregisters mDNS`() = runTest {
        adapter.start(emptyFlow(), buildDeviceInfo())
        adapter.stop()
        verify { nsdRegistrar.unregister() }
    }
}
