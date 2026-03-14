package com.nettarion.hyperborea.platform.license

import android.content.ContextWrapper
import android.content.SharedPreferences
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.LicenseState
import com.nettarion.hyperborea.core.test.TestAppLogger
import com.nettarion.hyperborea.core.PairingSession
import com.nettarion.hyperborea.core.PairingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class LicenseCheckerImplTest {

    @get:Rule
    val timeout: Timeout = Timeout(45, TimeUnit.SECONDS)

    private lateinit var prefs: SharedPreferences
    private lateinit var httpClient: FakeLicenseHttpClient
    private lateinit var checker: LicenseCheckerImpl

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        httpClient = FakeLicenseHttpClient()
        checker = LicenseCheckerImpl(ContextWrapper(null), prefs, TestAppLogger(), httpClient)
    }

    @Test
    fun `initial state is Checking`() {
        assertThat(checker.state.value).isEqualTo(LicenseState.Checking)
    }

    @Test
    fun `check with no auth token returns Unlicensed`() = runTest {
        checker.check()
        assertThat(checker.state.value).isEqualTo(LicenseState.Unlicensed)
    }

    @Test
    fun `check with no auth token emits Checking then Unlicensed`() = runTest {
        checker.state.test {
            assertThat(awaitItem()).isEqualTo(LicenseState.Checking)
            checker.check()
            assertThat(awaitItem()).isEqualTo(LicenseState.Unlicensed)
        }
    }

    @Test
    fun `check with auth token but network error returns Unlicensed`() = runTest {
        prefs.edit()
            .putString("license_auth_token", "some-token")
            .commit()

        httpClient.statusResponse = null

        checker.check()

        assertThat(checker.state.value).isEqualTo(LicenseState.Unlicensed)
    }

    @Test
    fun `check with auth token and exception returns Unlicensed`() = runTest {
        prefs.edit()
            .putString("license_auth_token", "some-token")
            .commit()

        httpClient.statusException = java.io.IOException("Connection refused")

        checker.check()

        assertThat(checker.state.value).isEqualTo(LicenseState.Unlicensed)
    }

    @Test
    fun `requestPairing returns Created on success`() = runTest {
        val futureExpiry = System.currentTimeMillis() + 600_000
        httpClient.pairingResponse = """{"pairingToken":"tok-123","pairingCode":"456789","expiresAt":$futureExpiry}"""

        val result = checker.requestPairing()

        assertThat(result).isInstanceOf(PairingSession.Created::class.java)
        val created = result as PairingSession.Created
        assertThat(created.pairingToken).isEqualTo("tok-123")
        assertThat(created.pairingCode).isEqualTo("456789")
        assertThat(created.expiresAt).isGreaterThan(0)

        val state = checker.state.value
        assertThat(state).isInstanceOf(LicenseState.Pairing::class.java)
        assertThat((state as LicenseState.Pairing).pairingToken).isEqualTo("tok-123")
    }

    @Test
    fun `requestPairing returns Error when server returns null`() = runTest {
        httpClient.pairingResponse = null

        val result = checker.requestPairing()

        assertThat(result).isInstanceOf(PairingSession.Error::class.java)
    }

    @Test
    fun `requestPairing returns Error on exception`() = runTest {
        httpClient.pairingException = java.io.IOException("Connection refused")

        val result = checker.requestPairing()

        assertThat(result).isInstanceOf(PairingSession.Error::class.java)
    }

    @Test
    fun `pollPairing returns Linked and saves auth token`() = runTest {
        httpClient.pairingStatusResponse = """{"status":"linked","authToken":"new-token-abc"}"""
        // After link succeeds, check() is called — null status response means Unlicensed
        httpClient.statusResponse = null

        val result = checker.pollPairing("tok-123")

        assertThat(result).isInstanceOf(PairingStatus.Linked::class.java)
        assertThat((result as PairingStatus.Linked).authToken).isEqualTo("new-token-abc")
        assertThat(prefs.getString("license_auth_token", null)).isEqualTo("new-token-abc")
    }

    @Test
    fun `pollPairing returns Pending when status is pending`() = runTest {
        httpClient.pairingStatusResponse = """{"status":"pending"}"""

        val result = checker.pollPairing("tok-123")

        assertThat(result).isEqualTo(PairingStatus.Pending)
    }

    @Test
    fun `pollPairing returns Expired when status is expired`() = runTest {
        httpClient.pairingStatusResponse = """{"status":"expired"}"""

        val result = checker.pollPairing("tok-123")

        assertThat(result).isEqualTo(PairingStatus.Expired)
        assertThat(checker.state.value).isEqualTo(LicenseState.Unlicensed)
    }

    @Test
    fun `pollPairing returns Error on network failure`() = runTest {
        httpClient.pairingStatusResponse = null

        val result = checker.pollPairing("tok-123")

        assertThat(result).isInstanceOf(PairingStatus.Error::class.java)
    }

    @Test
    fun `getDeviceUuid generates and persists UUID`() = runTest {
        checker.requestPairing()

        val uuid = prefs.getString("license_device_uuid", null)
        assertThat(uuid).isNotNull()
        assertThat(uuid).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }

    @Test
    fun `getDeviceUuid returns same value on subsequent calls`() = runTest {
        checker.requestPairing()
        val firstUuid = prefs.getString("license_device_uuid", null)

        checker.requestPairing()
        val secondUuid = prefs.getString("license_device_uuid", null)

        assertThat(firstUuid).isEqualTo(secondUuid)
    }

    @Test
    fun `unlink clears auth token and sets Unlicensed`() = runTest {
        prefs.edit()
            .putString("license_auth_token", "some-token")
            .commit()

        checker.unlink()

        assertThat(checker.state.value).isEqualTo(LicenseState.Unlicensed)
        assertThat(prefs.getString("license_auth_token", null)).isNull()
    }

    @Test
    fun `unlink calls server with auth token`() = runTest {
        prefs.edit().putString("license_auth_token", "my-token").commit()

        checker.unlink()

        assertThat(httpClient.lastUnlinkToken).isEqualTo("my-token")
    }

    @Test
    fun `unlink without auth token skips server call`() = runTest {
        checker.unlink()

        assertThat(checker.state.value).isEqualTo(LicenseState.Unlicensed)
        assertThat(httpClient.lastUnlinkToken).isNull()
    }

    private class FakeLicenseHttpClient : LicenseHttpClient {
        var statusResponse: String? = null
        var statusResponseFactory: ((nonce: String) -> String?)? = null
        var statusException: Exception? = null
        var pairingResponse: String? = null
        var pairingException: Exception? = null
        var pairingStatusResponse: String? = null
        var pairingStatusException: Exception? = null
        var lastNonce: String? = null

        override fun fetchStatus(authToken: String, nonce: String): String? {
            lastNonce = nonce
            statusException?.let { throw it }
            statusResponseFactory?.let { return it(nonce) }
            return statusResponse
        }

        override fun requestPairing(deviceUuid: String): String? {
            pairingException?.let { throw it }
            return pairingResponse
        }

        override fun pollPairingStatus(pairingToken: String): String? {
            pairingStatusException?.let { throw it }
            return pairingStatusResponse
        }

        var unlinkResult: Boolean = true
        var lastUnlinkToken: String? = null
        override fun unlink(authToken: String): Boolean {
            lastUnlinkToken = authToken
            return unlinkResult
        }
    }

}
