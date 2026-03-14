package com.nettarion.hyperborea.ui.device

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.MainDispatcherRule
import com.nettarion.hyperborea.core.adapter.AdapterState
import com.nettarion.hyperborea.core.adapter.HardwareAdapter
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.model.DeviceIdentity
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.model.Metric
import com.nettarion.hyperborea.core.orchestration.Prerequisite
import com.nettarion.hyperborea.core.profile.DeviceConfigRepository
import com.nettarion.hyperborea.core.system.SystemSnapshot
import com.nettarion.hyperborea.core.test.TestAppLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceConfigViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val fakeRepo = FakeDeviceConfigRepository()
    private val fakeHardwareAdapter = FakeHardwareAdapter()
    private val logger = TestAppLogger()

    private lateinit var viewModel: DeviceConfigViewModel

    private fun createViewModel(): DeviceConfigViewModel {
        viewModel = DeviceConfigViewModel(fakeRepo, fakeHardwareAdapter, logger)
        return viewModel
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `load populates from DeviceDatabase when no custom config`() = runTest {
        val vm = createViewModel()
        vm.load(2117)
        advanceUntilIdle()

        assertThat(vm.name.value).isEqualTo("NordicTrack S22i")
        assertThat(vm.type.value).isEqualTo(DeviceType.BIKE)
        assertThat(vm.maxResistance.value).isEqualTo("24")
        assertThat(vm.isCustom.value).isFalse()
    }

    @Test
    fun `load populates from custom config when one exists`() = runTest {
        val customInfo = DeviceInfo(
            name = "My Bike",
            type = DeviceType.BIKE,
            supportedMetrics = setOf(Metric.POWER),
            maxResistance = 30, minResistance = 5,
            minIncline = -5f, maxIncline = 15f,
            maxPower = 1500, minPower = 50, powerStep = 5,
            resistanceStep = 2.0f, inclineStep = 1.0f,
            speedStep = 1.0f, maxSpeed = 50f,
        )
        fakeRepo.configs[2117] = customInfo

        val vm = createViewModel()
        vm.load(2117)
        advanceUntilIdle()

        assertThat(vm.name.value).isEqualTo("My Bike")
        assertThat(vm.maxResistance.value).isEqualTo("30")
        assertThat(vm.isCustom.value).isTrue()
    }

    @Test
    fun `save persists config and calls refreshDeviceInfo`() = runTest {
        val vm = createViewModel()
        vm.load(2117)
        advanceUntilIdle()

        vm.setName("Custom Name")
        vm.setMaxResistance("32")

        var savedCalled = false
        vm.save { savedCalled = true }
        advanceUntilIdle()

        assertThat(savedCalled).isTrue()
        assertThat(fakeRepo.configs[2117]).isNotNull()
        assertThat(fakeRepo.configs[2117]!!.name).isEqualTo("Custom Name")
        assertThat(fakeRepo.configs[2117]!!.maxResistance).isEqualTo(32)
        assertThat(fakeHardwareAdapter.refreshCalled).isTrue()
        assertThat(vm.isCustom.value).isTrue()
    }

    @Test
    fun `resetToDefaults deletes config and refreshes`() = runTest {
        fakeRepo.configs[2117] = DeviceInfo.DEFAULT_INDOOR_BIKE.copy(name = "Custom")

        val vm = createViewModel()
        vm.load(2117)
        advanceUntilIdle()
        assertThat(vm.isCustom.value).isTrue()

        vm.resetToDefaults()
        advanceUntilIdle()

        assertThat(fakeRepo.configs[2117]).isNull()
        assertThat(vm.name.value).isEqualTo("NordicTrack S22i")
        assertThat(vm.isCustom.value).isFalse()
        assertThat(fakeHardwareAdapter.refreshCalled).isTrue()
    }

    @Test
    fun `load with null model uses defaults`() = runTest {
        val vm = createViewModel()
        vm.load(null)
        advanceUntilIdle()

        assertThat(vm.name.value).isEqualTo("Hyperborea")
        assertThat(vm.type.value).isEqualTo(DeviceType.BIKE)
    }
}

private class FakeDeviceConfigRepository : DeviceConfigRepository {
    val configs = mutableMapOf<Int, DeviceInfo>()

    override suspend fun getConfig(modelNumber: Int): DeviceInfo? = configs[modelNumber]
    override suspend fun saveConfig(modelNumber: Int, config: DeviceInfo) { configs[modelNumber] = config }
    override suspend fun deleteConfig(modelNumber: Int) { configs.remove(modelNumber) }
    override suspend fun hasConfig(modelNumber: Int): Boolean = modelNumber in configs
}

private class FakeHardwareAdapter : HardwareAdapter {
    var refreshCalled = false

    override val state: StateFlow<AdapterState> = MutableStateFlow(AdapterState.Inactive)
    override val exerciseData: StateFlow<ExerciseData?> = MutableStateFlow(null)
    override val deviceInfo: StateFlow<DeviceInfo?> = MutableStateFlow(null)
    override val deviceIdentity: StateFlow<DeviceIdentity?> = MutableStateFlow(null)
    override val prerequisites: List<Prerequisite> = emptyList()
    override fun canOperate(snapshot: SystemSnapshot): Boolean = true
    override suspend fun connect() {}
    override suspend fun disconnect() {}
    override suspend fun identify(): DeviceInfo? = null
    override suspend fun sendCommand(command: DeviceCommand) {}
    override fun setInitialElapsedTime(seconds: Long) {}
    override fun refreshDeviceInfo() { refreshCalled = true }
}
