package com.nettarion.hyperborea.core

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.test.buildDeviceInfo
import com.nettarion.hyperborea.core.test.buildSystemSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorTest {

    // --- Happy path ---

    @Test
    fun `start with all prerequisites met transitions to Running`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())
    }

    @Test
    fun `start connects hardware`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        assertThat(env.hardware.connectCalled).isTrue()
    }

    @Test
    fun `start starts broadcast adapters that can operate`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        assertThat(env.broadcast1.startCalled).isTrue()
        assertThat(env.broadcast2.startCalled).isTrue()
    }

    @Test
    fun `start refreshes system monitor twice`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        assertThat(env.monitor.refreshCount).isEqualTo(2)
    }

    // --- Ecosystem prerequisites ---

    @Test
    fun `ecosystem prerequisite unmet and fulfilled transitions to Running`() = runTest {
        val prereq = Prerequisite(
            id = "eco-test",
            description = "test",
            isMet = { false },
            fulfill = { FulfillResult.Success },
        )
        val env = TestEnv(this, ecosystemPrereqs = listOf(prereq))
        env.orchestrator.start()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())
    }

    @Test
    fun `ecosystem prerequisite unmet and fulfill fails transitions to Error`() = runTest {
        val prereq = Prerequisite(
            id = "eco-fail",
            description = "test",
            isMet = { false },
            fulfill = { FulfillResult.Failed("boom") },
        )
        val env = TestEnv(this, ecosystemPrereqs = listOf(prereq))
        env.orchestrator.start()
        assertThat(env.orchestrator.state.value).isInstanceOf(OrchestratorState.Error::class.java)
        assertThat((env.orchestrator.state.value as OrchestratorState.Error).message).contains("boom")
    }

    @Test
    fun `ecosystem prerequisite unmet with no fulfill transitions to Error`() = runTest {
        val prereq = Prerequisite(
            id = "eco-no-fulfill",
            description = "no way",
            isMet = { false },
            fulfill = null,
        )
        val env = TestEnv(this, ecosystemPrereqs = listOf(prereq))
        env.orchestrator.start()
        assertThat(env.orchestrator.state.value).isInstanceOf(OrchestratorState.Error::class.java)
        assertThat((env.orchestrator.state.value as OrchestratorState.Error).message).contains("eco-no-fulfill")
    }

    // --- Hardware prerequisites ---

    @Test
    fun `hardware prerequisite unmet and fulfilled transitions to Running`() = runTest {
        val prereq = Prerequisite(
            id = "hw-test",
            description = "test",
            isMet = { false },
            fulfill = { FulfillResult.Success },
        )
        val env = TestEnv(this, hardwarePrereqs = listOf(prereq))
        env.orchestrator.start()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())
    }

    @Test
    fun `hardware prerequisite unmet and fulfill fails transitions to Error`() = runTest {
        val prereq = Prerequisite(
            id = "hw-fail",
            description = "test",
            isMet = { false },
            fulfill = { FulfillResult.Failed("hw boom") },
        )
        val env = TestEnv(this, hardwarePrereqs = listOf(prereq))
        env.orchestrator.start()
        assertThat(env.orchestrator.state.value).isInstanceOf(OrchestratorState.Error::class.java)
        assertThat((env.orchestrator.state.value as OrchestratorState.Error).message).contains("hw boom")
    }

    // --- Hardware connect failure ---

    @Test
    fun `hardware connect failure transitions to Error`() = runTest {
        val env = TestEnv(this, hardwareConnectState = AdapterState.Error("usb failed"))
        env.orchestrator.start()
        assertThat(env.orchestrator.state.value).isInstanceOf(OrchestratorState.Error::class.java)
        assertThat((env.orchestrator.state.value as OrchestratorState.Error).message).contains("usb failed")
    }

    // --- canOperate filtering ---

    @Test
    fun `broadcast adapter that cannot operate is skipped`() = runTest {
        val env = TestEnv(this, broadcast1CanOperate = false)
        env.orchestrator.start()
        assertThat(env.broadcast1.startCalled).isFalse()
        assertThat(env.broadcast2.startCalled).isTrue()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())
    }

    @Test
    fun `hardware canOperate false transitions to Error`() = runTest {
        val env = TestEnv(this, hardwareCanOperate = false)
        env.orchestrator.start()
        assertThat(env.orchestrator.state.value).isInstanceOf(OrchestratorState.Error::class.java)
        assertThat((env.orchestrator.state.value as OrchestratorState.Error).message).contains("Hardware cannot operate")
    }

    // --- Stop ---

    @Test
    fun `stop transitions from Running to Idle`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        env.orchestrator.stop()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Idle)
    }

    @Test
    fun `stop disconnects hardware`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        env.orchestrator.stop()
        assertThat(env.hardware.disconnectCalled).isTrue()
    }

    @Test
    fun `stop stops broadcast adapters`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        env.orchestrator.stop()
        assertThat(env.broadcast1.stopCalled).isTrue()
        assertThat(env.broadcast2.stopCalled).isTrue()
    }

    // --- Idempotency ---

    @Test
    fun `double start is no-op`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        env.hardware.connectCalled = false
        env.orchestrator.start()
        assertThat(env.hardware.connectCalled).isFalse()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())
    }

    @Test
    fun `double stop is no-op`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.stop()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Idle)
    }

    @Test
    fun `start after stop works`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        env.orchestrator.stop()
        env.orchestrator.start()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())
    }

    // --- Hardware disconnect while running ---

    @Test
    fun `hardware error with reconnect failure stops broadcasts and transitions to Error`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())

        env.hardware.reconnectResult = AdapterState.Error("still broken")
        env.hardware.mutableState.value = AdapterState.Error("USB disconnected")
        advanceUntilIdle()

        assertThat(env.orchestrator.state.value).isInstanceOf(OrchestratorState.Error::class.java)
        assertThat((env.orchestrator.state.value as OrchestratorState.Error).message).contains("reconnect failed")
        assertThat(env.broadcast1.stopCalled).isTrue()
        assertThat(env.broadcast2.stopCalled).isTrue()
    }

    @Test
    fun `hardware going inactive with reconnect failure transitions to Error`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())

        env.hardware.reconnectResult = AdapterState.Error("still broken")
        env.hardware.mutableState.value = AdapterState.Inactive
        advanceUntilIdle()

        assertThat(env.orchestrator.state.value).isInstanceOf(OrchestratorState.Error::class.java)
        assertThat((env.orchestrator.state.value as OrchestratorState.Error).message).contains("reconnect failed")
        assertThat(env.broadcast1.stopCalled).isTrue()
    }

    // --- Pause/Resume ---

    @Test
    fun `pause from Running transitions to Paused and sends PauseWorkout command`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        env.orchestrator.pause()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Paused)
        assertThat(env.hardware.receivedCommands).contains(DeviceCommand.PauseWorkout)
    }

    @Test
    fun `resume from Paused transitions to Running and sends ResumeWorkout command`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        env.orchestrator.pause()
        env.orchestrator.resume()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())
        assertThat(env.hardware.receivedCommands).contains(DeviceCommand.ResumeWorkout)
    }

    @Test
    fun `pause when not Running is no-op`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.pause()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Idle)
        assertThat(env.hardware.receivedCommands).isEmpty()
    }

    @Test
    fun `resume when not Paused is no-op`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        env.orchestrator.resume()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())
        assertThat(env.hardware.receivedCommands).isEmpty()
    }

    @Test
    fun `stop from Paused transitions to Idle`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        env.orchestrator.pause()
        env.orchestrator.stop()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Idle)
    }

    @Test
    fun `start when Paused is no-op`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        env.orchestrator.pause()
        env.hardware.connectCalled = false
        env.orchestrator.start()
        assertThat(env.hardware.connectCalled).isFalse()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Paused)
    }

    // --- Command pipeline ---

    @Test
    fun `command from broadcast is forwarded to hardware`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        val command = DeviceCommand.SetResistance(5)
        env.broadcast1.emitCommand(command)
        assertThat(env.hardware.receivedCommands).contains(command)
    }

    // --- User preferences ---

    @Test
    fun `disabled broadcast is not started`() = runTest {
        val env = TestEnv(this, enabledBroadcasts = setOf(BroadcastId.FTMS))
        env.orchestrator.start()
        assertThat(env.broadcast1.startCalled).isTrue()
        assertThat(env.broadcast2.startCalled).isFalse()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())
    }

    @Test
    fun `all broadcasts disabled still transitions to Running`() = runTest {
        val env = TestEnv(this, enabledBroadcasts = emptySet())
        env.orchestrator.start()
        assertThat(env.broadcast1.startCalled).isFalse()
        assertThat(env.broadcast2.startCalled).isFalse()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())
    }

    @Test
    fun `disabled broadcast is not stopped on orchestrator stop`() = runTest {
        val env = TestEnv(this, enabledBroadcasts = setOf(BroadcastId.FTMS))
        env.orchestrator.start()
        env.orchestrator.stop()
        assertThat(env.broadcast1.stopCalled).isTrue()
        assertThat(env.broadcast2.stopCalled).isFalse()
    }

    // --- Command pipeline resilience ---

    @Test
    fun `command pipeline error does not crash orchestrator`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        env.hardware.shouldThrowOnSendCommand = true

        val command = DeviceCommand.SetResistance(5)
        env.broadcast1.emitCommand(command)

        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())
    }

    // --- Broadcast retry ---

    @Test
    fun `broadcast error triggers retry`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        assertThat(env.broadcast1.startCallCount).isEqualTo(1)

        env.broadcast1.setError("BLE failed")
        advanceUntilIdle()

        assertThat(env.broadcast1.startCallCount).isGreaterThan(1)
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())
    }

    @Test
    fun `broadcast exhausts retries transitions to degraded`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()

        env.broadcast1.failOnStart = true
        env.broadcast1.setError("BLE failed")
        advanceUntilIdle()

        val state = env.orchestrator.state.value
        assertThat(state).isInstanceOf(OrchestratorState.Running::class.java)
        assertThat((state as OrchestratorState.Running).degraded).isNotNull()
    }

    // --- Hardware reconnect ---

    @Test
    fun `hardware disconnect triggers reconnect attempts`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        val initialCount = env.hardware.connectCallCount

        env.hardware.mutableState.value = AdapterState.Error("USB glitch")
        advanceUntilIdle()

        assertThat(env.hardware.connectCallCount).isGreaterThan(initialCount)
    }

    @Test
    fun `hardware reconnect success returns to Running`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()

        env.hardware.mutableState.value = AdapterState.Error("USB glitch")
        advanceUntilIdle()

        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())
    }

    @Test
    fun `hardware reconnect exhausts retries transitions to Error`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()

        env.hardware.reconnectResult = AdapterState.Error("still broken")
        env.hardware.mutableState.value = AdapterState.Error("USB broken")
        advanceUntilIdle()

        assertThat(env.orchestrator.state.value).isInstanceOf(OrchestratorState.Error::class.java)
        assertThat((env.orchestrator.state.value as OrchestratorState.Error).message).contains("reconnect failed")
    }

    // --- Prerequisite timeout ---

    @Test
    fun `prerequisite timeout transitions to Error`() = runTest {
        val prereq = Prerequisite(
            id = "slow-prereq",
            description = "hangs forever",
            isMet = { false },
            fulfill = { delay(Long.MAX_VALUE); FulfillResult.Success },
        )
        val env = TestEnv(this, ecosystemPrereqs = listOf(prereq))
        env.orchestrator.start()

        assertThat(env.orchestrator.state.value).isInstanceOf(OrchestratorState.Error::class.java)
        assertThat((env.orchestrator.state.value as OrchestratorState.Error).message).contains("Timeout")
    }

    // --- Fakes ---

    private class TestEnv(
        testScope: TestScope,
        ecosystemPrereqs: List<Prerequisite> = emptyList(),
        hardwarePrereqs: List<Prerequisite> = emptyList(),
        hardwareConnectState: AdapterState = AdapterState.Active,
        hardwareCanOperate: Boolean = true,
        broadcast1CanOperate: Boolean = true,
        broadcast2CanOperate: Boolean = true,
        enabledBroadcasts: Set<BroadcastId> = BroadcastId.entries.toSet(),
    ) {
        val monitor = FakeSystemMonitor()
        val controller = FakeSystemController()
        val ecosystem = FakeEcosystemManager(ecosystemPrereqs)
        val hardware = FakeHardwareAdapter(hardwarePrereqs, hardwareConnectState, hardwareCanOperate)
        val broadcast1 = FakeBroadcastAdapter(BroadcastId.FTMS, broadcast1CanOperate)
        val broadcast2 = FakeBroadcastAdapter(BroadcastId.WFTNP, broadcast2CanOperate)
        val preferences = FakeUserPreferences(enabledBroadcasts)
        val logger = NoOpLogger()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScope.testScheduler))
        val profileRepository = FakeProfileRepository()
        val rideRecorder = RideRecorder(profileRepository, logger, scope)

        val orchestrator = Orchestrator(
            systemMonitor = monitor,
            systemController = controller,
            ecosystemManager = ecosystem,
            hardwareAdapter = hardware,
            broadcastAdapters = setOf(broadcast1, broadcast2),
            userPreferences = preferences,
            rideRecorder = rideRecorder,
            logger = logger,
            scope = scope,
        )
    }

    private class FakeSystemMonitor : SystemMonitor {
        private var nextTimestamp = 1L
        override val snapshot = MutableStateFlow(buildSystemSnapshot())
        var refreshCount = 0

        override suspend fun refresh() {
            refreshCount++
            snapshot.value = snapshot.value.copy(timestamp = nextTimestamp++)
        }
    }

    private class FakeSystemController : SystemController {
        val calls = mutableListOf<String>()

        override suspend fun stopService(packageName: String, className: String): Boolean {
            calls.add("stopService:$packageName/$className"); return true
        }

        override suspend fun forceStopPackage(packageName: String): Boolean {
            calls.add("forceStop:$packageName"); return true
        }

        override suspend fun disablePackage(packageName: String) = false
        override suspend fun enablePackage(packageName: String) = false
        override suspend fun uninstallPackage(packageName: String) = false
        override suspend fun disableComponent(packageName: String, className: String) = false
        override suspend fun enableComponent(packageName: String, className: String) = false
        override suspend fun grantUsbPermission(packageName: String) = false
        override suspend fun revokeUsbPermissions(packageName: String) = false
    }

    private class FakeEcosystemManager(
        override val prerequisites: List<Prerequisite>,
    ) : EcosystemManager

    private class FakeHardwareAdapter(
        override val prerequisites: List<Prerequisite>,
        private val connectState: AdapterState,
        private val operatable: Boolean,
    ) : HardwareAdapter {
        val mutableState = MutableStateFlow<AdapterState>(AdapterState.Inactive)
        override val state: StateFlow<AdapterState> = mutableState
        override val deviceInfo = MutableStateFlow<DeviceInfo?>(buildDeviceInfo())
        override val exerciseData = MutableStateFlow<ExerciseData?>(null)
        override val deviceIdentity = MutableStateFlow<DeviceIdentity?>(null)
        var connectCalled = false
        var connectCallCount = 0
        var disconnectCalled = false
        val receivedCommands = mutableListOf<DeviceCommand>()
        var shouldThrowOnSendCommand = false
        var reconnectResult: AdapterState? = null

        override fun canOperate(snapshot: SystemSnapshot) = operatable

        override suspend fun connect() {
            connectCalled = true
            connectCallCount++
            mutableState.value = if (connectCallCount > 1 && reconnectResult != null) reconnectResult!! else connectState
        }

        override suspend fun disconnect() {
            disconnectCalled = true
            mutableState.value = AdapterState.Inactive
        }

        override suspend fun sendCommand(command: DeviceCommand) {
            if (shouldThrowOnSendCommand) throw RuntimeException("sendCommand failed")
            receivedCommands.add(command)
        }
    }

    private class FakeUserPreferences(
        enabled: Set<BroadcastId>,
    ) : UserPreferences {
        override val enabledBroadcasts = MutableStateFlow(enabled)
        override fun setBroadcastEnabled(id: BroadcastId, enabled: Boolean) {
            val current = enabledBroadcasts.value.toMutableSet()
            if (enabled) current.add(id) else current.remove(id)
            enabledBroadcasts.value = current
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
        var failOnStart = false

        override fun canOperate(snapshot: SystemSnapshot) = operatable

        override suspend fun start(dataSource: Flow<ExerciseData>, deviceInfo: DeviceInfo) {
            startCalled = true
            startCallCount++
            if (failOnStart) {
                _state.value = AdapterState.Error("start failed #$startCallCount")
            } else {
                _state.value = AdapterState.Active
            }
        }

        override suspend fun stop() {
            stopCalled = true
            _state.value = AdapterState.Inactive
        }

        suspend fun emitCommand(command: DeviceCommand) {
            _incomingCommands.emit(command)
        }

        fun setError(message: String) {
            _state.value = AdapterState.Error(message)
        }
    }

    private class FakeProfileRepository : ProfileRepository {
        override val profiles: Flow<List<Profile>> = MutableStateFlow(emptyList())
        override val activeProfile: StateFlow<Profile?> = MutableStateFlow(null)
        override suspend fun createProfile(name: String) = Profile(id = 1, name = name)
        override suspend fun updateProfile(profile: Profile) {}
        override suspend fun deleteProfile(id: Long) {}
        override suspend fun setActiveProfile(id: Long) {}
        override fun getRideSummaries(profileId: Long): Flow<List<RideSummary>> = MutableStateFlow(emptyList())
        override suspend fun saveRideSummary(summary: RideSummary, samples: List<WorkoutSample>) {}
        override suspend fun deleteRideSummary(id: Long) {}
        override fun getWorkoutSamples(rideId: Long): Flow<List<WorkoutSample>> = MutableStateFlow(emptyList())
    }

    private class NoOpLogger : AppLogger {
        override fun d(tag: String, message: String) {}
        override fun i(tag: String, message: String) {}
        override fun w(tag: String, message: String) {}
        override fun e(tag: String, message: String, throwable: Throwable?) {}
    }
}
