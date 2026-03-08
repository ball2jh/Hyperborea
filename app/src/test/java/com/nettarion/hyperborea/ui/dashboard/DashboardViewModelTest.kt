package com.nettarion.hyperborea.ui.dashboard

import android.content.Context
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.adapter.AdapterState
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.adapter.BroadcastAdapter
import com.nettarion.hyperborea.core.adapter.BroadcastId
import com.nettarion.hyperborea.core.model.ClientInfo
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.model.DeviceIdentity
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.orchestration.EcosystemManager
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.adapter.HardwareAdapter
import com.nettarion.hyperborea.core.LicenseChecker
import com.nettarion.hyperborea.core.LicenseState
import com.nettarion.hyperborea.core.LinkResult
import com.nettarion.hyperborea.core.orchestration.Orchestrator
import com.nettarion.hyperborea.core.orchestration.OrchestratorState
import com.nettarion.hyperborea.core.orchestration.Prerequisite
import com.nettarion.hyperborea.core.model.Profile
import com.nettarion.hyperborea.core.profile.ProfileRepository
import com.nettarion.hyperborea.core.profile.RideRecorder
import com.nettarion.hyperborea.core.model.RideSummary
import com.nettarion.hyperborea.core.model.WorkoutSample
import com.nettarion.hyperborea.core.system.SystemController
import com.nettarion.hyperborea.core.system.SystemMonitor
import com.nettarion.hyperborea.core.system.SystemSnapshot
import com.nettarion.hyperborea.core.profile.UserPreferences
import com.nettarion.hyperborea.core.test.buildDeviceInfo
import com.nettarion.hyperborea.core.test.buildExerciseData
import com.nettarion.hyperborea.core.test.buildSystemSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DashboardViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeHardware: FakeHardwareAdapter
    private lateinit var fakeSystemMonitor: FakeSystemMonitor
    private lateinit var fakeUserPreferences: FakeUserPreferences
    private lateinit var fakeProfileRepository: FakeProfileRepository
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeHardware = FakeHardwareAdapter()
        fakeSystemMonitor = FakeSystemMonitor()
        fakeUserPreferences = FakeUserPreferences()
        fakeProfileRepository = FakeProfileRepository()
        context = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.createViewModel(
        broadcastAdapters: Set<BroadcastAdapter> = emptySet(),
    ): DashboardViewModel {
        val logger = NoOpLogger()
        val rideRecorder = RideRecorder(fakeProfileRepository, logger, this)
        val orchestrator = Orchestrator(
            systemMonitor = fakeSystemMonitor,
            systemController = FakeSystemController(),
            ecosystemManager = FakeEcosystemManager(),
            hardwareAdapter = fakeHardware,
            broadcastAdapters = broadcastAdapters,
            userPreferences = fakeUserPreferences,
            rideRecorder = rideRecorder,
            logger = logger,
            scope = this,
        )
        return DashboardViewModel(
            orchestrator = orchestrator,
            hardwareAdapter = fakeHardware,
            broadcastAdapters = broadcastAdapters,
            systemMonitor = fakeSystemMonitor,
            userPreferences = fakeUserPreferences,
            profileRepository = fakeProfileRepository,
            licenseChecker = FakeLicenseChecker(),
            context = context,
        )
    }

    @Test
    fun `initial state has idle orchestrator`() = runTest(UnconfinedTestDispatcher()) {
        val vm = createViewModel()

        assertThat(vm.uiState.value.orchestratorState).isEqualTo(OrchestratorState.Idle)
    }

    @Test
    fun `broadcasts sorted by id ordinal`() = runTest(UnconfinedTestDispatcher()) {
        val wftnp = FakeBroadcastAdapter(BroadcastId.WFTNP)
        val ftms = FakeBroadcastAdapter(BroadcastId.FTMS)
        // Pass in reverse order (WFTNP before FTMS)
        val adapters = setOf(wftnp, ftms)

        val vm = createViewModel(broadcastAdapters = adapters)

        vm.uiState.test {
            val state = awaitItem()
            assertThat(state.broadcasts).hasSize(2)
            assertThat(state.broadcasts[0].id).isEqualTo(BroadcastId.FTMS)
            assertThat(state.broadcasts[1].id).isEqualTo(BroadcastId.WFTNP)
        }
    }

    @Test
    fun `broadcast client count reflects connected clients`() = runTest(UnconfinedTestDispatcher()) {
        val ftms = FakeBroadcastAdapter(BroadcastId.FTMS)
        val vm = createViewModel(broadcastAdapters = setOf(ftms))

        vm.uiState.test {
            // Initial: 0 clients
            val initial = awaitItem()
            assertThat(initial.broadcasts.first().clientCount).isEqualTo(0)

            // Add two clients
            ftms.connectedClientsFlow.value = setOf(
                ClientInfo(id = "client-1", protocol = "FTMS", connectedAt = 100L),
                ClientInfo(id = "client-2", protocol = "FTMS", connectedAt = 200L),
            )
            val updated = awaitItem()
            assertThat(updated.broadcasts.first().clientCount).isEqualTo(2)
        }
    }

    @Test
    fun `exercise data flows through to ui state`() = runTest(UnconfinedTestDispatcher()) {
        val vm = createViewModel()

        vm.uiState.test {
            // Initial: null exercise data
            val initial = awaitItem()
            assertThat(initial.exerciseData).isNull()

            // Emit exercise data
            val exercise = buildExerciseData(power = 150, cadence = 80, speed = 25.0f)
            fakeHardware.exerciseDataFlow.value = exercise

            val updated = awaitItem()
            assertThat(updated.exerciseData).isEqualTo(exercise)
            assertThat(updated.exerciseData?.power).isEqualTo(150)
            assertThat(updated.exerciseData?.cadence).isEqualTo(80)
        }
    }

    @Test
    fun `enabled broadcasts flow reflects user preferences`() = runTest(UnconfinedTestDispatcher()) {
        val ftms = FakeBroadcastAdapter(BroadcastId.FTMS)
        val wftnp = FakeBroadcastAdapter(BroadcastId.WFTNP)
        fakeUserPreferences.enabledBroadcastsFlow.value = setOf(BroadcastId.FTMS)

        val vm = createViewModel(broadcastAdapters = setOf(ftms, wftnp))

        vm.uiState.test {
            val initial = awaitItem()
            val ftmsState = initial.broadcasts.first { it.id == BroadcastId.FTMS }
            val wftnpState = initial.broadcasts.first { it.id == BroadcastId.WFTNP }
            assertThat(ftmsState.enabled).isTrue()
            assertThat(wftnpState.enabled).isFalse()

            // Enable WFTNP
            fakeUserPreferences.enabledBroadcastsFlow.value =
                setOf(BroadcastId.FTMS, BroadcastId.WFTNP)

            val updated = awaitItem()
            assertThat(updated.broadcasts.first { it.id == BroadcastId.FTMS }.enabled).isTrue()
            assertThat(updated.broadcasts.first { it.id == BroadcastId.WFTNP }.enabled).isTrue()
        }
    }

    // --- Action methods ---

    @Test
    fun `toggleBroadcast delegates to user preferences`() = runTest(UnconfinedTestDispatcher()) {
        val ftms = FakeBroadcastAdapter(BroadcastId.FTMS)
        val wftnp = FakeBroadcastAdapter(BroadcastId.WFTNP)
        fakeUserPreferences.enabledBroadcastsFlow.value = BroadcastId.entries.toSet()

        val vm = createViewModel(broadcastAdapters = setOf(ftms, wftnp))

        vm.toggleBroadcast(BroadcastId.WFTNP, false)

        assertThat(fakeUserPreferences.enabledBroadcastsFlow.value).doesNotContain(BroadcastId.WFTNP)
        assertThat(fakeUserPreferences.enabledBroadcastsFlow.value).contains(BroadcastId.FTMS)
    }

    @Test
    fun `toggleBroadcast enable adds to preferences`() = runTest(UnconfinedTestDispatcher()) {
        fakeUserPreferences.enabledBroadcastsFlow.value = setOf(BroadcastId.FTMS)

        val vm = createViewModel()
        vm.toggleBroadcast(BroadcastId.WFTNP, true)

        assertThat(fakeUserPreferences.enabledBroadcastsFlow.value).contains(BroadcastId.WFTNP)
    }

    // --- Hardware state ---

    @Test
    fun `hardware state flows to ui state`() = runTest(UnconfinedTestDispatcher()) {
        val vm = createViewModel()

        vm.uiState.test {
            val initial = awaitItem()
            assertThat(initial.hardwareState).isEqualTo(AdapterState.Inactive)

            fakeHardware.stateFlow.value = AdapterState.Active
            val updated = awaitItem()
            assertThat(updated.hardwareState).isEqualTo(AdapterState.Active)
        }
    }

    // --- Profile name ---

    @Test
    fun `profile name flows from repository`() = runTest(UnconfinedTestDispatcher()) {
        val vm = createViewModel()

        vm.uiState.test {
            val initial = awaitItem()
            assertThat(initial.profileName).isNull()

            fakeProfileRepository.activeProfile.value = Profile(id = 1, name = "Alice")
            val updated = awaitItem()
            assertThat(updated.profileName).isEqualTo("Alice")
        }
    }

    // --- Active profile id ---

    @Test
    fun `activeProfileId returns null when no active profile`() = runTest(UnconfinedTestDispatcher()) {
        val vm = createViewModel()
        assertThat(vm.activeProfileId).isNull()
    }

    @Test
    fun `activeProfileId returns profile id when set`() = runTest(UnconfinedTestDispatcher()) {
        fakeProfileRepository.activeProfile.value = Profile(id = 42, name = "Bob")
        val vm = createViewModel()
        assertThat(vm.activeProfileId).isEqualTo(42L)
    }

    // --- currentElapsedSeconds ---

    @Test
    fun `currentElapsedSeconds returns 0 when no exercise data`() = runTest(UnconfinedTestDispatcher()) {
        val vm = createViewModel()
        assertThat(vm.currentElapsedSeconds).isEqualTo(0L)
    }

    @Test
    fun `currentElapsedSeconds returns elapsed time from exercise data`() = runTest(UnconfinedTestDispatcher()) {
        val vm = createViewModel()
        fakeHardware.exerciseDataFlow.value = buildExerciseData(elapsedTime = 300L)

        vm.uiState.test {
            awaitItem() // wait for update
            assertThat(vm.currentElapsedSeconds).isEqualTo(300L)
        }
    }

    // --- Device info ---

    @Test
    fun `device info flows to ui state`() = runTest(UnconfinedTestDispatcher()) {
        val vm = createViewModel()

        vm.uiState.test {
            val initial = awaitItem()
            assertThat(initial.deviceInfo).isNull()

            val info = com.nettarion.hyperborea.core.test.buildDeviceInfo(name = "S22i")
            fakeHardware.deviceInfoFlow.value = info
            val updated = awaitItem()
            assertThat(updated.deviceInfo).isEqualTo(info)
        }
    }

    // --- Broadcast state ---

    @Test
    fun `broadcast state reflects adapter state`() = runTest(UnconfinedTestDispatcher()) {
        val ftms = FakeBroadcastAdapter(BroadcastId.FTMS)
        val vm = createViewModel(broadcastAdapters = setOf(ftms))

        vm.uiState.test {
            val initial = awaitItem()
            assertThat(initial.broadcasts.first().state).isEqualTo(AdapterState.Inactive)

            ftms.stateFlow.value = AdapterState.Active
            val updated = awaitItem()
            assertThat(updated.broadcasts.first().state).isEqualTo(AdapterState.Active)
        }
    }

    // --- Fakes ---

    private class FakeHardwareAdapter : HardwareAdapter {
        override val prerequisites: List<Prerequisite> = emptyList()
        val stateFlow = MutableStateFlow<AdapterState>(AdapterState.Inactive)
        override val state: MutableStateFlow<AdapterState> = stateFlow
        val exerciseDataFlow = MutableStateFlow<ExerciseData?>(null)
        override val exerciseData: MutableStateFlow<ExerciseData?> = exerciseDataFlow
        val deviceInfoFlow = MutableStateFlow<DeviceInfo?>(null)
        override val deviceInfo: MutableStateFlow<DeviceInfo?> = deviceInfoFlow
        val deviceIdentityFlow = MutableStateFlow<DeviceIdentity?>(null)
        override val deviceIdentity: MutableStateFlow<DeviceIdentity?> = deviceIdentityFlow
        override fun canOperate(snapshot: SystemSnapshot): Boolean = true
        override suspend fun connect() {}
        override suspend fun disconnect() {}
        override suspend fun sendCommand(command: DeviceCommand) {}
        override fun setInitialElapsedTime(seconds: Long) {}
    }

    private class FakeBroadcastAdapter(
        override val id: BroadcastId,
    ) : BroadcastAdapter {
        override val prerequisites: List<Prerequisite> = emptyList()
        val stateFlow = MutableStateFlow<AdapterState>(AdapterState.Inactive)
        override val state: MutableStateFlow<AdapterState> = stateFlow
        val connectedClientsFlow = MutableStateFlow<Set<ClientInfo>>(emptySet())
        override val connectedClients: MutableStateFlow<Set<ClientInfo>> = connectedClientsFlow
        override val incomingCommands: Flow<DeviceCommand> = emptyFlow()
        override fun canOperate(snapshot: SystemSnapshot): Boolean = true
        override suspend fun start(dataSource: Flow<ExerciseData>, deviceInfo: DeviceInfo) {}
        override suspend fun stop() {}
    }

    private class FakeSystemMonitor : SystemMonitor {
        val snapshotFlow = MutableStateFlow(buildSystemSnapshot())
        override val snapshot: MutableStateFlow<SystemSnapshot> = snapshotFlow
        override suspend fun refresh() {
            snapshotFlow.value = snapshotFlow.value.copy(timestamp = snapshotFlow.value.timestamp + 1)
        }
    }

    private class FakeUserPreferences : UserPreferences {
        val enabledBroadcastsFlow = MutableStateFlow<Set<BroadcastId>>(BroadcastId.entries.toSet())
        override val enabledBroadcasts: MutableStateFlow<Set<BroadcastId>> = enabledBroadcastsFlow
        override fun setBroadcastEnabled(id: BroadcastId, enabled: Boolean) {
            val current = enabledBroadcastsFlow.value.toMutableSet()
            if (enabled) current.add(id) else current.remove(id)
            enabledBroadcastsFlow.value = current
        }
    }

    private class FakeSystemController : SystemController {
        override suspend fun stopService(packageName: String, className: String) = true
        override suspend fun forceStopPackage(packageName: String) = true
        override suspend fun disablePackage(packageName: String) = true
        override suspend fun enablePackage(packageName: String) = true
        override suspend fun uninstallPackage(packageName: String) = true
        override suspend fun disableComponent(packageName: String, className: String) = true
        override suspend fun enableComponent(packageName: String, className: String) = true
        override suspend fun grantUsbPermission(packageName: String) = true
        override suspend fun revokeUsbPermissions(packageName: String) = true
    }

    private class FakeEcosystemManager : EcosystemManager {
        override val prerequisites: List<Prerequisite> = emptyList()
    }

    private class FakeLicenseChecker : LicenseChecker {
        override val state = MutableStateFlow<LicenseState>(LicenseState.Licensed(Long.MAX_VALUE))
        override suspend fun check() {}
        override suspend fun linkWithCode(code: String) = LinkResult.Success("test-token")
        override suspend fun linkWithQrToken(qrToken: String) = LinkResult.Success("test-token")
    }

    private class FakeProfileRepository : ProfileRepository {
        override val profiles: Flow<List<Profile>> = MutableStateFlow(emptyList())
        override val activeProfile: MutableStateFlow<Profile?> = MutableStateFlow(null)
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
