package com.nettarion.hyperborea.ecosystem.ifit

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.system.ComponentState
import com.nettarion.hyperborea.core.system.ComponentType
import com.nettarion.hyperborea.core.system.DeclaredComponent
import com.nettarion.hyperborea.core.test.buildSystemSnapshot
import org.junit.Before
import org.junit.Test

class IfitEcosystemManagerTest {

    private lateinit var manager: IfitEcosystemManager

    @Before
    fun setUp() {
        manager = IfitEcosystemManager()
    }

    // --- Prerequisite list ---

    @Test
    fun `manager declares the three iFit-coexistence prerequisites`() {
        assertThat(manager.prerequisites.map { it.id }).containsExactly(
            "ifit-standalone-stopped",
            "glassos-service-stopped",
            "eru-usb-receiver-disabled",
        )
    }

    @Test
    fun `none of the prerequisites declare a fulfill lambda`() {
        // Active fulfillment is the deploy script's job (`pm disable-user`); at
        // runtime we only verify, never recover.
        assertThat(manager.prerequisites.map { it.fulfill }).containsExactly(null, null, null)
    }

    // --- glassos-service-stopped ---

    private fun glassosPrereq() = manager.prerequisites.first { it.id == "glassos-service-stopped" }

    @Test
    fun `glassos prerequisite is met when no glassos components`() {
        val snapshot = buildSystemSnapshot(components = emptyList())
        assertThat(glassosPrereq().isMet(snapshot)).isTrue()
    }

    @Test
    fun `glassos prerequisite is NOT met when glassos is RUNNING`() {
        val snapshot = buildSystemSnapshot(
            components = listOf(buildGlassosComponent(ComponentState.RUNNING)),
        )
        assertThat(glassosPrereq().isMet(snapshot)).isFalse()
    }

    @Test
    fun `glassos prerequisite is met when glassos is DISABLED`() {
        val snapshot = buildSystemSnapshot(
            components = listOf(buildGlassosComponent(ComponentState.DISABLED)),
        )
        assertThat(glassosPrereq().isMet(snapshot)).isTrue()
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

    // --- Helpers ---

    private fun buildGlassosComponent(state: ComponentState) = DeclaredComponent(
        packageName = "com.ifit.glassos_service",
        className = "com.ifit.glassos_service.GlassOSService",
        type = ComponentType.SERVICE,
        state = state,
    )

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
}
