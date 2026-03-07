package com.nettarion.hyperborea.broadcast.wftnp

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.AdapterState
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.test.buildSystemSnapshot
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class WftnpAdapterTest {

    private lateinit var adapter: WftnpAdapter
    private lateinit var nsdRegistrar: NsdRegistrar
    private val logger = object : AppLogger {
        override fun d(tag: String, message: String) {}
        override fun i(tag: String, message: String) {}
        override fun w(tag: String, message: String) {}
        override fun e(tag: String, message: String, throwable: Throwable?) {}
    }

    @Before
    fun setUp() {
        nsdRegistrar = mockk(relaxed = true)
        adapter = WftnpAdapter(nsdRegistrar, logger, deviceName = { "Test Bike" })
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
        adapter.start(emptyFlow())
        assertThat(adapter.state.value).isEqualTo(AdapterState.Active)
    }

    @Test
    fun `stop transitions to Inactive`() = runTest {
        adapter.start(emptyFlow())
        adapter.stop()
        assertThat(adapter.state.value).isEqualTo(AdapterState.Inactive)
    }

    @Test
    fun `double start is a no-op`() = runTest {
        adapter.start(emptyFlow())
        assertThat(adapter.state.value).isEqualTo(AdapterState.Active)
        adapter.start(emptyFlow())
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
        adapter.start(emptyFlow())
        adapter.stop()
        assertThat(adapter.connectedClients.value).isEmpty()
    }

    // --- mDNS ---

    @Test
    fun `start registers mDNS`() = runTest {
        adapter.start(emptyFlow())
        verify { nsdRegistrar.register(WftnpServer.PORT, "Test Bike") }
    }

    @Test
    fun `stop unregisters mDNS`() = runTest {
        adapter.start(emptyFlow())
        adapter.stop()
        verify { nsdRegistrar.unregister() }
    }
}
