package com.nettarion.hyperborea.broadcast.dircon

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.AdapterState
import com.nettarion.hyperborea.core.test.buildSystemSnapshot
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class DirconAdapterTest {

    private lateinit var adapter: DirconAdapter

    @Before
    fun setUp() {
        adapter = DirconAdapter()
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

    // --- Incoming commands ---

    @Test
    fun `incomingCommands completes without emitting`() = runTest {
        adapter.incomingCommands.test {
            awaitComplete()
        }
    }
}
