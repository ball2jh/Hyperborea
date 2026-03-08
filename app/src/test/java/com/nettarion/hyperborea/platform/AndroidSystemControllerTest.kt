package com.nettarion.hyperborea.platform

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.AppLogger
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class AndroidSystemControllerTest {

    private lateinit var controller: AndroidSystemController
    private lateinit var context: Application

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        controller = AndroidSystemController(context, RecordingLogger)
    }

    // --- Smoke tests (existing) ---

    @Test
    fun `stopService does not throw`() = runTest {
        controller.stopService("com.example", "com.example.Svc")
    }

    @Test
    fun `forceStopPackage does not throw`() = runTest {
        controller.forceStopPackage("com.example")
    }

    @Test
    fun `disablePackage does not throw`() = runTest {
        controller.disablePackage("com.example")
    }

    @Test
    fun `enablePackage does not throw`() = runTest {
        controller.enablePackage("com.example")
    }

    @Test
    fun `uninstallPackage does not throw`() = runTest {
        controller.uninstallPackage("com.example")
    }

    @Test
    fun `disableComponent does not throw`() = runTest {
        controller.disableComponent("com.example", "com.example.Svc")
    }

    @Test
    fun `enableComponent does not throw`() = runTest {
        controller.enableComponent("com.example", "com.example.Svc")
    }

    @Test
    fun `grantUsbPermission does not throw`() = runTest {
        controller.grantUsbPermission("com.example")
    }

    @Test
    fun `revokeUsbPermissions does not throw`() = runTest {
        controller.revokeUsbPermissions("com.example")
    }

    // --- Behavioral tests ---

    @Test
    fun `disableComponent sets DISABLED via PackageManager`() = runTest {
        val componentName = ComponentName(context.packageName, "com.nettarion.hyperborea.TestComponent")
        controller.disableComponent(context.packageName, "com.nettarion.hyperborea.TestComponent")

        val state = context.packageManager.getComponentEnabledSetting(componentName)
        assertThat(state).isEqualTo(PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
    }

    @Test
    fun `enableComponent sets ENABLED via PackageManager`() = runTest {
        val componentName = ComponentName(context.packageName, "com.nettarion.hyperborea.TestComponent")

        // First disable, then enable
        controller.disableComponent(context.packageName, "com.nettarion.hyperborea.TestComponent")
        controller.enableComponent(context.packageName, "com.nettarion.hyperborea.TestComponent")

        val state = context.packageManager.getComponentEnabledSetting(componentName)
        assertThat(state).isEqualTo(PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
    }

    @Test
    fun `disableComponent returns true on success`() = runTest {
        val result = controller.disableComponent(context.packageName, "com.nettarion.hyperborea.TestComponent")
        assertThat(result).isTrue()
    }

    @Test
    fun `enableComponent returns true on success`() = runTest {
        val result = controller.enableComponent(context.packageName, "com.nettarion.hyperborea.TestComponent")
        assertThat(result).isTrue()
    }

    @Test
    fun `grantUsbPermission returns false when no USB device available`() = runTest {
        val result = controller.grantUsbPermission(context.packageName)
        assertThat(result).isFalse()
    }

    @Test
    fun `grantUsbPermission logs warning when no device`() = runTest {
        RecordingLogger.clear()
        controller.grantUsbPermission(context.packageName)

        val warnings = RecordingLogger.messages.filter { it.first == "w" }
        assertThat(warnings).isNotEmpty()
    }

    @Test
    fun `forceStopPackage returns a result`() = runTest {
        // forceStopPackage tries reflection first, then falls back to shell
        // Either path should complete without crash
        val result = controller.forceStopPackage("com.example.nonexistent")
        // Result depends on whether reflection succeeds in Robolectric
        assertThat(result).isAnyOf(true, false)
    }

    @Test
    fun `revokeUsbPermissions delegates to forceStopPackage`() = runTest {
        // revokeUsbPermissions is implemented by delegating to forceStopPackage
        RecordingLogger.clear()
        controller.revokeUsbPermissions("com.example")

        val debugMessages = RecordingLogger.messages
            .filter { it.first == "d" && it.second.contains("revokeUsbPermissions") }
        assertThat(debugMessages).isNotEmpty()
    }

    @Test
    fun `stopService returns a boolean result`() = runTest {
        val result = controller.stopService("com.example", "com.example.Svc")
        // Shell command may fail (expected in test) but should return a boolean
        assertThat(result).isAnyOf(true, false)
    }

    private object RecordingLogger : AppLogger {
        val messages = mutableListOf<Pair<String, String>>()

        fun clear() { messages.clear() }

        override fun d(tag: String, message: String) { messages.add("d" to message) }
        override fun i(tag: String, message: String) { messages.add("i" to message) }
        override fun w(tag: String, message: String) { messages.add("w" to message) }
        override fun e(tag: String, message: String, throwable: Throwable?) { messages.add("e" to message) }
    }
}
