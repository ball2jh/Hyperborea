package com.nettarion.hyperborea.platform.update

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UpdateManagerTest {

    private lateinit var httpClient: FakeUpdateHttpClient
    private lateinit var logger: FakeAppLogger
    private lateinit var manager: UpdateManager

    @Before
    fun setUp() {
        httpClient = FakeUpdateHttpClient()
        logger = FakeAppLogger()
    }

    private fun createManager(
        testDispatcher: kotlinx.coroutines.test.TestDispatcher = UnconfinedTestDispatcher(),
    ): UpdateManager {
        val context = RuntimeEnvironment.getApplication()
        val scope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        return UpdateManager(
            context = context,
            httpClient = httpClient,
            appInstaller = AppInstaller(context, logger),
            logger = logger,
            scope = scope,
        )
    }

    @Test
    fun `checkForUpdates with newer app version sets appTrack to Available`() = runTest {
        manager = createManager(UnconfinedTestDispatcher(testScheduler))
        httpClient.manifestResponse = """
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

        manager.checkForUpdates()

        val state = manager.appTrack.state.value
        assertThat(state).isInstanceOf(TrackState.Available::class.java)
        val available = state as TrackState.Available
        assertThat(available.info.version).isEqualTo("99.0")
        assertThat(available.info.releaseNotes).isEqualTo("Big update.")
    }

    @Test
    fun `checkForUpdates with same app version keeps appTrack Idle`() = runTest {
        manager = createManager(UnconfinedTestDispatcher(testScheduler))

        @Suppress("DEPRECATION")
        val currentVersionCode = RuntimeEnvironment.getApplication().packageManager
            .getPackageInfo(RuntimeEnvironment.getApplication().packageName, 0).versionCode

        httpClient.manifestResponse = """
            {
                "app": {
                    "versionCode": $currentVersionCode,
                    "versionName": "1.0",
                    "url": "https://example.com/app.apk",
                    "sha256": "abc"
                }
            }
        """.trimIndent()

        manager.checkForUpdates()

        assertThat(manager.appTrack.state.value).isEqualTo(TrackState.Idle)
    }

    @Test
    fun `checkForUpdates with empty manifest keeps appTrack Idle`() = runTest {
        manager = createManager(UnconfinedTestDispatcher(testScheduler))
        httpClient.manifestResponse = "{}"

        manager.checkForUpdates()

        assertThat(manager.appTrack.state.value).isEqualTo(TrackState.Idle)
    }

    @Test
    fun `checkForUpdates with HTTP error keeps appTrack Idle`() = runTest {
        manager = createManager(UnconfinedTestDispatcher(testScheduler))
        httpClient.manifestException = java.io.IOException("No network")

        manager.checkForUpdates()

        assertThat(manager.appTrack.state.value).isEqualTo(TrackState.Idle)
    }

    @Test
    fun `checkForUpdates with malformed JSON keeps appTrack Idle`() = runTest {
        manager = createManager(UnconfinedTestDispatcher(testScheduler))
        httpClient.manifestResponse = "not json"

        manager.checkForUpdates()

        assertThat(manager.appTrack.state.value).isEqualTo(TrackState.Idle)
    }

    @Test
    fun `checking flow reflects in-progress state`() = runTest {
        manager = createManager(UnconfinedTestDispatcher(testScheduler))
        httpClient.manifestResponse = "{}"

        manager.checking.test {
            assertThat(awaitItem()).isFalse()
            manager.checkForUpdates()
            // With UnconfinedTestDispatcher, the check completes immediately
            // so we verify it returns to false
            assertThat(manager.checking.value).isFalse()
        }
    }
}
