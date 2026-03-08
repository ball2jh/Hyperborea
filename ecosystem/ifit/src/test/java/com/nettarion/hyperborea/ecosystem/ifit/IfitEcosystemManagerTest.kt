package com.nettarion.hyperborea.ecosystem.ifit

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.system.ComponentState
import com.nettarion.hyperborea.core.system.ComponentType
import com.nettarion.hyperborea.core.system.DeclaredComponent
import com.nettarion.hyperborea.core.orchestration.FulfillResult
import com.nettarion.hyperborea.core.system.SystemController
import com.nettarion.hyperborea.core.test.buildSystemSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class IfitEcosystemManagerTest {

    private lateinit var manager: IfitEcosystemManager

    @Before
    fun setUp() {
        manager = IfitEcosystemManager()
    }

    // --- Prerequisites ---

    @Test
    fun `has two prerequisites`() {
        assertThat(manager.prerequisites).hasSize(2)
    }

    // --- ifit-standalone-stopped ---

    private fun standalonePrereq() = manager.prerequisites.first { it.id == "ifit-standalone-stopped" }

    @Test
    fun `standalone prerequisite is met when no ifit components`() {
        val snapshot = buildSystemSnapshot(components = emptyList())
        assertThat(standalonePrereq().isMet(snapshot)).isTrue()
    }

    @Test
    fun `standalone prerequisite is met when ifit service is ENABLED`() {
        val snapshot = buildSystemSnapshot(
            components = listOf(buildIfitComponent(ComponentState.ENABLED)),
        )
        assertThat(standalonePrereq().isMet(snapshot)).isTrue()
    }

    @Test
    fun `standalone prerequisite is met when ifit service is DISABLED`() {
        val snapshot = buildSystemSnapshot(
            components = listOf(buildIfitComponent(ComponentState.DISABLED)),
        )
        assertThat(standalonePrereq().isMet(snapshot)).isTrue()
    }

    @Test
    fun `standalone prerequisite is NOT met when ifit service is RUNNING`() {
        val snapshot = buildSystemSnapshot(
            components = listOf(buildIfitComponent(ComponentState.RUNNING)),
        )
        assertThat(standalonePrereq().isMet(snapshot)).isFalse()
    }

    @Test
    fun `standalone prerequisite is NOT met when ifit service is RUNNING_FOREGROUND`() {
        val snapshot = buildSystemSnapshot(
            components = listOf(buildIfitComponent(ComponentState.RUNNING_FOREGROUND)),
        )
        assertThat(standalonePrereq().isMet(snapshot)).isFalse()
    }

    @Test
    fun `standalone prerequisite is met when other package is running`() {
        val snapshot = buildSystemSnapshot(
            components = listOf(
                DeclaredComponent(
                    packageName = "com.other.app",
                    className = "com.other.app.SomeService",
                    type = ComponentType.SERVICE,
                    state = ComponentState.RUNNING,
                ),
            ),
        )
        assertThat(standalonePrereq().isMet(snapshot)).isTrue()
    }

    @Test
    fun `standalone fulfill calls forceStopPackage with correct package`() = runTest {
        var capturedPackage: String? = null
        val controller = stubController(onForceStop = { pkg -> capturedPackage = pkg; true })
        standalonePrereq().fulfill!!.invoke(controller)
        assertThat(capturedPackage).isEqualTo("com.ifit.standalone")
    }

    @Test
    fun `standalone fulfill returns Success when forceStopPackage succeeds`() = runTest {
        val controller = stubController(onForceStop = { true })
        val result = standalonePrereq().fulfill!!.invoke(controller)
        assertThat(result).isEqualTo(FulfillResult.Success)
    }

    @Test
    fun `standalone fulfill returns Failed when forceStopPackage fails`() = runTest {
        val controller = stubController(onForceStop = { false })
        val result = standalonePrereq().fulfill!!.invoke(controller)
        assertThat(result).isInstanceOf(FulfillResult.Failed::class.java)
        assertThat((result as FulfillResult.Failed).reason).contains("com.ifit.standalone")
    }

    // --- eru-usb-receiver-disabled ---

    private fun eruReceiverPrereq() = manager.prerequisites.first { it.id == "eru-usb-receiver-disabled" }

    @Test
    fun `eru receiver prerequisite is met when receiver is absent`() {
        val snapshot = buildSystemSnapshot(components = emptyList())
        assertThat(eruReceiverPrereq().isMet(snapshot)).isTrue()
    }

    @Test
    fun `eru receiver prerequisite is met when receiver is DISABLED`() {
        val snapshot = buildSystemSnapshot(
            components = listOf(buildEruReceiver(ComponentState.DISABLED)),
        )
        assertThat(eruReceiverPrereq().isMet(snapshot)).isTrue()
    }

    @Test
    fun `eru receiver prerequisite is NOT met when receiver is ENABLED`() {
        val snapshot = buildSystemSnapshot(
            components = listOf(buildEruReceiver(ComponentState.ENABLED)),
        )
        assertThat(eruReceiverPrereq().isMet(snapshot)).isFalse()
    }

    @Test
    fun `eru receiver fulfill calls disableComponent with correct arguments`() = runTest {
        var capturedPkg: String? = null
        var capturedClass: String? = null
        val controller = stubController(
            onDisableComponent = { pkg, cls -> capturedPkg = pkg; capturedClass = cls; true },
        )
        eruReceiverPrereq().fulfill!!.invoke(controller)
        assertThat(capturedPkg).isEqualTo("com.ifit.eru")
        assertThat(capturedClass).isEqualTo("com.ifit.eru.receivers.UsbDeviceAttachedReceiver")
    }

    @Test
    fun `eru receiver fulfill returns Success when disableComponent succeeds`() = runTest {
        val controller = stubController(onDisableComponent = { _, _ -> true })
        val result = eruReceiverPrereq().fulfill!!.invoke(controller)
        assertThat(result).isEqualTo(FulfillResult.Success)
    }

    @Test
    fun `eru receiver fulfill returns Failed when disableComponent fails`() = runTest {
        val controller = stubController(onDisableComponent = { _, _ -> false })
        val result = eruReceiverPrereq().fulfill!!.invoke(controller)
        assertThat(result).isInstanceOf(FulfillResult.Failed::class.java)
    }

    // --- Helpers ---

    private fun buildIfitComponent(state: ComponentState) = DeclaredComponent(
        packageName = "com.ifit.standalone",
        className = "com.ifit.standalone.ConnectionService",
        type = ComponentType.SERVICE,
        state = state,
    )

    private fun buildEruReceiver(state: ComponentState) = DeclaredComponent(
        packageName = "com.ifit.eru",
        className = "com.ifit.eru.receivers.UsbDeviceAttachedReceiver",
        type = ComponentType.BROADCAST_RECEIVER,
        state = state,
    )

    private fun stubController(
        onForceStop: suspend (String) -> Boolean = { false },
        onDisableComponent: suspend (String, String) -> Boolean = { _, _ -> false },
    ) = object : SystemController {
        override suspend fun stopService(packageName: String, className: String) = false
        override suspend fun forceStopPackage(packageName: String) = onForceStop(packageName)
        override suspend fun disablePackage(packageName: String) = false
        override suspend fun enablePackage(packageName: String) = false
        override suspend fun uninstallPackage(packageName: String) = false
        override suspend fun disableComponent(packageName: String, className: String) = onDisableComponent(packageName, className)
        override suspend fun enableComponent(packageName: String, className: String) = false
        override suspend fun grantUsbPermission(packageName: String) = false
        override suspend fun revokeUsbPermissions(packageName: String) = false
    }
}
