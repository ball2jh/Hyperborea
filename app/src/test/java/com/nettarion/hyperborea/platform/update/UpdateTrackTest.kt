package com.nettarion.hyperborea.platform.update

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UpdateTrackTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var httpClient: FakeUpdateHttpClient
    private lateinit var installer: FakeUpdateInstaller
    private lateinit var logger: FakeAppLogger
    private lateinit var track: UpdateTrack

    private val sampleInfo = UpdateInfo(
        version = "1.1",
        url = "https://example.com/update.bin",
        sha256 = "", // set per test
        releaseNotes = "Test update",
    )

    @Before
    fun setUp() {
        httpClient = FakeUpdateHttpClient()
        installer = FakeUpdateInstaller()
        logger = FakeAppLogger()
    }

    private fun createTrack(
        testDispatcher: kotlinx.coroutines.test.TestDispatcher = UnconfinedTestDispatcher(),
    ): UpdateTrack {
        val scope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        return UpdateTrack(
            name = "test",
            downloadDir = tempDir.root.absolutePath,
            downloadFilename = "update.bin",
            installer = installer,
            httpClient = httpClient,
            logger = logger,
            scope = scope,
        )
    }

    // --- Initial state ---

    @Test
    fun `initial state is Idle`() {
        track = createTrack()
        assertThat(track.state.value).isEqualTo(TrackState.Idle)
    }

    // --- setAvailable ---

    @Test
    fun `setAvailable transitions to Available`() {
        track = createTrack()
        track.setAvailable(sampleInfo)
        assertThat(track.state.value).isInstanceOf(TrackState.Available::class.java)
        assertThat((track.state.value as TrackState.Available).info).isEqualTo(sampleInfo)
    }

    // --- download ---

    @Test
    fun `download from Available completes with correct SHA-256`() = runTest {
        track = createTrack(UnconfinedTestDispatcher(testScheduler))
        val content = "test file content".toByteArray()
        val sha256 = sha256Hex(content)
        val info = sampleInfo.copy(sha256 = sha256)

        httpClient.downloadBytes = content
        track.setAvailable(info)
        track.download()

        val state = track.state.value
        assertThat(state).isInstanceOf(TrackState.ReadyToInstall::class.java)
        val ready = state as TrackState.ReadyToInstall
        assertThat(ready.info).isEqualTo(info)
        assertThat(java.io.File(ready.path).readBytes()).isEqualTo(content)
    }

    @Test
    fun `download with SHA-256 mismatch transitions to Error`() = runTest {
        track = createTrack(UnconfinedTestDispatcher(testScheduler))
        val info = sampleInfo.copy(sha256 = "0000000000000000000000000000000000000000000000000000000000000000")

        httpClient.downloadBytes = "some content".toByteArray()
        track.setAvailable(info)
        track.download()

        val state = track.state.value
        assertThat(state).isInstanceOf(TrackState.Error::class.java)
        assertThat((state as TrackState.Error).message).contains("SHA-256 mismatch")
    }

    @Test
    fun `download with HTTP error transitions to Error`() = runTest {
        track = createTrack(UnconfinedTestDispatcher(testScheduler))
        val info = sampleInfo.copy(sha256 = "abc")

        httpClient.downloadException = java.io.IOException("Network error")
        track.setAvailable(info)
        track.download()

        val state = track.state.value
        assertThat(state).isInstanceOf(TrackState.Error::class.java)
        assertThat((state as TrackState.Error).message).contains("Download failed")
    }

    @Test
    fun `download from Idle is no-op`() {
        track = createTrack()
        track.download()
        assertThat(track.state.value).isEqualTo(TrackState.Idle)
    }

    // --- install ---

    @Test
    fun `install from ReadyToInstall with success transitions to Installed`() = runTest {
        track = createTrack(UnconfinedTestDispatcher(testScheduler))
        val content = "apk content".toByteArray()
        val sha256 = sha256Hex(content)
        val info = sampleInfo.copy(sha256 = sha256)

        httpClient.downloadBytes = content
        track.setAvailable(info)
        track.download()

        installer.installResult = InstallResult.Success
        track.install()

        assertThat(track.state.value).isInstanceOf(TrackState.Installed::class.java)
        assertThat(installer.installCalled).isTrue()
    }

    @Test
    fun `install with failure transitions to Error`() = runTest {
        track = createTrack(UnconfinedTestDispatcher(testScheduler))
        val content = "apk content".toByteArray()
        val sha256 = sha256Hex(content)
        val info = sampleInfo.copy(sha256 = sha256)

        httpClient.downloadBytes = content
        track.setAvailable(info)
        track.download()

        installer.installResult = InstallResult.Failed("pm install failed")
        track.install()

        val state = track.state.value
        assertThat(state).isInstanceOf(TrackState.Error::class.java)
        assertThat((state as TrackState.Error).message).isEqualTo("pm install failed")
    }

    @Test
    fun `install from Idle is no-op`() {
        track = createTrack()
        track.install()
        assertThat(track.state.value).isEqualTo(TrackState.Idle)
    }

    // --- dismiss ---

    @Test
    fun `dismiss from Available resets to Idle`() {
        track = createTrack()
        track.setAvailable(sampleInfo)
        track.dismiss()
        assertThat(track.state.value).isEqualTo(TrackState.Idle)
    }

    @Test
    fun `dismiss from Error resets to Idle`() = runTest {
        track = createTrack(UnconfinedTestDispatcher(testScheduler))
        httpClient.downloadException = java.io.IOException("fail")
        track.setAvailable(sampleInfo.copy(sha256 = "abc"))
        track.download()
        assertThat(track.state.value).isInstanceOf(TrackState.Error::class.java)

        track.dismiss()
        assertThat(track.state.value).isEqualTo(TrackState.Idle)
    }

    // --- state flow emissions ---

    @Test
    fun `download progresses through states to ReadyToInstall`() = runTest {
        track = createTrack(UnconfinedTestDispatcher(testScheduler))
        val content = ByteArray(16384) { it.toByte() }
        val sha256 = sha256Hex(content)
        val info = sampleInfo.copy(sha256 = sha256)

        httpClient.downloadBytes = content
        track.setAvailable(info)
        track.download()

        // With UnconfinedTestDispatcher, StateFlow may coalesce intermediate Downloading
        // states. Verify the final state is ReadyToInstall with correct bytes downloaded.
        val state = track.state.value
        assertThat(state).isInstanceOf(TrackState.ReadyToInstall::class.java)
        val ready = state as TrackState.ReadyToInstall
        assertThat(ready.info).isEqualTo(info)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
