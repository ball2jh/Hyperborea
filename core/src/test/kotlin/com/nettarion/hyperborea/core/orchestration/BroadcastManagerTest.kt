package com.nettarion.hyperborea.core.orchestration

import com.nettarion.hyperborea.core.adapter.AdapterState
import com.nettarion.hyperborea.core.adapter.BroadcastAdapter
import com.nettarion.hyperborea.core.adapter.BroadcastId
import com.nettarion.hyperborea.core.model.ClientInfo
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.profile.UserPreferences
import com.nettarion.hyperborea.core.system.SystemMonitor
import com.nettarion.hyperborea.core.system.SystemSnapshot
import com.nettarion.hyperborea.core.test.TestAppLogger
import com.nettarion.hyperborea.core.test.buildDeviceInfo
import com.nettarion.hyperborea.core.test.buildExerciseData
import com.nettarion.hyperborea.core.test.buildSystemSnapshot

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BroadcastManagerTest {

    // --- Start/Stop ---

    @Test
    fun `start starts enabled broadcast adapters`() = runTest {
        val env = TestEnv(this)
        env.manager.start()
        assertThat(env.broadcast1.startCalled).isTrue()
        assertThat(env.broadcast2.startCalled).isTrue()
    }

    @Test
    fun `start skips disabled broadcast adapters`() = runTest {
        val env = TestEnv(this, enabledBroadcasts = setOf(BroadcastId.FTMS))
        env.manager.start()
        assertThat(env.broadcast1.startCalled).isTrue()
        assertThat(env.broadcast2.startCalled).isFalse()
    }

    @Test
    fun `start skips adapters that cannot operate`() = runTest {
        val env = TestEnv(this, broadcast1CanOperate = false)
        env.manager.start()
        assertThat(env.broadcast1.startCalled).isFalse()
        assertThat(env.broadcast2.startCalled).isTrue()
    }

    @Test
    fun `stop stops all active broadcasts`() = runTest {
        val env = TestEnv(this)
        env.manager.start()
        env.manager.stop()
        assertThat(env.broadcast1.stopCalled).isTrue()
        assertThat(env.broadcast2.stopCalled).isTrue()
    }

    @Test
    fun `double start is no-op`() = runTest {
        val env = TestEnv(this)
        env.manager.start()
        env.broadcast1.startCallCount = 0
        env.manager.start()
        assertThat(env.broadcast1.startCallCount).isEqualTo(0)
    }

    @Test
    fun `double stop is no-op`() = runTest {
        val env = TestEnv(this)
        env.manager.stop()
        assertThat(env.broadcast1.stopCalled).isFalse()
    }

    // --- Toggle ---

    @Test
    fun `toggle off stops broadcast`() = runTest {
        val env = TestEnv(this)
        env.manager.start()
        env.preferences.setBroadcastEnabled(BroadcastId.WIFI, false)
        advanceUntilIdle()
        assertThat(env.broadcast2.stopCalled).isTrue()
        assertThat(env.broadcast1.stopCalled).isFalse()
    }

    @Test
    fun `toggle on starts broadcast`() = runTest {
        val env = TestEnv(this, enabledBroadcasts = setOf(BroadcastId.FTMS))
        env.manager.start()
        assertThat(env.broadcast2.startCalled).isFalse()
        env.preferences.setBroadcastEnabled(BroadcastId.WIFI, true)
        advanceUntilIdle()
        assertThat(env.broadcast2.startCalled).isTrue()
    }

    @Test
    fun `toggle off then on restarts broadcast`() = runTest {
        val env = TestEnv(this)
        env.manager.start()
        assertThat(env.broadcast1.startCallCount).isEqualTo(1)

        env.preferences.setBroadcastEnabled(BroadcastId.FTMS, false)
        advanceUntilIdle()
        assertThat(env.broadcast1.stopCallCount).isEqualTo(1)

        env.preferences.setBroadcastEnabled(BroadcastId.FTMS, true)
        advanceUntilIdle()
        assertThat(env.broadcast1.startCallCount).isEqualTo(2)
    }

    @Test
    fun `toggle while not started is no-op`() = runTest {
        val env = TestEnv(this)
        env.preferences.setBroadcastEnabled(BroadcastId.WIFI, false)
        advanceUntilIdle()
        assertThat(env.broadcast1.startCalled).isFalse()
        assertThat(env.broadcast2.stopCalled).isFalse()
    }

    @Test
    fun `toggle broadcast that cannot operate does not start`() = runTest {
        val env = TestEnv(this, enabledBroadcasts = setOf(BroadcastId.FTMS), broadcast2CanOperate = false)
        env.manager.start()
        env.preferences.setBroadcastEnabled(BroadcastId.WIFI, true)
        advanceUntilIdle()
        assertThat(env.broadcast2.startCalled).isFalse()
    }

    // --- Retry ---

    @Test
    fun `broadcast error triggers retry`() = runTest {
        val env = TestEnv(this)
        env.manager.start()
        assertThat(env.broadcast1.startCallCount).isEqualTo(1)

        env.broadcast1.setError("BLE failed")
        advanceUntilIdle()

        assertThat(env.broadcast1.startCallCount).isGreaterThan(1)
    }

    @Test
    fun `broadcast exhausts retries`() = runTest {
        val env = TestEnv(this)
        env.manager.start()

        env.broadcast1.failOnStart = true
        env.broadcast1.setError("BLE failed")
        advanceUntilIdle()

        // After 3 retries all failing, startCallCount should be 1 (initial) + 3 (retries) = 4
        assertThat(env.broadcast1.startCallCount).isEqualTo(4)
    }

    // --- Commands ---

    @Test
    fun `commands from broadcasts are aggregated into incomingCommands`() = runTest {
        val env = TestEnv(this)
        env.manager.start()

        val collected = mutableListOf<DeviceCommand>()
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            env.manager.incomingCommands.toList(collected)
        }

        val cmd1 = DeviceCommand.SetResistance(5)
        val cmd2 = DeviceCommand.SetIncline(3.0f)
        env.broadcast1.emitCommand(cmd1)
        env.broadcast2.emitCommand(cmd2)

        assertThat(collected).containsExactly(cmd1, cmd2)
        collectJob.cancel()
    }

    @Test
    fun `command from dynamically started broadcast is aggregated`() = runTest {
        val env = TestEnv(this, enabledBroadcasts = setOf(BroadcastId.FTMS))
        env.manager.start()

        val collected = mutableListOf<DeviceCommand>()
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            env.manager.incomingCommands.toList(collected)
        }

        env.preferences.setBroadcastEnabled(BroadcastId.WIFI, true)
        advanceUntilIdle()

        val cmd = DeviceCommand.SetResistance(10)
        env.broadcast2.emitCommand(cmd)
        assertThat(collected).contains(cmd)
        collectJob.cancel()
    }

    // --- Data source ---

    @Test
    fun `connectDataSource and disconnectDataSource manage forwarding job`() = runTest {
        val env = TestEnv(this)
        env.manager.start()

        val source = MutableStateFlow<ExerciseData?>(null)
        env.manager.connectDataSource(source)

        val data = buildExerciseData(power = 100)
        source.value = data
        advanceUntilIdle()

        env.manager.disconnectDataSource()

        // Further emissions should not be forwarded (no crash, just dropped)
        source.value = buildExerciseData(power = 200)
        advanceUntilIdle()
    }

    // --- Device info ---

    @Test
    fun `updateDeviceInfo restarts broadcasts if info differs`() = runTest {
        val env = TestEnv(this)
        env.manager.start()
        assertThat(env.broadcast1.startCallCount).isEqualTo(1)

        val newInfo = buildDeviceInfo(name = "Different Device")
        env.manager.updateDeviceInfo(newInfo)

        assertThat(env.broadcast1.stopCallCount).isEqualTo(1)
        assertThat(env.broadcast1.startCallCount).isEqualTo(2)
    }

    @Test
    fun `updateDeviceInfo is no-op if info matches`() = runTest {
        val env = TestEnv(this)
        env.manager.start()
        assertThat(env.broadcast1.startCallCount).isEqualTo(1)

        env.manager.updateDeviceInfo(DeviceInfo.DEFAULT_INDOOR_BIKE)

        assertThat(env.broadcast1.startCallCount).isEqualTo(1)
        assertThat(env.broadcast1.stopCallCount).isEqualTo(0)
    }

    @Test
    fun `start uses DEFAULT_INDOOR_BIKE when no updateDeviceInfo called`() = runTest {
        val env = TestEnv(this)
        env.manager.start()
        assertThat(env.broadcast1.lastDeviceInfo).isEqualTo(DeviceInfo.DEFAULT_INDOOR_BIKE)
    }

    // --- Fakes ---

    private class TestEnv(
        testScope: TestScope,
        broadcast1CanOperate: Boolean = true,
        broadcast2CanOperate: Boolean = true,
        enabledBroadcasts: Set<BroadcastId> = BroadcastId.entries.toSet(),
    ) {
        val monitor = FakeSystemMonitor()
        val preferences = FakeUserPreferences(enabledBroadcasts)
        val broadcast1 = FakeBroadcastAdapter(BroadcastId.FTMS, broadcast1CanOperate)
        val broadcast2 = FakeBroadcastAdapter(BroadcastId.WIFI, broadcast2CanOperate)
        val logger = TestAppLogger()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScope.testScheduler))

        val manager = BroadcastManager(
            broadcastAdapters = setOf(broadcast1, broadcast2),
            systemMonitor = monitor,
            userPreferences = preferences,
            logger = logger,
            scope = scope,
        )
    }

    private class FakeSystemMonitor : SystemMonitor {
        override val snapshot = MutableStateFlow(buildSystemSnapshot())
        override suspend fun refresh() {}
    }

    private class FakeUserPreferences(
        enabled: Set<BroadcastId>,
    ) : UserPreferences {
        override val enabledBroadcasts = MutableStateFlow(enabled)
        override val overlayEnabled = MutableStateFlow(false)
        override val savedSensorAddress = MutableStateFlow<String?>(null)
        override fun setBroadcastEnabled(id: BroadcastId, enabled: Boolean) {
            val current = enabledBroadcasts.value.toMutableSet()
            if (enabled) current.add(id) else current.remove(id)
            enabledBroadcasts.value = current
        }
        override fun setOverlayEnabled(enabled: Boolean) {
            overlayEnabled.value = enabled
        }
        override fun setSavedSensorAddress(address: String?) {
            savedSensorAddress.value = address
        }
    }

    private class FakeBroadcastAdapter(
        override val id: BroadcastId,
        private val operatable: Boolean,
    ) : BroadcastAdapter {
        private val _state = MutableStateFlow<AdapterState>(AdapterState.Inactive)
        override val state: StateFlow<AdapterState> = _state
        override val prerequisites: List<Prerequisite> = emptyList()
        override val connectedClients = MutableStateFlow<Set<ClientInfo>>(emptySet())
        private val _incomingCommands = MutableSharedFlow<DeviceCommand>()
        override val incomingCommands: Flow<DeviceCommand> = _incomingCommands
        var startCalled = false
        var startCallCount = 0
        var stopCalled = false
        var stopCallCount = 0
        var failOnStart = false
        var lastDeviceInfo: DeviceInfo? = null

        override fun canOperate(snapshot: SystemSnapshot) = operatable

        override suspend fun start(dataSource: Flow<ExerciseData>, deviceInfo: DeviceInfo) {
            startCalled = true
            startCallCount++
            lastDeviceInfo = deviceInfo
            if (failOnStart) {
                _state.value = AdapterState.Error("start failed #$startCallCount")
            } else {
                _state.value = AdapterState.Active
            }
        }

        override suspend fun stop() {
            stopCalled = true
            stopCallCount++
            _state.value = AdapterState.Inactive
        }

        suspend fun emitCommand(command: DeviceCommand) {
            _incomingCommands.emit(command)
        }

        fun setError(message: String) {
            _state.value = AdapterState.Error(message)
        }
    }
}
