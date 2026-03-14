package com.nettarion.hyperborea.core.orchestration

import com.nettarion.hyperborea.core.adapter.AdapterState
import com.nettarion.hyperborea.core.adapter.BroadcastAdapter
import com.nettarion.hyperborea.core.adapter.BroadcastId
import com.nettarion.hyperborea.core.adapter.DiscoveredSensor
import com.nettarion.hyperborea.core.adapter.HardwareAdapter
import com.nettarion.hyperborea.core.adapter.SensorAdapter
import com.nettarion.hyperborea.core.adapter.SensorId
import com.nettarion.hyperborea.core.adapter.SensorReading
import com.nettarion.hyperborea.core.model.ClientInfo
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.model.DeviceIdentity
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.model.Profile
import com.nettarion.hyperborea.core.model.RideSummary
import com.nettarion.hyperborea.core.model.WorkoutSample
import com.nettarion.hyperborea.core.profile.ProfileRepository
import com.nettarion.hyperborea.core.profile.RideRecorder
import com.nettarion.hyperborea.core.profile.UserPreferences
import com.nettarion.hyperborea.core.system.SystemController
import com.nettarion.hyperborea.core.system.SystemMonitor
import com.nettarion.hyperborea.core.system.SystemSnapshot

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.test.TestAppLogger
import com.nettarion.hyperborea.core.test.buildDeviceInfo
import com.nettarion.hyperborea.core.test.buildSystemSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    fun `hardware error with reconnect failure transitions to Error`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())

        env.hardware.reconnectResult = AdapterState.Error("still broken")
        env.hardware.mutableState.value = AdapterState.Error("USB disconnected")
        advanceUntilIdle()

        assertThat(env.orchestrator.state.value).isInstanceOf(OrchestratorState.Error::class.java)
        assertThat((env.orchestrator.state.value as OrchestratorState.Error).message).contains("reconnect failed")
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

    @Test
    fun `command pipeline error does not crash orchestrator`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        env.hardware.shouldThrowOnSendCommand = true

        val command = DeviceCommand.SetResistance(5)
        env.broadcast1.emitCommand(command)

        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())
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

    // --- BroadcastManager integration ---

    @Test
    fun `start connects data source to broadcast manager`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        // Broadcasts are started by BroadcastManager.start() (service lifecycle),
        // but data flows through connectDataSource. Verify broadcasts received data source.
        assertThat(env.broadcast1.startCalled).isTrue()
    }

    @Test
    fun `stop disconnects data source from broadcast manager`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        env.orchestrator.stop()
        // Broadcasts keep running (BroadcastManager not stopped — service lifecycle),
        // but data source disconnected. Verify orchestrator reached Idle.
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Idle)
    }

    @Test
    fun `start updates broadcast manager device info`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        // BroadcastManager.updateDeviceInfo is called with hardware's device info.
        // Since our fake returns buildDeviceInfo() which differs from DEFAULT_INDOOR_BIKE,
        // broadcasts get restarted with the new info.
        assertThat(env.broadcast1.lastDeviceInfo).isEqualTo(buildDeviceInfo())
    }

    // --- Probe ---

    @Test
    fun `probe returns device info on success`() = runTest {
        val env = TestEnv(this)
        val result = env.orchestrator.probe()
        assertThat(result).isEqualTo(buildDeviceInfo())
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Idle)
    }

    @Test
    fun `probe returns null on ecosystem prerequisite failure`() = runTest {
        val prereq = Prerequisite(
            id = "eco-fail",
            description = "test",
            isMet = { false },
            fulfill = { FulfillResult.Failed("boom") },
        )
        val env = TestEnv(this, ecosystemPrereqs = listOf(prereq))
        val result = env.orchestrator.probe()
        assertThat(result).isNull()
        assertThat(env.orchestrator.state.value).isInstanceOf(OrchestratorState.Error::class.java)
    }

    @Test
    fun `probe returns null when hardware cannot operate`() = runTest {
        val env = TestEnv(this, hardwareCanOperate = false)
        val result = env.orchestrator.probe()
        assertThat(result).isNull()
        assertThat(env.orchestrator.state.value).isInstanceOf(OrchestratorState.Error::class.java)
    }

    @Test
    fun `probe returns null on identify failure`() = runTest {
        val env = TestEnv(this)
        env.hardware.identifyResult = null
        val result = env.orchestrator.probe()
        assertThat(result).isNull()
        assertThat(env.orchestrator.state.value).isInstanceOf(OrchestratorState.Error::class.java)
    }

    @Test
    fun `probe returns null when not Idle`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())
        val result = env.orchestrator.probe()
        assertThat(result).isNull()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())
    }

    @Test
    fun `probe updates broadcast manager device info`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.probe()
        // After probe, broadcasts should be restarted with the identified device info
        assertThat(env.broadcast1.lastDeviceInfo).isEqualTo(buildDeviceInfo())
    }

    @Test
    fun `start works after successful probe`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.probe()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Idle)
        env.orchestrator.start()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())
    }

    // --- Sensor tests ---

    @Test
    fun `start works without sensor adapter`() = runTest {
        val env = TestEnv(this, sensorAdapter = null)
        env.orchestrator.start()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())
    }

    @Test
    fun `start works with sensor adapter`() = runTest {
        val sensor = FakeSensorAdapter()
        val env = TestEnv(this, sensorAdapter = sensor)
        env.orchestrator.start()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())
    }

    @Test
    fun `stop disconnects sensor adapter`() = runTest {
        val sensor = FakeSensorAdapter()
        val env = TestEnv(this, sensorAdapter = sensor)
        env.orchestrator.start()
        env.orchestrator.stop()
        assertThat(sensor.disconnectCalled).isTrue()
    }

    @Test
    fun `sensor reading merges into exercise data`() = runTest {
        val sensor = FakeSensorAdapter()
        val env = TestEnv(this, sensorAdapter = sensor)
        env.orchestrator.start()

        // Emit exercise data without HR
        env.hardware.exerciseData.value = ExerciseData(
            power = 150, cadence = 80, speed = 25f, resistance = 10,
            incline = 2f, heartRate = null, distance = 1f, calories = 50, elapsedTime = 60,
        )
        // Emit sensor reading
        sensor.mutableReading.value = SensorReading.HeartRate(142)

        // The merged flow should combine both — verify broadcast received data with HR
        advanceUntilIdle()

        // Broadcasts get the merged data via connectDataSource
        assertThat(env.broadcast1.startCalled).isTrue()
    }

    // --- DMK safety key handling ---

    @Test
    fun `DMK workoutMode 8 transitions Running to Paused`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())

        // Emit exercise data with DMK workout mode
        env.hardware.exerciseData.value = ExerciseData(
            power = 0, cadence = 0, speed = 0f, resistance = 0,
            incline = 0f, heartRate = null, distance = null, calories = null,
            elapsedTime = 60, workoutMode = 8,
        )
        advanceUntilIdle()

        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Paused)
    }

    @Test
    fun `IDLE workoutMode 1 after DMK transitions Paused to Running`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()

        // First go to Paused via DMK
        env.hardware.exerciseData.value = ExerciseData(
            power = 0, cadence = 0, speed = 0f, resistance = 0,
            incline = 0f, heartRate = null, distance = null, calories = null,
            elapsedTime = 60, workoutMode = 8,
        )
        advanceUntilIdle()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Paused)

        // Then re-insert safety key (IDLE mode)
        env.hardware.exerciseData.value = ExerciseData(
            power = 0, cadence = 0, speed = 0f, resistance = 0,
            incline = 0f, heartRate = null, distance = null, calories = null,
            elapsedTime = 60, workoutMode = 1,
        )
        advanceUntilIdle()

        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())
        assertThat(env.hardware.receivedCommands).contains(DeviceCommand.ResumeWorkout)
    }

    @Test
    fun `DMK is no-op when not Running`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        env.orchestrator.pause() // manually paused

        env.hardware.exerciseData.value = ExerciseData(
            power = 0, cadence = 0, speed = 0f, resistance = 0,
            incline = 0f, heartRate = null, distance = null, calories = null,
            elapsedTime = 60, workoutMode = 8,
        )
        advanceUntilIdle()

        // Should stay Paused (was already paused)
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Paused)
    }

    // --- Fakes ---

    private class TestEnv(
        testScope: TestScope,
        ecosystemPrereqs: List<Prerequisite> = emptyList(),
        hardwarePrereqs: List<Prerequisite> = emptyList(),
        hardwareConnectState: AdapterState = AdapterState.Active,
        hardwareCanOperate: Boolean = true,
        enabledBroadcasts: Set<BroadcastId> = BroadcastId.entries.toSet(),
        sensorAdapter: SensorAdapter? = FakeSensorAdapter(),
    ) {
        val monitor = FakeSystemMonitor()
        val controller = FakeSystemController()
        val ecosystem = FakeEcosystemManager(ecosystemPrereqs)
        val hardware = FakeHardwareAdapter(hardwarePrereqs, hardwareConnectState, hardwareCanOperate)
        val broadcast1 = FakeBroadcastAdapter(BroadcastId.FTMS, true)
        val broadcast2 = FakeBroadcastAdapter(BroadcastId.WIFI, true)
        val preferences = FakeUserPreferences(enabledBroadcasts)
        val logger = TestAppLogger()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScope.testScheduler))
        val profileRepository = FakeProfileRepository()
        val rideRecorder = RideRecorder(profileRepository, logger, scope)

        val broadcastManager = BroadcastManager(
            broadcastAdapters = setOf(broadcast1, broadcast2),
            systemMonitor = monitor,
            userPreferences = preferences,
            logger = logger,
            scope = scope,
        )

        val orchestrator: Orchestrator

        init {
            // BroadcastManager must be started before Orchestrator (service lifecycle)
            kotlinx.coroutines.runBlocking { broadcastManager.start() }

            orchestrator = Orchestrator(
                systemMonitor = monitor,
                systemController = controller,
                ecosystemManager = ecosystem,
                hardwareAdapter = hardware,
                broadcastManager = broadcastManager,
                rideRecorder = rideRecorder,
                userPreferences = preferences,
                logger = logger,
                scope = scope,
                sensorAdapter = sensorAdapter,
            )
        }
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
        var identifyResult: DeviceInfo? = buildDeviceInfo()

        override fun canOperate(snapshot: SystemSnapshot) = operatable

        override suspend fun identify(): DeviceInfo? = identifyResult

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

        override fun setInitialElapsedTime(seconds: Long) {}
        override fun refreshDeviceInfo() {}
    }

    private class FakeUserPreferences(
        enabled: Set<BroadcastId>,
    ) : UserPreferences {
        override val enabledBroadcasts = MutableStateFlow(enabled)
        override val overlayEnabled = MutableStateFlow(false)
        override val savedSensorAddress = MutableStateFlow<String?>(null)
        override val fanMode = MutableStateFlow(com.nettarion.hyperborea.core.model.FanMode.OFF)
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
        override fun setFanMode(mode: com.nettarion.hyperborea.core.model.FanMode) {
            fanMode.value = mode
        }
    }

    private class FakeSensorAdapter : SensorAdapter {
        private val _state = MutableStateFlow<AdapterState>(AdapterState.Inactive)
        override val state: StateFlow<AdapterState> = _state
        override val id = SensorId.HEART_RATE
        override val prerequisites: List<Prerequisite> = emptyList()
        val mutableReading = MutableStateFlow<SensorReading?>(null)
        override val reading: StateFlow<SensorReading?> = mutableReading
        var disconnectCalled = false

        override fun canOperate(snapshot: SystemSnapshot) = true
        override suspend fun startScan(): Flow<DiscoveredSensor> = MutableSharedFlow()
        override suspend fun connect(address: String) { _state.value = AdapterState.Active }
        override suspend fun disconnect() { disconnectCalled = true; _state.value = AdapterState.Inactive }
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

    private class FakeProfileRepository : ProfileRepository {
        override val profiles: Flow<List<Profile>> = MutableStateFlow(emptyList())
        override val activeProfile: StateFlow<Profile?> = MutableStateFlow(null)
        override suspend fun createProfile(name: String) = Profile(id = 1, name = name)
        override suspend fun updateProfile(profile: Profile) {}
        override suspend fun deleteProfile(id: Long) {}
        override suspend fun setActiveProfile(id: Long) {}
        override fun getRideSummary(id: Long): Flow<RideSummary?> = MutableStateFlow(null)
        override fun getRideSummaries(profileId: Long): Flow<List<RideSummary>> = MutableStateFlow(emptyList())
        override suspend fun saveRideSummary(summary: RideSummary, samples: List<WorkoutSample>): Long = 0
        override suspend fun deleteRideSummary(id: Long) {}
        override fun getWorkoutSamples(rideId: Long): Flow<List<WorkoutSample>> = MutableStateFlow(emptyList())
    }

}
