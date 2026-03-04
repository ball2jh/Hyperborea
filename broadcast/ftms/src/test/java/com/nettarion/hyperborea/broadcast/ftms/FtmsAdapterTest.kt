package com.nettarion.hyperborea.broadcast.ftms

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.AdapterState
import com.nettarion.hyperborea.core.test.buildSystemSnapshot
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class FtmsAdapterTest {

    private lateinit var adapter: FtmsAdapter

    @Before
    fun setUp() {
        adapter = FtmsAdapter()
    }

    // --- Prerequisites ---

    @Test
    fun `prerequisites is empty`() {
        assertThat(adapter.prerequisites).isEmpty()
    }

    // --- canOperate ---

    @Test
    fun `canOperate returns true when BLE advertising is supported`() {
        val snapshot = buildSystemSnapshot(
            isBluetoothLeEnabled = true,
            isBluetoothLeAdvertisingSupported = true,
        )
        assertThat(adapter.canOperate(snapshot)).isTrue()
    }

    @Test
    fun `canOperate returns false when BLE is disabled`() {
        val snapshot = buildSystemSnapshot(isBluetoothLeEnabled = false)
        assertThat(adapter.canOperate(snapshot)).isFalse()
    }

    @Test
    fun `canOperate returns false when BLE enabled but advertising not supported`() {
        val snapshot = buildSystemSnapshot(
            isBluetoothLeEnabled = true,
            isBluetoothLeAdvertisingSupported = false,
        )
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
