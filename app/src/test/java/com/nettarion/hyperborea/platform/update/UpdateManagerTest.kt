package com.nettarion.hyperborea.platform.update

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.BuildConfig
import com.nettarion.hyperborea.core.test.TestAppLogger
import com.nettarion.hyperborea.core.orchestration.OrchestratorState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateManagerTest {

    private lateinit var httpClient: FakeUpdateHttpClient
    private lateinit var installer: FakeUpdateInstaller
    private lateinit var logger: TestAppLogger
    private val orchestratorState = MutableStateFlow<OrchestratorState>(OrchestratorState.Idle)

    @Before
    fun setUp() {
        httpClient = FakeUpdateHttpClient()
        installer = FakeUpdateInstaller()
        logger = TestAppLogger()
    }

    private fun createManager(
        currentVersionCode: Int = 1,
    ): UpdateManager {
        val testDispatcher = UnconfinedTestDispatcher()
        val scope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        val downloadDir = File(System.getProperty("java.io.tmpdir"), "test-update-${System.nanoTime()}").absolutePath
        return UpdateManager(
            httpClient = httpClient,
            appInstaller = installer,
            logger = logger,
            scope = scope,
            versionProvider = VersionProvider { currentVersionCode },
            downloadDir = downloadDir,
            orchestratorState = orchestratorState,
        )
    }

    // --- Manifest parsing (kept from original) ---

    @Test
    fun `manifest parsing extracts app entry`() {
        val json = """
            {
                "app": {
                    "versionCode": 999,
                    "versionName": "99.0",
                    "url": "${BuildConfig.SERVER_URL}/app.apk",
                    "sha256": "abc123",
                    "releaseNotes": "Big update."
                }
            }
        """.trimIndent()

        val manifest = UpdateManifest.parse(json)
        assertThat(manifest.app).isNotNull()
        assertThat(manifest.app?.versionCode).isEqualTo(999)
        assertThat(manifest.app?.versionName).isEqualTo("99.0")
    }

    @Test
    fun `manifest parsing with empty JSON returns null app`() {
        val manifest = UpdateManifest.parse("{}")
        assertThat(manifest.app).isNull()
    }

    @Test
    fun `manifest parsing with malformed JSON throws`() {
        assertThrows(Exception::class.java) {
            UpdateManifest.parse("not json")
        }
    }

    // --- checkForUpdates ---

    @Test
    fun `checkForUpdates with newer version sets track to Available`() = runTest {
        httpClient.manifestResponse = """
            {
                "app": {
                    "versionCode": 10,
                    "versionName": "2.0",
                    "url": "${BuildConfig.SERVER_URL}/app.apk",
                    "sha256": "deadbeef",
                    "releaseNotes": "New features."
                }
            }
        """.trimIndent()

        val manager = createManager(currentVersionCode = 1)
        manager.checkForUpdates()

        val state = manager.appTrack.state.value
        assertThat(state).isInstanceOf(TrackState.Available::class.java)
        val available = state as TrackState.Available
        assertThat(available.info.version).isEqualTo("2.0")
        assertThat(available.info.sha256).isEqualTo("deadbeef")
    }

    @Test
    fun `checkForUpdates with same version does not set track to Available`() = runTest {
        httpClient.manifestResponse = """
            {
                "app": {
                    "versionCode": 1,
                    "versionName": "1.0",
                    "url": "${BuildConfig.SERVER_URL}/app.apk",
                    "sha256": "abc123"
                }
            }
        """.trimIndent()

        val manager = createManager(currentVersionCode = 1)
        manager.checkForUpdates()

        assertThat(manager.appTrack.state.value).isEqualTo(TrackState.Idle)
    }

    @Test
    fun `checkForUpdates with older version does not set track to Available`() = runTest {
        httpClient.manifestResponse = """
            {
                "app": {
                    "versionCode": 1,
                    "versionName": "0.5",
                    "url": "${BuildConfig.SERVER_URL}/app.apk",
                    "sha256": "abc123"
                }
            }
        """.trimIndent()

        val manager = createManager(currentVersionCode = 5)
        manager.checkForUpdates()

        assertThat(manager.appTrack.state.value).isEqualTo(TrackState.Idle)
    }

    @Test
    fun `checkForUpdates with no app in manifest is no-op`() = runTest {
        httpClient.manifestResponse = "{}"

        val manager = createManager(currentVersionCode = 1)
        manager.checkForUpdates()

        assertThat(manager.appTrack.state.value).isEqualTo(TrackState.Idle)
    }

    @Test
    fun `checkForUpdates with fetch error does not crash`() = runTest {
        httpClient.manifestException = java.io.IOException("Network error")

        val manager = createManager(currentVersionCode = 1)
        manager.checkForUpdates()

        assertThat(manager.appTrack.state.value).isEqualTo(TrackState.Idle)
    }

    @Test
    fun `checkForUpdates sets checking to true then false`() = runTest {
        httpClient.manifestResponse = "{}"

        val manager = createManager()
        assertThat(manager.checking.value).isFalse()
        manager.checkForUpdates()
        // With UnconfinedTestDispatcher, the coroutine completes synchronously
        assertThat(manager.checking.value).isFalse()
    }

    // --- applyUpdate ---

    @Test
    fun `applyUpdate drives full pipeline when available and orchestrator idle`() = runTest {
        val content = "test apk content".toByteArray()
        val sha256 = sha256Hex(content)
        httpClient.manifestResponse = """
            {
                "app": {
                    "versionCode": 10,
                    "versionName": "2.0",
                    "url": "${BuildConfig.SERVER_URL}/app.apk",
                    "sha256": "$sha256",
                    "releaseNotes": "New features."
                }
            }
        """.trimIndent()
        httpClient.downloadBytes = content
        installer.installResult = InstallResult.Success
        orchestratorState.value = OrchestratorState.Idle

        val manager = createManager(currentVersionCode = 1)
        manager.checkForUpdates()
        assertThat(manager.appTrack.state.value).isInstanceOf(TrackState.Available::class.java)

        manager.applyUpdateInternal()

        assertThat(installer.installCalled).isTrue()
        assertThat(installer.finalizeCalled).isTrue()
    }

    @Test
    fun `applyUpdate defers restart when orchestrator is running`() = runTest {
        val content = "test apk content".toByteArray()
        val sha256 = sha256Hex(content)
        httpClient.manifestResponse = """
            {
                "app": {
                    "versionCode": 10,
                    "versionName": "2.0",
                    "url": "${BuildConfig.SERVER_URL}/app.apk",
                    "sha256": "$sha256",
                    "releaseNotes": "New features."
                }
            }
        """.trimIndent()
        httpClient.downloadBytes = content
        installer.installResult = InstallResult.Success
        orchestratorState.value = OrchestratorState.Running()

        val manager = createManager(currentVersionCode = 1)
        manager.checkForUpdates()
        assertThat(manager.appTrack.state.value).isInstanceOf(TrackState.Available::class.java)

        // Set orchestrator to transition to Idle after install completes
        // With UnconfinedTestDispatcher, we need to set Idle before calling apply
        // because awaitIdleAndFinalize will block on first{}
        orchestratorState.value = OrchestratorState.Idle

        manager.applyUpdateInternal()

        assertThat(installer.installCalled).isTrue()
        assertThat(installer.finalizeCalled).isTrue()
    }

    @Test
    fun `applyUpdate is no-op when track is not available`() = runTest {
        val manager = createManager()
        assertThat(manager.appTrack.state.value).isEqualTo(TrackState.Idle)

        manager.applyUpdateInternal()

        assertThat(installer.installCalled).isFalse()
        assertThat(installer.finalizeCalled).isFalse()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
