package com.nettarion.hyperborea.ui.dashboard

import android.content.ContextWrapper
import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.MainDispatcherRule
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.adapter.AdapterState
import com.nettarion.hyperborea.core.adapter.BroadcastAdapter
import com.nettarion.hyperborea.core.adapter.BroadcastId
import com.nettarion.hyperborea.core.adapter.HardwareAdapter
import com.nettarion.hyperborea.core.model.ClientInfo
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.model.DeviceIdentity
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.model.Profile
import com.nettarion.hyperborea.core.model.RideSummary
import com.nettarion.hyperborea.core.model.WorkoutSample
import com.nettarion.hyperborea.core.orchestration.EcosystemManager
import com.nettarion.hyperborea.core.orchestration.Orchestrator
import com.nettarion.hyperborea.core.orchestration.OrchestratorState
import com.nettarion.hyperborea.core.orchestration.Prerequisite
import com.nettarion.hyperborea.core.profile.ProfileRepository
import com.nettarion.hyperborea.core.profile.RideRecorder
import com.nettarion.hyperborea.core.profile.UserPreferences
import com.nettarion.hyperborea.core.system.SystemController
import com.nettarion.hyperborea.core.system.SystemMonitor
import com.nettarion.hyperborea.core.system.SystemSnapshot
import com.nettarion.hyperborea.core.test.buildExerciseData
import com.nettarion.hyperborea.core.test.buildSystemSnapshot
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timeout: Timeout = Timeout(45, TimeUnit.SECONDS)

    // Fakes
    private val hardwareState = MutableStateFlow<AdapterState>(AdapterState.Inactive)
    private val exerciseData = MutableStateFlow<ExerciseData?>(null)
    private val deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    private val deviceIdentity = MutableStateFlow<DeviceIdentity?>(null)

    private val systemSnapshot = MutableStateFlow(buildSystemSnapshot())
    private val enabledBroadcasts = MutableStateFlow<Set<BroadcastId>>(emptySet())
    private val activeProfile = MutableStateFlow<Profile?>(null)

    private val toggledBroadcasts = mutableListOf<Pair<BroadcastId, Boolean>>()

    private val fakeHardwareAdapter = object : HardwareAdapter {
        override val state: StateFlow<AdapterState> = hardwareState
        override val exerciseData: StateFlow<ExerciseData?> = this@DashboardViewModelTest.exerciseData
        override val deviceInfo: StateFlow<DeviceInfo?> = this@DashboardViewModelTest.deviceInfo
        override val deviceIdentity: StateFlow<DeviceIdentity?> = this@DashboardViewModelTest.deviceIdentity
        override val prerequisites: List<Prerequisite> = emptyList()
        override fun canOperate(snapshot: SystemSnapshot): Boolean = true
        override suspend fun connect() {}
        override suspend fun disconnect() {}
        override suspend fun sendCommand(command: DeviceCommand) {}
        override fun setInitialElapsedTime(seconds: Long) {}
    }

    private val fakeSystemMonitor = object : SystemMonitor {
        override val snapshot: StateFlow<SystemSnapshot> = systemSnapshot
        override suspend fun refresh() {}
    }

    private val fakeUserPreferences = object : UserPreferences {
        override val enabledBroadcasts: StateFlow<Set<BroadcastId>> = this@DashboardViewModelTest.enabledBroadcasts
        override fun setBroadcastEnabled(id: BroadcastId, enabled: Boolean) {
            toggledBroadcasts.add(id to enabled)
        }
    }

    private val fakeProfileRepository = object : ProfileRepository {
        override val profiles: Flow<List<Profile>> = flowOf(emptyList())
        override val activeProfile: StateFlow<Profile?> = this@DashboardViewModelTest.activeProfile
        override suspend fun createProfile(name: String): Profile = Profile(name = name)
        override suspend fun updateProfile(profile: Profile) {}
        override suspend fun deleteProfile(id: Long) {}
        override suspend fun setActiveProfile(id: Long) {}
        override fun getRideSummaries(profileId: Long): Flow<List<RideSummary>> = flowOf(emptyList())
        override suspend fun saveRideSummary(summary: RideSummary, samples: List<WorkoutSample>) {}
        override suspend fun deleteRideSummary(id: Long) {}
        override fun getWorkoutSamples(rideId: Long): Flow<List<WorkoutSample>> = flowOf(emptyList())
    }

    private fun createBroadcastAdapter(
        id: BroadcastId,
        state: MutableStateFlow<AdapterState> = MutableStateFlow(AdapterState.Inactive),
        clients: MutableStateFlow<Set<ClientInfo>> = MutableStateFlow(emptySet()),
    ): BroadcastAdapter = object : BroadcastAdapter {
        override val id: BroadcastId = id
        override val state: StateFlow<AdapterState> = state
        override val connectedClients: StateFlow<Set<ClientInfo>> = clients
        override val incomingCommands: Flow<DeviceCommand> = emptyFlow()
        override val prerequisites: List<Prerequisite> = emptyList()
        override fun canOperate(snapshot: SystemSnapshot): Boolean = true
        override suspend fun start(dataSource: Flow<ExerciseData>, deviceInfo: DeviceInfo) {}
        override suspend fun stop() {}
    }

    private lateinit var viewModel: DashboardViewModel

    private fun createViewModel(
        broadcastAdapters: Set<BroadcastAdapter> = emptySet(),
    ) {
        val noOpLogger = object : AppLogger {
            override fun d(tag: String, message: String) {}
            override fun i(tag: String, message: String) {}
            override fun w(tag: String, message: String) {}
            override fun e(tag: String, message: String, throwable: Throwable?) {}
        }

        val fakeSystemController = object : SystemController {
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

        val fakeEcosystemManager = object : EcosystemManager {
            override val prerequisites: List<Prerequisite> = emptyList()
        }

        val scope = CoroutineScope(mainDispatcherRule.testDispatcher)
        val rideRecorder = RideRecorder(fakeProfileRepository, noOpLogger, scope)

        val orchestrator = Orchestrator(
            systemMonitor = fakeSystemMonitor,
            systemController = fakeSystemController,
            ecosystemManager = fakeEcosystemManager,
            hardwareAdapter = fakeHardwareAdapter,
            broadcastAdapters = broadcastAdapters,
            userPreferences = fakeUserPreferences,
            rideRecorder = rideRecorder,
            logger = noOpLogger,
            scope = scope,
        )

        viewModel = DashboardViewModel(
            orchestrator = orchestrator,
            hardwareAdapter = fakeHardwareAdapter,
            broadcastAdapters = broadcastAdapters,
            systemMonitor = fakeSystemMonitor,
            userPreferences = fakeUserPreferences,
            profileRepository = fakeProfileRepository,
            context = ContextWrapper(null),
        )
    }

    @Before
    fun setUp() {
        toggledBroadcasts.clear()
    }

    @Test
    fun `initial uiState has expected defaults`() {
        createViewModel()

        val state = viewModel.uiState.value
        assertThat(state.orchestratorState).isEqualTo(OrchestratorState.Idle)
        assertThat(state.exerciseData).isNull()
        assertThat(state.hardwareState).isEqualTo(AdapterState.Inactive)
        assertThat(state.deviceInfo).isNull()
        assertThat(state.broadcasts).isEmpty()
        assertThat(state.systemStatus).isEqualTo(buildSystemSnapshot().status)
        assertThat(state.profileName).isNull()
    }

    @Test
    fun `uiState reflects hardware adapter state changes`() = runTest {
        createViewModel()

        viewModel.uiState.test {
            assertThat(awaitItem().hardwareState).isEqualTo(AdapterState.Inactive)

            hardwareState.value = AdapterState.Active
            assertThat(awaitItem().hardwareState).isEqualTo(AdapterState.Active)
        }
    }

    @Test
    fun `uiState reflects exercise data updates`() = runTest {
        createViewModel()

        viewModel.uiState.test {
            assertThat(awaitItem().exerciseData).isNull()

            val data = buildExerciseData(power = 200, cadence = 90, elapsedTime = 120)
            exerciseData.value = data
            assertThat(awaitItem().exerciseData).isEqualTo(data)
        }
    }

    @Test
    fun `broadcasts sorted by BroadcastId ordinal`() = runTest {
        val wftnpAdapter = createBroadcastAdapter(BroadcastId.WFTNP)
        val ftmsAdapter = createBroadcastAdapter(BroadcastId.FTMS)
        createViewModel(broadcastAdapters = setOf(wftnpAdapter, ftmsAdapter))

        viewModel.uiState.test {
            val broadcasts = awaitItem().broadcasts
            assertThat(broadcasts).hasSize(2)
            assertThat(broadcasts[0].id).isEqualTo(BroadcastId.FTMS)
            assertThat(broadcasts[1].id).isEqualTo(BroadcastId.WFTNP)
        }
    }

    @Test
    fun `toggleBroadcast delegates to userPreferences`() {
        createViewModel()

        viewModel.toggleBroadcast(BroadcastId.FTMS, true)

        assertThat(toggledBroadcasts).containsExactly(BroadcastId.FTMS to true)
    }

    @Test
    fun `broadcast enabled state reflects user preferences`() = runTest {
        val ftmsAdapter = createBroadcastAdapter(BroadcastId.FTMS)
        enabledBroadcasts.value = setOf(BroadcastId.FTMS)
        createViewModel(broadcastAdapters = setOf(ftmsAdapter))

        viewModel.uiState.test {
            val broadcasts = awaitItem().broadcasts
            assertThat(broadcasts).hasSize(1)
            assertThat(broadcasts[0].enabled).isTrue()
        }
    }

    @Test
    fun `broadcast disabled when not in user preferences`() = runTest {
        val ftmsAdapter = createBroadcastAdapter(BroadcastId.FTMS)
        enabledBroadcasts.value = emptySet()
        createViewModel(broadcastAdapters = setOf(ftmsAdapter))

        viewModel.uiState.test {
            val broadcasts = awaitItem().broadcasts
            assertThat(broadcasts).hasSize(1)
            assertThat(broadcasts[0].enabled).isFalse()
        }
    }

    @Test
    fun `activeProfileId returns profile id`() {
        createViewModel()

        activeProfile.value = Profile(id = 42, name = "Test User")

        assertThat(viewModel.activeProfileId).isEqualTo(42)
    }

    @Test
    fun `activeProfileId returns null when no profile`() {
        createViewModel()

        assertThat(viewModel.activeProfileId).isNull()
    }

    @Test
    fun `currentElapsedSeconds returns elapsed time from exercise data`() = runTest {
        createViewModel()

        viewModel.uiState.test {
            awaitItem() // initial

            exerciseData.value = buildExerciseData(elapsedTime = 300)
            awaitItem() // updated

            assertThat(viewModel.currentElapsedSeconds).isEqualTo(300)
        }
    }

    @Test
    fun `currentElapsedSeconds returns zero when no exercise data`() {
        createViewModel()

        assertThat(viewModel.currentElapsedSeconds).isEqualTo(0)
    }

    @Test
    fun `uiState reflects profile name`() = runTest {
        createViewModel()

        viewModel.uiState.test {
            assertThat(awaitItem().profileName).isNull()

            activeProfile.value = Profile(id = 1, name = "Alice")
            assertThat(awaitItem().profileName).isEqualTo("Alice")
        }
    }

    @Test
    fun `broadcast client count reflects connected clients`() = runTest {
        val clients = MutableStateFlow<Set<ClientInfo>>(emptySet())
        val ftmsAdapter = createBroadcastAdapter(BroadcastId.FTMS, clients = clients)
        createViewModel(broadcastAdapters = setOf(ftmsAdapter))

        viewModel.uiState.test {
            assertThat(awaitItem().broadcasts[0].clientCount).isEqualTo(0)

            clients.value = setOf(
                ClientInfo(id = "device-1", protocol = "FTMS", connectedAt = 0),
                ClientInfo(id = "device-2", protocol = "FTMS", connectedAt = 0),
            )
            assertThat(awaitItem().broadcasts[0].clientCount).isEqualTo(2)
        }
    }

    @Test
    fun `broadcast state reflects adapter state changes`() = runTest {
        val adapterState = MutableStateFlow<AdapterState>(AdapterState.Inactive)
        val ftmsAdapter = createBroadcastAdapter(BroadcastId.FTMS, state = adapterState)
        createViewModel(broadcastAdapters = setOf(ftmsAdapter))

        viewModel.uiState.test {
            assertThat(awaitItem().broadcasts[0].state).isEqualTo(AdapterState.Inactive)

            adapterState.value = AdapterState.Active
            assertThat(awaitItem().broadcasts[0].state).isEqualTo(AdapterState.Active)
        }
    }

    @Test
    fun `uiState reflects system status changes`() = runTest {
        createViewModel()

        viewModel.uiState.test {
            val initial = awaitItem()
            assertThat(initial.systemStatus.isBluetoothLeEnabled).isFalse()

            systemSnapshot.value = buildSystemSnapshot(isBluetoothLeEnabled = true, isWifiEnabled = true)
            val updated = awaitItem()
            assertThat(updated.systemStatus.isBluetoothLeEnabled).isTrue()
            assertThat(updated.systemStatus.isWifiEnabled).isTrue()
        }
    }
}
