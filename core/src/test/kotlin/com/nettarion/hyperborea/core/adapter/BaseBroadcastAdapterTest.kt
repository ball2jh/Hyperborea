package com.nettarion.hyperborea.core.adapter

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.model.ClientInfo
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.orchestration.Prerequisite
import com.nettarion.hyperborea.core.system.SystemSnapshot

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.test.buildDeviceInfo
import com.nettarion.hyperborea.core.test.buildExerciseData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BaseBroadcastAdapterTest {

    @Test
    fun `start transitions from Inactive to Active`() = runTest {
        val adapter = TestAdapter()
        val dataSource = MutableSharedFlow<ExerciseData>()
        adapter.start(dataSource, buildDeviceInfo())
        assertThat(adapter.state.value).isEqualTo(AdapterState.Active)
    }

    @Test
    fun `start when already Active is no-op`() = runTest {
        val adapter = TestAdapter()
        val dataSource = MutableSharedFlow<ExerciseData>()
        adapter.start(dataSource, buildDeviceInfo())
        adapter.onStartCallCount = 0
        adapter.start(dataSource, buildDeviceInfo())
        assertThat(adapter.onStartCallCount).isEqualTo(0)
    }

    @Test
    fun `stop transitions from Active to Inactive`() = runTest {
        val adapter = TestAdapter()
        val dataSource = MutableSharedFlow<ExerciseData>()
        adapter.start(dataSource, buildDeviceInfo())
        adapter.stop()
        assertThat(adapter.state.value).isEqualTo(AdapterState.Inactive)
    }

    @Test
    fun `stop when Inactive is no-op`() = runTest {
        val adapter = TestAdapter()
        adapter.stop()
        assertThat(adapter.onStopCalled).isFalse()
    }

    @Test
    fun `stop calls onStop`() = runTest {
        val adapter = TestAdapter()
        val dataSource = MutableSharedFlow<ExerciseData>()
        adapter.start(dataSource, buildDeviceInfo())
        adapter.stop()
        assertThat(adapter.onStopCalled).isTrue()
    }

    @Test
    fun `stop clears connected clients`() = runTest {
        val adapter = TestAdapter()
        val dataSource = MutableSharedFlow<ExerciseData>()
        adapter.start(dataSource, buildDeviceInfo())
        adapter.triggerUpdateClients(setOf(ClientInfo("c1", "test", 0L)))
        assertThat(adapter.connectedClients.value).hasSize(1)
        adapter.stop()
        assertThat(adapter.connectedClients.value).isEmpty()
    }

    @Test
    fun `data collection calls broadcaster`() = runTest {
        val adapter = TestAdapter()
        val dataSource = MutableSharedFlow<ExerciseData>()
        adapter.start(dataSource, buildDeviceInfo())
        val data = buildExerciseData(power = 100)
        dataSource.emit(data)
        assertThat(adapter.receivedData).contains(data)
        adapter.stop()
    }

    @Test
    fun `setError transitions to Error state`() = runTest {
        val adapter = TestAdapter()
        val dataSource = MutableSharedFlow<ExerciseData>()
        adapter.start(dataSource, buildDeviceInfo())
        adapter.triggerError("test error")
        assertThat(adapter.state.value).isInstanceOf(AdapterState.Error::class.java)
        assertThat((adapter.state.value as AdapterState.Error).message).isEqualTo("test error")
    }

    @Test
    fun `emitCommand emits to incomingCommands`() = runTest(UnconfinedTestDispatcher()) {
        val adapter = TestAdapter()
        val dataSource = MutableSharedFlow<ExerciseData>()
        val received = mutableListOf<DeviceCommand>()
        backgroundScope.launch {
            adapter.incomingCommands.collect { received.add(it) }
        }
        adapter.start(dataSource, buildDeviceInfo())
        val command = DeviceCommand.SetResistance(5)
        adapter.triggerCommand(command)
        assertThat(received).contains(command)
        adapter.stop()
    }

    @Test
    fun `onStart failure transitions to Error and calls onStop`() = runTest {
        val adapter = TestAdapter(failOnStart = true)
        val dataSource = MutableSharedFlow<ExerciseData>()
        adapter.start(dataSource, buildDeviceInfo())
        assertThat(adapter.state.value).isInstanceOf(AdapterState.Error::class.java)
        assertThat(adapter.onStopCalled).isTrue()
    }

    private class TestAdapter(
        private val failOnStart: Boolean = false,
    ) : BaseBroadcastAdapter(
        logger = NoOpLogger(),
        tag = "TestAdapter",
        dispatcher = UnconfinedTestDispatcher(),
    ) {
        override val id: BroadcastId = BroadcastId.FTMS
        override val prerequisites: List<Prerequisite> = emptyList()
        override fun canOperate(snapshot: SystemSnapshot): Boolean = true

        var onStartCallCount = 0
        var onStopCalled = false
        val receivedData = mutableListOf<ExerciseData>()

        override suspend fun onStart(
            scope: CoroutineScope,
            deviceInfo: DeviceInfo,
        ): suspend (ExerciseData) -> Unit {
            if (failOnStart) throw RuntimeException("start failed")
            onStartCallCount++
            return { data -> receivedData.add(data) }
        }

        override fun onStop() {
            onStopCalled = true
        }

        fun triggerError(message: String) = setError(message)
        fun triggerCommand(command: DeviceCommand) = emitCommand(command)
        fun triggerUpdateClients(clients: Set<ClientInfo>) = updateClients(clients)
    }

    private class NoOpLogger : AppLogger {
        override fun d(tag: String, message: String) {}
        override fun i(tag: String, message: String) {}
        override fun w(tag: String, message: String) {}
        override fun e(tag: String, message: String, throwable: Throwable?) {}
    }
}
