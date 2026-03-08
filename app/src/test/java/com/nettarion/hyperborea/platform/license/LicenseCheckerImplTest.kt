package com.nettarion.hyperborea.platform.license

import android.content.SharedPreferences
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.LicenseState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LicenseCheckerImplTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var checker: LicenseCheckerImpl

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        checker = LicenseCheckerImpl(prefs, NoOpLogger())
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
    fun `check with auth token but network error and valid cache returns Licensed`() = runTest {
        // Set up auth token so it attempts network call
        prefs.edit()
            .putString("license_auth_token", "some-token")
            .putLong("license_cached_expires_at", System.currentTimeMillis() + 86_400_000)
            .commit()

        // Network call will fail (example.com returns error or times out)
        // The implementation should fall back to cache
        checker.check()

        assertThat(checker.state.value).isInstanceOf(LicenseState.Licensed::class.java)
    }

    @Test
    fun `check with auth token but network error and expired cache returns Unlicensed`() = runTest {
        prefs.edit()
            .putString("license_auth_token", "some-token")
            .putLong("license_cached_expires_at", System.currentTimeMillis() - 86_400_000)
            .commit()

        checker.check()

        assertThat(checker.state.value).isEqualTo(LicenseState.Unlicensed)
    }

    @Test
    fun `check with auth token but network error and no cache returns Unlicensed`() = runTest {
        prefs.edit()
            .putString("license_auth_token", "some-token")
            .commit()

        checker.check()

        assertThat(checker.state.value).isEqualTo(LicenseState.Unlicensed)
    }

    @Test
    fun `getDeviceUuid generates and persists UUID`() = runTest {
        // First call to linkWithCode triggers getDeviceUuid internally
        // We can verify UUID persistence by checking prefs after a link attempt
        checker.linkWithCode("123456")

        val uuid = prefs.getString("license_device_uuid", null)
        assertThat(uuid).isNotNull()
        // UUID format: 8-4-4-4-12
        assertThat(uuid).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }

    @Test
    fun `getDeviceUuid returns same value on subsequent calls`() = runTest {
        // First link attempt generates UUID
        checker.linkWithCode("123456")
        val firstUuid = prefs.getString("license_device_uuid", null)

        // Second link attempt should reuse the same UUID
        checker.linkWithCode("654321")
        val secondUuid = prefs.getString("license_device_uuid", null)

        assertThat(firstUuid).isEqualTo(secondUuid)
    }

    @Test
    fun `linkWithCode returns error when server unreachable`() = runTest {
        val result = checker.linkWithCode("123456")
        assertThat(result).isInstanceOf(com.nettarion.hyperborea.core.LinkResult.Error::class.java)
    }

    private class NoOpLogger : AppLogger {
        override fun d(tag: String, message: String) {}
        override fun i(tag: String, message: String) {}
        override fun w(tag: String, message: String) {}
        override fun e(tag: String, message: String, throwable: Throwable?) {}
    }
}
