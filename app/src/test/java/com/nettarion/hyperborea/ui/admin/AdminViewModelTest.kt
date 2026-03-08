package com.nettarion.hyperborea.ui.admin

import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.MainDispatcherRule
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.LogEntry
import com.nettarion.hyperborea.core.LogStore
import com.nettarion.hyperborea.core.adapter.AdapterState
import com.nettarion.hyperborea.core.adapter.BroadcastId
import com.nettarion.hyperborea.core.adapter.HardwareAdapter
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.model.DeviceIdentity
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.orchestration.Prerequisite
import com.nettarion.hyperborea.core.profile.UserPreferences
import com.nettarion.hyperborea.core.system.SystemLogEntry
import com.nettarion.hyperborea.core.system.SystemLogStore
import com.nettarion.hyperborea.core.system.SystemMonitor
import com.nettarion.hyperborea.core.system.SystemSnapshot
import com.nettarion.hyperborea.core.test.buildSystemSnapshot
import com.nettarion.hyperborea.platform.license.FakeSharedPreferences
import com.nettarion.hyperborea.platform.support.SupportHttpClient
import com.nettarion.hyperborea.platform.update.FakeAppLogger
import com.nettarion.hyperborea.platform.update.FakeUpdateHttpClient
import com.nettarion.hyperborea.platform.update.FakeUpdateInstaller
import com.nettarion.hyperborea.platform.update.UpdateManager
import com.nettarion.hyperborea.platform.update.VersionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class AdminViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timeout: Timeout = Timeout(45, TimeUnit.SECONDS)

    private val systemSnapshot = MutableStateFlow(buildSystemSnapshot())
    private val enabledBroadcasts = MutableStateFlow<Set<BroadcastId>>(emptySet())
    private var uploadResponse: String? = """{"code":"ABC123"}"""
    private val prefs = FakeSharedPreferences()

    private val noOpLogger = FakeAppLogger()

    private val fakeLogStore = object : LogStore {
        override val entries = MutableStateFlow<List<LogEntry>>(emptyList())
        override val size = MutableStateFlow(0)
        override fun export(): String = "app log content"
        override fun clear() {}
    }

    private val fakeSystemLogStore = object : SystemLogStore {
        override val entries = MutableStateFlow<List<SystemLogEntry>>(emptyList())
        override val size = MutableStateFlow(0)
        override fun export(): String = "system log content"
        override fun clear() {}
    }

    private val fakeSystemMonitor = object : SystemMonitor {
        override val snapshot: StateFlow<SystemSnapshot> = systemSnapshot
        override suspend fun refresh() {}
    }

    private val fakeHardwareAdapter = object : HardwareAdapter {
        override val state = MutableStateFlow<AdapterState>(AdapterState.Inactive)
        override val exerciseData = MutableStateFlow<ExerciseData?>(null)
        override val deviceInfo = MutableStateFlow<DeviceInfo?>(null)
        override val deviceIdentity = MutableStateFlow<DeviceIdentity?>(null)
        override val prerequisites: List<Prerequisite> = emptyList()
        override fun canOperate(snapshot: SystemSnapshot): Boolean = true
        override suspend fun connect() {}
        override suspend fun disconnect() {}
        override suspend fun sendCommand(command: DeviceCommand) {}
        override fun setInitialElapsedTime(seconds: Long) {}
    }

    private val fakeUserPreferences = object : UserPreferences {
        override val enabledBroadcasts: StateFlow<Set<BroadcastId>> = this@AdminViewModelTest.enabledBroadcasts
        override fun setBroadcastEnabled(id: BroadcastId, enabled: Boolean) {}
    }

    private val fakeSupportClient = object : SupportHttpClient {
        override fun upload(authToken: String, jsonBody: String): String? = uploadResponse
    }

    private lateinit var viewModel: AdminViewModel

    private fun createViewModel() {
        prefs.edit().putString("license_device_uuid", "test-uuid").apply()
        prefs.edit().putString("license_auth_token", "test-token").apply()

        val scope = CoroutineScope(mainDispatcherRule.testDispatcher)
        val downloadDir = File(System.getProperty("java.io.tmpdir"), "test-update-${System.nanoTime()}").absolutePath
        val updateManager = UpdateManager(
            httpClient = FakeUpdateHttpClient(),
            appInstaller = FakeUpdateInstaller(),
            logger = noOpLogger,
            scope = scope,
            prefs = prefs,
            versionProvider = VersionProvider { 1 },
            downloadDir = downloadDir,
        )

        viewModel = AdminViewModel(
            logStore = fakeLogStore,
            systemLogStore = fakeSystemLogStore,
            systemMonitor = fakeSystemMonitor,
            hardwareAdapter = fakeHardwareAdapter,
            broadcastAdapters = emptySet(),
            updateManager = updateManager,
            userPreferences = fakeUserPreferences,
            supportHttpClient = fakeSupportClient,
            licensePreferences = prefs,
            logger = noOpLogger,
            context = ContextWrapper(null),
        )
    }

    private fun runViewModelTest(block: suspend TestScope.() -> Unit) = runTest {
        try {
            block()
        } finally {
            if (::viewModel.isInitialized) {
                viewModel.viewModelScope.cancel()
            }
        }
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `uploadSupport transitions Idle to Uploading to Success`() = runViewModelTest {
        createViewModel()

        viewModel.supportUploadState.test {
            assertThat(awaitItem()).isEqualTo(SupportUploadState.Idle)

            viewModel.uploadSupport()
            assertThat(awaitItem()).isEqualTo(SupportUploadState.Uploading)
            assertThat(awaitItem()).isEqualTo(SupportUploadState.Success("ABC123"))
        }
    }

    @Test
    fun `uploadSupport transitions to Error on null response`() = runViewModelTest {
        uploadResponse = null
        createViewModel()

        viewModel.supportUploadState.test {
            assertThat(awaitItem()).isEqualTo(SupportUploadState.Idle)

            viewModel.uploadSupport()
            assertThat(awaitItem()).isEqualTo(SupportUploadState.Uploading)
            assertThat(awaitItem()).isEqualTo(SupportUploadState.Error("Upload failed"))
        }
    }

    @Test
    fun `uploadSupport errors when no auth token`() = runViewModelTest {
        createViewModel()
        prefs.edit().remove("license_auth_token").apply()

        viewModel.supportUploadState.test {
            assertThat(awaitItem()).isEqualTo(SupportUploadState.Idle)

            viewModel.uploadSupport()
            assertThat(awaitItem()).isEqualTo(SupportUploadState.Uploading)
            assertThat(awaitItem()).isEqualTo(SupportUploadState.Error("Device not linked"))
        }
    }

    @Test
    fun `dismissSupportUpload resets to Idle`() = runViewModelTest {
        createViewModel()

        viewModel.supportUploadState.test {
            assertThat(awaitItem()).isEqualTo(SupportUploadState.Idle)

            viewModel.uploadSupport()
            assertThat(awaitItem()).isEqualTo(SupportUploadState.Uploading)
            assertThat(awaitItem()).isEqualTo(SupportUploadState.Success("ABC123"))

            viewModel.dismissSupportUpload()
            assertThat(awaitItem()).isEqualTo(SupportUploadState.Idle)
        }
    }

    @Test
    fun `uploadSupport errors on invalid response JSON`() = runViewModelTest {
        uploadResponse = """{"status":"ok"}"""
        createViewModel()

        viewModel.supportUploadState.test {
            assertThat(awaitItem()).isEqualTo(SupportUploadState.Idle)

            viewModel.uploadSupport()
            assertThat(awaitItem()).isEqualTo(SupportUploadState.Uploading)
            assertThat(awaitItem()).isEqualTo(SupportUploadState.Error("Invalid response"))
        }
    }
}
