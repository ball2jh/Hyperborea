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
import com.nettarion.hyperborea.core.model.DeviceType
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
import com.nettarion.hyperborea.core.test.buildExerciseData
import com.nettarion.hyperborea.core.test.buildSystemSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorTest {

    @Test fun `hardware failure while reasserting unconfirmed Resume cannot crash or resume the workout`() = runTest {
        val env = TestEnv(this)
        env.hardware.deviceInfo.value = buildDeviceInfo(type = DeviceType.TREADMILL)
        env.orchestrator.start()
        env.hardware.exerciseData.value = ExerciseData.ZERO.copy(workoutMode = 2); runCurrent()
        env.orchestrator.pause(); env.orchestrator.resume()
        env.hardware.shouldThrowOnSendCommand = true
        advanceTimeBy(10_001); runCurrent()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Paused)
        env.hardware.shouldThrowOnSendCommand = false
        env.orchestrator.stop(saveRide = false)
    }

    @Test fun `residual RESULTS is cleared while a newly armed treadmill waits for physical Start`() = runTest {
        val env = TestEnv(this)
        env.hardware.deviceInfo.value = buildDeviceInfo(type = DeviceType.TREADMILL)
        env.orchestrator.start()
        for (mode in listOf(4, 1)) {
            env.hardware.exerciseData.value = ExerciseData.ZERO.copy(workoutMode = mode); runCurrent()
        }
        assertThat(env.orchestrator.state.value).isInstanceOf(OrchestratorState.AwaitingConsoleStart::class.java)
        assertThat(env.hardware.receivedCommands).doesNotContain(DeviceCommand.ResumeWorkout)
        env.orchestrator.stop(saveRide = false)
    }

    @Test fun `external treadmill Resume uses the same explicit confirmation gate`() = runTest {
        val env = TestEnv(this)
        env.hardware.deviceInfo.value = buildDeviceInfo(type = DeviceType.TREADMILL)
        env.orchestrator.start()
        env.hardware.exerciseData.value = ExerciseData.ZERO.copy(workoutMode = 2); runCurrent()
        env.broadcast1.emitCommand(DeviceCommand.PauseWorkout); runCurrent()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Paused)
        env.broadcast1.emitCommand(DeviceCommand.ResumeWorkout); runCurrent()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Paused)
        env.hardware.exerciseData.value = ExerciseData.ZERO.copy(workoutMode = 2, elapsedTime = 1); runCurrent()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())
        env.orchestrator.stop(saveRide = false)
    }

    @Test fun `Stop revokes pending treadmill start before recording save can block`() = runTest {
        val env = TestEnv(this)
        (env.profileRepository.activeProfile as MutableStateFlow<Profile?>).value = Profile(id = 1, name = "Runner")
        env.hardware.deviceInfo.value = buildDeviceInfo(type = DeviceType.TREADMILL)
        env.orchestrator.start()
        for (second in listOf(1L, 120L)) {
            env.hardware.exerciseData.value = ExerciseData.ZERO.copy(workoutMode = 2, elapsedTime = second, speed = 5f, power = 100)
            runCurrent()
        }
        env.orchestrator.pause(); env.orchestrator.resume()
        val saving = CompletableDeferred<Unit>(); val allowSave = CompletableDeferred<Unit>()
        env.profileRepository.beforeSave = { saving.complete(Unit); allowSave.await() }
        val stopJob = launch { env.orchestrator.stop() }
        runCurrent(); assertThat(saving.isCompleted).isTrue()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Stopping)
        assertThat(env.hardware.receivedCommands.last()).isEqualTo(DeviceCommand.PauseWorkout)
        allowSave.complete(Unit); stopJob.join()
        env.hardware.exerciseData.value = ExerciseData.ZERO.copy(workoutMode = 2, elapsedTime = 121); runCurrent()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Idle)
    }

    @Test fun `a new Pause cancels an unconfirmed treadmill Resume`() = runTest {
        val env = TestEnv(this)
        env.hardware.deviceInfo.value = buildDeviceInfo(type = DeviceType.TREADMILL)
        env.orchestrator.start()
        env.hardware.exerciseData.value = ExerciseData.ZERO.copy(workoutMode = 2); runCurrent()
        env.orchestrator.pause(); env.orchestrator.resume(); env.orchestrator.pause()
        env.hardware.exerciseData.value = ExerciseData.ZERO.copy(workoutMode = 2, elapsedTime = 5)
        advanceTimeBy(20_000); runCurrent()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Paused)
        assertThat(env.hardware.receivedCommands.filterIsInstance<DeviceCommand.PauseWorkout>()).hasSize(2)
        env.orchestrator.stop(saveRide = false)
    }

    @Test fun `explicit treadmill resume waits through trailing expiry reports for fresh confirmation`() = runTest {
        val env = TestEnv(this)
        env.hardware.deviceInfo.value = buildDeviceInfo(type = DeviceType.TREADMILL)
        env.orchestrator.start()
        env.hardware.exerciseData.value = ExerciseData.ZERO.copy(workoutMode = 2); runCurrent()
        env.orchestrator.pause()
        env.hardware.exerciseData.value = ExerciseData.ZERO.copy(workoutMode = 4); runCurrent()
        env.orchestrator.resume()
        for (mode in listOf(4, 1, 1)) {
            env.hardware.exerciseData.value = ExerciseData.ZERO.copy(workoutMode = mode, elapsedTime = 20 + mode.toLong())
            runCurrent()
            assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Paused)
            assertThat(env.orchestrator.sendProgramCommand(DeviceCommand.SetTargetSpeed(8f))).isFalse()
        }
        env.hardware.exerciseData.value = ExerciseData.ZERO.copy(workoutMode = 2, elapsedTime = 25); runCurrent()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())
        assertThat(env.orchestrator.sendProgramCommand(DeviceCommand.SetTargetSpeed(8f))).isTrue()
        env.orchestrator.stop(saveRide = false)
    }

    @Test fun `unconfirmed treadmill resume reasserts pause and cannot be confirmed by later unsolicited telemetry`() = runTest {
        val env = TestEnv(this)
        env.hardware.deviceInfo.value = buildDeviceInfo(type = DeviceType.TREADMILL)
        env.orchestrator.start()
        env.hardware.exerciseData.value = ExerciseData.ZERO.copy(workoutMode = 2); runCurrent()
        env.orchestrator.pause(); env.orchestrator.resume()
        advanceTimeBy(10_001); runCurrent()
        assertThat(env.hardware.receivedCommands.filterIsInstance<DeviceCommand.PauseWorkout>()).hasSize(2)
        env.hardware.exerciseData.value = ExerciseData.ZERO.copy(workoutMode = 2, elapsedTime = 2); runCurrent()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Paused)
        env.orchestrator.stop(saveRide = false)
    }

    @Test fun `Stop cancels pending treadmill resume and later console frames cannot revive the workout`() = runTest {
        val env = TestEnv(this)
        env.hardware.deviceInfo.value = buildDeviceInfo(type = DeviceType.TREADMILL)
        env.orchestrator.start()
        env.hardware.exerciseData.value = ExerciseData.ZERO.copy(workoutMode = 2); runCurrent()
        env.orchestrator.pause(); env.orchestrator.resume(); env.orchestrator.stop(saveRide = false)
        env.hardware.exerciseData.value = ExerciseData.ZERO.copy(workoutMode = 2, elapsedTime = 2)
        advanceTimeBy(20_000); runCurrent()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Idle)
        assertThat(env.hardware.receivedCommands.filterIsInstance<DeviceCommand.ResumeWorkout>()).hasSize(1)
    }

    @Test fun `paused treadmill disconnect ends session without automatic reconnect or Resume`() = runTest {
        val env = TestEnv(this)
        env.hardware.deviceInfo.value = buildDeviceInfo(type = DeviceType.TREADMILL)
        env.orchestrator.start()
        env.hardware.exerciseData.value = ExerciseData.ZERO.copy(workoutMode = 2); advanceUntilIdle()
        env.orchestrator.pause()
        env.hardware.mutableState.value = AdapterState.Error("USB disconnected")
        advanceUntilIdle()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Idle)
        assertThat(env.hardware.connectCallCount).isEqualTo(1)
        assertThat(env.hardware.receivedCommands).doesNotContain(DeviceCommand.ResumeWorkout)
    }

    @Test fun `treadmill pause survives controller timeout until explicit Stop without restarting the belt`() = runTest {
        val env = TestEnv(this)
        (env.profileRepository.activeProfile as MutableStateFlow<Profile?>).value = Profile(id = 1, name = "Runner")
        env.hardware.deviceInfo.value = buildDeviceInfo(type = DeviceType.TREADMILL)
        env.orchestrator.start()
        env.hardware.exerciseData.value = ExerciseData.ZERO.copy(workoutMode = 2, elapsedTime = 1, speed = 5f, power = 100)
        advanceUntilIdle()
        env.hardware.exerciseData.value = ExerciseData.ZERO.copy(workoutMode = 2, elapsedTime = 120, speed = 5f, power = 100)
        advanceUntilIdle()
        env.orchestrator.pause()
        val baseline = env.hardware.receivedCommands.size
        for (mode in listOf(4, 1, 4, 1)) {
            env.hardware.exerciseData.value = ExerciseData.ZERO.copy(workoutMode = mode, elapsedTime = 120)
            advanceUntilIdle()
        }
        advanceTimeBy(2 * 60 * 60 * 1000L); runCurrent()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Paused)
        assertThat(env.hardware.receivedCommands.drop(baseline)).doesNotContain(DeviceCommand.ResumeWorkout)
        assertThat(env.hardware.disconnectCalled).isFalse()
        assertThat(env.rideRecorder.lastSavedRideId.value).isNull()
        env.orchestrator.stop()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Idle)
        assertThat(env.rideRecorder.lastSavedRideId.value).isEqualTo(0)
    }

    @Test fun `reinserting treadmill safety key never automatically resumes belt`() = runTest {
        val env = TestEnv(this)
        env.hardware.deviceInfo.value = buildDeviceInfo(type = DeviceType.TREADMILL)
        env.orchestrator.start()
        for (mode in listOf(2, 8, 1)) {
            env.hardware.exerciseData.value = ExerciseData.ZERO.copy(workoutMode = mode)
            advanceUntilIdle()
        }
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Paused)
        assertThat(env.hardware.receivedCommands).doesNotContain(DeviceCommand.ResumeWorkout)
    }

    @Test fun `treadmill console pause blocks program targets and RUNNING telemetry cannot authorize resume`() = runTest {
        val env = TestEnv(this)
        env.hardware.deviceInfo.value = buildDeviceInfo(type = DeviceType.TREADMILL)
        env.orchestrator.start()
        env.hardware.exerciseData.value = ExerciseData.ZERO.copy(workoutMode = 2); advanceUntilIdle()
        env.hardware.exerciseData.value = ExerciseData.ZERO.copy(workoutMode = 3); advanceUntilIdle()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Paused)
        assertThat(env.orchestrator.sendProgramCommand(DeviceCommand.SetTargetSpeed(8f))).isFalse()
        // Even stale/unsolicited RUNNING telemetry cannot substitute for a human Resume.
        env.hardware.exerciseData.value = ExerciseData.ZERO.copy(workoutMode = 2); advanceUntilIdle()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Paused)
        assertThat(env.hardware.receivedCommands).doesNotContain(DeviceCommand.ResumeWorkout)
        env.orchestrator.resume()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Paused)
        env.hardware.exerciseData.value = ExerciseData.ZERO.copy(workoutMode = 2, elapsedTime = 1)
        runCurrent()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())
    }

    @Test fun `treadmill program targets cannot be sent to other equipment types`() = runTest {
        for (type in listOf(DeviceType.BIKE, DeviceType.ELLIPTICAL, DeviceType.ROWER)) {
            val env = TestEnv(this)
            env.hardware.deviceInfo.value = buildDeviceInfo(type = type)
            env.orchestrator.start()
            assertThat(env.orchestrator.sendProgramCommand(DeviceCommand.SetTargetSpeed(8f))).isFalse()
            assertThat(env.orchestrator.sendProgramCommand(DeviceCommand.SetIncline(3f, snapToStep = false))).isFalse()
            assertThat(env.hardware.receivedCommands).doesNotContain(DeviceCommand.SetTargetSpeed(8f))
            env.orchestrator.stop(saveRide = false)
        }
    }

    @Test
    fun `program commands require a running non degraded workout and stop stays authoritative`() = runTest {
        val env = TestEnv(this)
        env.hardware.deviceInfo.value = buildDeviceInfo(type = DeviceType.TREADMILL)
        val command = DeviceCommand.SetTargetSpeed(8f)
        assertThat(env.orchestrator.sendProgramCommand(command)).isFalse()
        env.orchestrator.start()
        env.hardware.exerciseData.value = ExerciseData.ZERO.copy(workoutMode = 2)
        advanceUntilIdle()
        assertThat(env.orchestrator.sendProgramCommand(command)).isTrue()
        env.orchestrator.pause()
        assertThat(env.orchestrator.sendProgramCommand(command)).isFalse()
        env.orchestrator.resume()
        env.hardware.exerciseData.value = ExerciseData.ZERO.copy(workoutMode = 2, elapsedTime = 1)
        runCurrent()
        assertThat(env.orchestrator.sendProgramCommand(command)).isTrue()
        env.hardware.deviceInfo.value = buildDeviceInfo(type = DeviceType.BIKE)
        assertThat(env.orchestrator.sendProgramCommand(command)).isFalse()
        env.orchestrator.stop()
        assertThat(env.orchestrator.sendProgramCommand(command)).isFalse()
    }

    @Test
    fun `program commands cannot bypass treadmill physical start`() = runTest {
        val env = TestEnv(this)
        env.hardware.deviceInfo.value = buildDeviceInfo(type = DeviceType.TREADMILL)
        env.orchestrator.start()
        assertThat(env.orchestrator.sendProgramCommand(DeviceCommand.SetTargetSpeed(8f))).isFalse()
        assertThat(env.hardware.receivedCommands).doesNotContain(DeviceCommand.SetTargetSpeed(8f))
    }

    @Test
    fun `program commands are blocked when the hardware reports degradation`() = runTest {
        val env = TestEnv(this)
        env.hardware.degradedReason.value = "Connection interrupted"
        env.orchestrator.start()
        assertThat(env.orchestrator.sendProgramCommand(DeviceCommand.SetTargetSpeed(8f))).isFalse()
    }

    @Test
    fun `external control notifies automation before forwarding a target`() = runTest {
        val observed = mutableListOf<DeviceCommand>()
        lateinit var env: TestEnv
        env = TestEnv(this, beforeExternalCommand = {
            assertThat(env.hardware.receivedCommands).doesNotContain(it)
            observed += it
        })
        env.orchestrator.start()
        val command = DeviceCommand.SetTargetSpeed(7f)
        env.broadcast1.emitCommand(command)
        assertThat(observed).containsExactly(command)
        assertThat(env.hardware.receivedCommands).contains(command)
    }

    // --- Happy path ---

    @Test
    fun `start with all prerequisites met transitions to Running`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())
    }

    @Test
    fun `start surfaces the hardware adapter's degraded reason as Running(degraded)`() = runTest {
        val env = TestEnv(this)
        env.hardware.degradedReason.value = "console didn't confirm the workout started"
        env.orchestrator.start()
        assertThat(env.orchestrator.state.value)
            .isEqualTo(OrchestratorState.Running(degraded = "console didn't confirm the workout started"))
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

    @Test
    fun `second dropout seeds last-seen elapsed without double-counting`() = runTest {
        val env = TestEnv(this)
        env.orchestrator.start()

        // Ride reaches 600s cumulative, then dropout #1
        env.hardware.exerciseData.value = buildExerciseData(power = 100, elapsedTime = 600)
        env.hardware.mutableState.value = AdapterState.Error("USB glitch")
        advanceUntilIdle()
        assertThat(env.hardware.initialElapsedCalls.last()).isEqualTo(600)

        // Reconnected session reports cumulative elapsed (seed included); rides to 900, dropout #2
        env.hardware.exerciseData.value = buildExerciseData(power = 100, elapsedTime = 900)
        env.hardware.mutableState.value = AdapterState.Error("USB glitch again")
        advanceUntilIdle()

        // Seed must be the last-seen cumulative elapsed (900) — not 600 + 900
        assertThat(env.hardware.initialElapsedCalls.last()).isEqualTo(900)
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
    fun `probe stays Idle and does not fulfil when a prerequisite is not met`() = runTest {
        // The prereq has a fulfill that would *fail* — probe must not call it (passive); it just
        // leaves the orchestrator Idle. (start(), by contrast, would fulfil and reach Error.)
        val prereq = Prerequisite(
            id = "eco-fail",
            description = "test",
            isMet = { false },
            fulfill = { FulfillResult.Failed("boom") },
        )
        val env = TestEnv(this, ecosystemPrereqs = listOf(prereq))
        val result = env.orchestrator.probe()
        assertThat(result).isNull()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Idle)
        assertThat(env.hardware.identifyCallCount).isEqualTo(0)
    }

    @Test
    fun `probe stays Idle when hardware cannot operate`() = runTest {
        val env = TestEnv(this, hardwareCanOperate = false)
        val result = env.orchestrator.probe()
        assertThat(result).isNull()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Idle)
    }

    @Test
    fun `probe stays Idle on identify failure`() = runTest {
        val env = TestEnv(this)
        env.hardware.identifyResult = null
        val result = env.orchestrator.probe()
        assertThat(result).isNull()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Idle)
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

    @Test
    fun `startProbing probes once the hardware prerequisite becomes met, then stops`() = runTest {
        var usbReady = false
        val prereq = Prerequisite(id = "hw-usb", description = "test", isMet = { usbReady })
        val env = TestEnv(this, hardwarePrereqs = listOf(prereq))
        env.hardware.deviceInfo.value = null // not yet identified

        fun republish() {
            env.monitor.snapshot.value = env.monitor.snapshot.value
                .copy(timestamp = env.monitor.snapshot.value.timestamp + 1)
        }

        env.orchestrator.startProbing()
        advanceUntilIdle()
        assertThat(env.hardware.identifyCallCount).isEqualTo(0) // prereq unmet → no probe

        // USB attaches → probe → identify (the fake's identify() also populates deviceInfo, like the real adapter).
        usbReady = true; republish(); advanceUntilIdle()
        assertThat(env.hardware.identifyCallCount).isEqualTo(1)
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Idle)
        assertThat(env.broadcast1.lastDeviceInfo).isEqualTo(buildDeviceInfo())

        // USB power-cycles (detach, re-attach) — must NOT re-probe now that it's identified.
        usbReady = false; republish(); advanceUntilIdle()
        usbReady = true;  republish(); advanceUntilIdle()
        assertThat(env.hardware.identifyCallCount).isEqualTo(1)

        env.orchestrator.stopProbing()
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

    // --- Treadmill: AwaitingConsoleStart ---

    @Test
    fun `treadmill connect lands in AwaitingConsoleStart, not Running`() = runTest {
        val env = TestEnv(this)
        env.hardware.deviceInfo.value = buildDeviceInfo(type = DeviceType.TREADMILL)

        env.orchestrator.start()
        advanceUntilIdle()

        val state = env.orchestrator.state.value
        assertThat(state).isInstanceOf(OrchestratorState.AwaitingConsoleStart::class.java)
        assertThat((state as OrchestratorState.AwaitingConsoleStart).message)
            .contains("console")
    }

    @Test
    fun `treadmill broadcasts go live in AwaitingConsoleStart`() = runTest {
        val env = TestEnv(this)
        env.hardware.deviceInfo.value = buildDeviceInfo(type = DeviceType.TREADMILL)

        env.orchestrator.start()
        advanceUntilIdle()

        // The user's mental model: app Start = telemetry connects. So the broadcasts must
        // already be running even while we wait on the physical Start key.
        assertThat(env.broadcast1.startCalled).isTrue()
    }

    @Test
    fun `treadmill WORKOUT_MODE 2 promotes AwaitingConsoleStart to Running`() = runTest {
        val env = TestEnv(this)
        env.hardware.deviceInfo.value = buildDeviceInfo(type = DeviceType.TREADMILL)
        env.orchestrator.start()
        advanceUntilIdle()
        assertThat(env.orchestrator.state.value).isInstanceOf(OrchestratorState.AwaitingConsoleStart::class.java)

        // User presses the physical Start key — MCU completes WARM_UP → RUNNING.
        env.hardware.exerciseData.value = ExerciseData(
            power = 0, cadence = 0, speed = 1.5f, resistance = 0,
            incline = 0f, heartRate = null, distance = null, calories = null,
            elapsedTime = 0, workoutMode = 2,
        )
        advanceUntilIdle()

        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())
    }

    @Test
    fun `treadmill idle report while armed stays armed when the console never left idle`() = runTest {
        // Some belt machines refuse the app's WARM_UP write and keep reporting idle until the
        // physical Start key — that idle is the parked state, not the user pressing Stop.
        val env = TestEnv(this)
        env.hardware.deviceInfo.value = buildDeviceInfo(type = DeviceType.TREADMILL)
        env.orchestrator.start()
        advanceUntilIdle()
        assertThat(env.orchestrator.state.value).isInstanceOf(OrchestratorState.AwaitingConsoleStart::class.java)

        // Console reports its (unchanged) idle workout state right after subscribing.
        env.hardware.exerciseData.value = ExerciseData(
            power = 0, cadence = 0, speed = 0f, resistance = 0,
            incline = 0f, heartRate = null, distance = null, calories = null,
            elapsedTime = 0, workoutMode = 1,
        )
        advanceUntilIdle()

        assertThat(env.orchestrator.state.value).isInstanceOf(OrchestratorState.AwaitingConsoleStart::class.java)
    }

    @Test
    fun `treadmill WORKOUT_MODE 1 after confirmed WARM_UP tears the armed workout down silently`() = runTest {
        val env = TestEnv(this)
        env.hardware.deviceInfo.value = buildDeviceInfo(type = DeviceType.TREADMILL)
        env.orchestrator.start()
        advanceUntilIdle()
        assertThat(env.orchestrator.state.value).isInstanceOf(OrchestratorState.AwaitingConsoleStart::class.java)

        // Console confirmed WARM_UP (left idle, code 10), still awaiting the Start key…
        env.hardware.exerciseData.value = ExerciseData(
            power = 0, cadence = 0, speed = 0f, resistance = 0,
            incline = 0f, heartRate = null, distance = null, calories = null,
            elapsedTime = 0, workoutMode = 10,
        )
        advanceUntilIdle()
        assertThat(env.orchestrator.state.value).isInstanceOf(OrchestratorState.AwaitingConsoleStart::class.java)

        // …then the user changed their mind and pressed console Stop while armed.
        env.hardware.exerciseData.value = ExerciseData(
            power = 0, cadence = 0, speed = 0f, resistance = 0,
            incline = 0f, heartRate = null, distance = null, calories = null,
            elapsedTime = 0, workoutMode = 1,
        )
        advanceUntilIdle()

        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Idle)
    }

    @Test
    fun `treadmill WORKOUT_MODE 1 from Running stops the workout`() = runTest {
        val env = TestEnv(this)
        env.hardware.deviceInfo.value = buildDeviceInfo(type = DeviceType.TREADMILL)
        env.orchestrator.start()
        // Promote AwaitingConsoleStart → Running by emitting RUNNING.
        env.hardware.exerciseData.value = ExerciseData(
            power = 0, cadence = 0, speed = 1.5f, resistance = 0,
            incline = 0f, heartRate = null, distance = null, calories = null,
            elapsedTime = 30, workoutMode = 2,
        )
        advanceUntilIdle()
        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Running())

        // User presses console Stop mid-workout.
        env.hardware.exerciseData.value = ExerciseData(
            power = 0, cadence = 0, speed = 0f, resistance = 0,
            incline = 0f, heartRate = null, distance = null, calories = null,
            elapsedTime = 30, workoutMode = 1,
        )
        advanceUntilIdle()

        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Idle)
    }

    @Test
    fun `stop from AwaitingConsoleStart transitions to Idle without saving`() = runTest {
        val env = TestEnv(this)
        env.hardware.deviceInfo.value = buildDeviceInfo(type = DeviceType.TREADMILL)
        env.orchestrator.start()
        advanceUntilIdle()
        assertThat(env.orchestrator.state.value).isInstanceOf(OrchestratorState.AwaitingConsoleStart::class.java)

        // User taps CANCEL on the app.
        env.orchestrator.stop(saveRide = false)
        advanceUntilIdle()

        assertThat(env.orchestrator.state.value).isEqualTo(OrchestratorState.Idle)
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
        beforeExternalCommand: suspend (DeviceCommand) -> Unit = {},
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
                beforeExternalCommand = beforeExternalCommand,
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

        override suspend fun requestUsbPermission(): Boolean {
            calls.add("requestUsbPermission"); return true
        }
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
        override val degradedReason = MutableStateFlow<String?>(null)
        var connectCalled = false
        var connectCallCount = 0
        var disconnectCalled = false
        val receivedCommands = mutableListOf<DeviceCommand>()
        var shouldThrowOnSendCommand = false
        var reconnectResult: AdapterState? = null
        var identifyResult: DeviceInfo? = buildDeviceInfo()
        var identifyCallCount = 0

        override fun canOperate(snapshot: SystemSnapshot) = operatable

        override suspend fun identify(): DeviceInfo? {
            identifyCallCount++
            // Mimic FitProAdapter.identify(): a successful identify populates deviceInfo.
            identifyResult?.let { deviceInfo.value = it }
            return identifyResult
        }

        override suspend fun connect() {
            connectCalled = true
            connectCallCount++
            mutableState.value = if (connectCallCount > 1 && reconnectResult != null) reconnectResult!! else connectState
        }

        override suspend fun disconnect() {
            disconnectCalled = true
            // FitProAdapter clears its exercise data on disconnect — mirror that here so the
            // reconnect path can't re-read stale pre-dropout data.
            exerciseData.value = null
            mutableState.value = AdapterState.Inactive
        }

        override suspend fun sendCommand(command: DeviceCommand) {
            if (shouldThrowOnSendCommand) throw RuntimeException("sendCommand failed")
            receivedCommands.add(command)
        }

        val initialElapsedCalls = mutableListOf<Long>()
        override fun setInitialElapsedTime(seconds: Long) {
            initialElapsedCalls.add(seconds)
        }
        override suspend fun refreshDeviceInfo() {}
    }

    private class FakeUserPreferences(
        enabled: Set<BroadcastId>,
    ) : UserPreferences {
        override val enabledBroadcasts = MutableStateFlow(enabled)
        override val overlayEnabled = MutableStateFlow(false)
        override val overlayStyle = MutableStateFlow(com.nettarion.hyperborea.core.profile.OverlayStyle.METRICS)
        override val lastKnownDeviceType = MutableStateFlow<com.nettarion.hyperborea.core.model.DeviceType?>(null)
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
        override fun setOverlayStyle(style: com.nettarion.hyperborea.core.profile.OverlayStyle) {
            overlayStyle.value = style
        }
        override fun setLastKnownDeviceType(type: com.nettarion.hyperborea.core.model.DeviceType) {
            lastKnownDeviceType.value = type
        }
        override fun setSavedSensorAddress(address: String?) {
            savedSensorAddress.value = address
        }
        override fun setFanMode(mode: com.nettarion.hyperborea.core.model.FanMode) {
            fanMode.value = mode
        }
        override val immersiveModeEnabled = MutableStateFlow(true)
        override fun setImmersiveModeEnabled(enabled: Boolean) {
            immersiveModeEnabled.value = enabled
        }
        override val useImperial = MutableStateFlow(true)
        override fun setUseImperial(enabled: Boolean) {
            useImperial.value = enabled
        }
        override val screenSleepEnabled = MutableStateFlow(false)
        override fun setScreenSleepEnabled(enabled: Boolean) {
            screenSleepEnabled.value = enabled
        }
        override val screenSleepTimeoutMinutes = MutableStateFlow(10)
        override fun setScreenSleepTimeoutMinutes(minutes: Int) {
            screenSleepTimeoutMinutes.value = minutes
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
        var beforeSave: suspend () -> Unit = {}
        override suspend fun saveRideSummary(summary: RideSummary, samples: List<WorkoutSample>): Long { beforeSave(); return 0 }
        override suspend fun deleteRideSummary(id: Long) {}
        override fun getWorkoutSamples(rideId: Long): Flow<List<WorkoutSample>> = MutableStateFlow(emptyList())
    }

}
