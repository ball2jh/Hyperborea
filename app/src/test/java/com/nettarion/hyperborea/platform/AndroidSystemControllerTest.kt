package com.nettarion.hyperborea.platform

import android.app.Application
import com.nettarion.hyperborea.core.AppLogger
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AndroidSystemControllerTest {

    private lateinit var controller: AndroidSystemController

    @Before
    fun setUp() {
        val context: Application = RuntimeEnvironment.getApplication()
        controller = AndroidSystemController(context, NoOpLogger)
    }

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

    private object NoOpLogger : AppLogger {
        override fun d(tag: String, message: String) {}
        override fun i(tag: String, message: String) {}
        override fun w(tag: String, message: String) {}
        override fun e(tag: String, message: String, throwable: Throwable?) {}
    }
}
