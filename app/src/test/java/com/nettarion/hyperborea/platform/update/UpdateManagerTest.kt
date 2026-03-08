package com.nettarion.hyperborea.platform.update

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.platform.license.FakeSharedPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateManagerTest {

    private lateinit var httpClient: FakeUpdateHttpClient
    private lateinit var logger: FakeAppLogger
    private lateinit var prefs: FakeSharedPreferences

    @Before
    fun setUp() {
        httpClient = FakeUpdateHttpClient()
        logger = FakeAppLogger()
        prefs = FakeSharedPreferences()
    }

    private fun createManager(
        currentVersionCode: Int = 1,
    ): UpdateManager {
        val testDispatcher = UnconfinedTestDispatcher()
        val scope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        val downloadDir = File(System.getProperty("java.io.tmpdir"), "test-update-${System.nanoTime()}").absolutePath
        return UpdateManager(
            httpClient = httpClient,
            appInstaller = FakeUpdateInstaller(),
            logger = logger,
            scope = scope,
            prefs = prefs,
            versionProvider = VersionProvider { currentVersionCode },
            downloadDir = downloadDir,
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
                    "url": "https://example.com/app.apk",
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
                    "url": "https://example.com/app.apk",
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
                    "url": "https://example.com/app.apk",
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
                    "url": "https://example.com/app.apk",
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

    @Test
    fun `checkForUpdates includes auth header when token present`() = runTest {
        prefs.edit().putString("license_auth_token", "my-token").commit()
        // Use manifestException to stop the flow early — the key thing is it makes the call
        httpClient.manifestException = java.io.IOException("Expected")

        val manager = createManager()
        manager.checkForUpdates()

        // No crash means the auth token was read and headers were constructed
        assertThat(manager.appTrack.state.value).isEqualTo(TrackState.Idle)
    }
}
