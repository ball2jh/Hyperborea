package com.nettarion.hyperborea.ecosystem.ifit

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.ComponentState
import com.nettarion.hyperborea.core.ComponentType
import com.nettarion.hyperborea.core.DeclaredComponent
import com.nettarion.hyperborea.core.FulfillResult
import com.nettarion.hyperborea.core.SystemController
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
    fun `has single prerequisite`() {
        assertThat(manager.prerequisites).hasSize(1)
    }

    @Test
    fun `prerequisite id is ifit-standalone-stopped`() {
        assertThat(manager.prerequisites.single().id).isEqualTo("ifit-standalone-stopped")
    }

    @Test
    fun `prerequisite is met when no ifit components`() {
        val snapshot = buildSystemSnapshot(components = emptyList())
        assertThat(manager.prerequisites.single().isMet(snapshot)).isTrue()
    }

    @Test
    fun `prerequisite is met when ifit service is ENABLED`() {
        val snapshot = buildSystemSnapshot(
            components = listOf(buildIfitComponent(ComponentState.ENABLED)),
        )
        assertThat(manager.prerequisites.single().isMet(snapshot)).isTrue()
    }

    @Test
    fun `prerequisite is met when ifit service is DISABLED`() {
        val snapshot = buildSystemSnapshot(
            components = listOf(buildIfitComponent(ComponentState.DISABLED)),
        )
        assertThat(manager.prerequisites.single().isMet(snapshot)).isTrue()
    }

    @Test
    fun `prerequisite is NOT met when ifit service is RUNNING`() {
        val snapshot = buildSystemSnapshot(
            components = listOf(buildIfitComponent(ComponentState.RUNNING)),
        )
        assertThat(manager.prerequisites.single().isMet(snapshot)).isFalse()
    }

    @Test
    fun `prerequisite is NOT met when ifit service is RUNNING_FOREGROUND`() {
        val snapshot = buildSystemSnapshot(
            components = listOf(buildIfitComponent(ComponentState.RUNNING_FOREGROUND)),
        )
        assertThat(manager.prerequisites.single().isMet(snapshot)).isFalse()
    }

    @Test
    fun `prerequisite is met when other package is running`() {
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
        assertThat(manager.prerequisites.single().isMet(snapshot)).isTrue()
    }

    // --- fulfill ---

    @Test
    fun `fulfill calls forceStopPackage with correct package`() = runTest {
        var capturedPackage: String? = null
        val controller = stubController(
            onForceStop = { pkg ->
                capturedPackage = pkg
                true
            },
        )
        manager.prerequisites.single().fulfill!!.invoke(controller)
        assertThat(capturedPackage).isEqualTo("com.ifit.standalone")
    }

    @Test
    fun `fulfill returns Success when forceStopPackage succeeds`() = runTest {
        val controller = stubController(onForceStop = { true })
        val result = manager.prerequisites.single().fulfill!!.invoke(controller)
        assertThat(result).isEqualTo(FulfillResult.Success)
    }

    @Test
    fun `fulfill returns Failed when forceStopPackage fails`() = runTest {
        val controller = stubController(onForceStop = { false })
        val result = manager.prerequisites.single().fulfill!!.invoke(controller)
        assertThat(result).isInstanceOf(FulfillResult.Failed::class.java)
        assertThat((result as FulfillResult.Failed).reason).contains("com.ifit.standalone")
    }

    // --- Helpers ---

    private fun buildIfitComponent(state: ComponentState) = DeclaredComponent(
        packageName = "com.ifit.standalone",
        className = "com.ifit.standalone.ConnectionService",
        type = ComponentType.SERVICE,
        state = state,
    )

    private fun stubController(
        onForceStop: suspend (String) -> Boolean = { false },
    ) = object : SystemController {
        override suspend fun stopService(packageName: String, className: String) = false
        override suspend fun forceStopPackage(packageName: String) = onForceStop(packageName)
        override suspend fun disablePackage(packageName: String) = false
        override suspend fun enablePackage(packageName: String) = false
        override suspend fun uninstallPackage(packageName: String) = false
        override suspend fun disableComponent(packageName: String, className: String) = false
        override suspend fun enableComponent(packageName: String, className: String) = false
        override suspend fun grantUsbPermission(packageName: String) = false
        override suspend fun revokeUsbPermissions(packageName: String) = false
    }
}
