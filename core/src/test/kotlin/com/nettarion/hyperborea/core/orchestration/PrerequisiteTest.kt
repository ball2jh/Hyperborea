package com.nettarion.hyperborea.core.orchestration

import com.nettarion.hyperborea.core.system.SystemController
import com.nettarion.hyperborea.core.system.SystemSnapshot

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.test.buildSystemSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PrerequisiteTest {

    @Test
    fun `equality is based on id only`() {
        val a = Prerequisite(id = "check-1", description = "First description", isMet = { true })
        val b = Prerequisite(id = "check-1", description = "Different description", isMet = { false })
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `inequality on different ids`() {
        val a = Prerequisite(id = "check-1", description = "Same", isMet = { true })
        val b = Prerequisite(id = "check-2", description = "Same", isMet = { true })
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `hashCode is based on id only`() {
        val a = Prerequisite(id = "check-1", description = "A", isMet = { true })
        val b = Prerequisite(id = "check-1", description = "B", isMet = { false })
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `toString includes id and description`() {
        val p = Prerequisite(id = "usb-check", description = "USB must be available", isMet = { true })
        assertThat(p.toString()).isEqualTo("Prerequisite(id=usb-check, description=USB must be available)")
    }

    @Test
    fun `isMet lambda evaluates correctly when true`() {
        val p = Prerequisite(id = "wifi", description = "WiFi enabled", isMet = { it.status.isWifiEnabled })
        val snapshot = buildSystemSnapshot(isWifiEnabled = true)
        assertThat(p.isMet(snapshot)).isTrue()
    }

    @Test
    fun `isMet lambda evaluates correctly when false`() {
        val p = Prerequisite(id = "wifi", description = "WiFi enabled", isMet = { it.status.isWifiEnabled })
        val snapshot = buildSystemSnapshot(isWifiEnabled = false)
        assertThat(p.isMet(snapshot)).isFalse()
    }

    @Test
    fun `set deduplication by id`() {
        val a = Prerequisite(id = "check-1", description = "A", isMet = { true })
        val b = Prerequisite(id = "check-1", description = "B", isMet = { false })
        val c = Prerequisite(id = "check-2", description = "C", isMet = { true })
        val set = setOf(a, b, c)
        assertThat(set).hasSize(2)
    }

    @Test
    fun `not equal to non-Prerequisite`() {
        val p = Prerequisite(id = "check", description = "desc", isMet = { true })
        assertThat(p.equals("not a prerequisite")).isFalse()
        assertThat(p.equals(null)).isFalse()
    }

    // --- fulfill ---

    @Test
    fun `fulfill defaults to null`() {
        val p = Prerequisite(id = "test", description = "desc", isMet = { true })
        assertThat(p.fulfill).isNull()
    }

    @Test
    fun `fulfill lambda is invoked and returns Success`() = runTest {
        val p = Prerequisite(
            id = "test",
            description = "desc",
            isMet = { true },
            fulfill = { FulfillResult.Success },
        )
        val result = p.fulfill!!.invoke(stubController())
        assertThat(result).isEqualTo(FulfillResult.Success)
    }

    @Test
    fun `fulfill lambda can return Failed`() = runTest {
        val p = Prerequisite(
            id = "test",
            description = "desc",
            isMet = { true },
            fulfill = { FulfillResult.Failed("could not stop service") },
        )
        val result = p.fulfill!!.invoke(stubController())
        assertThat(result).isInstanceOf(FulfillResult.Failed::class.java)
        assertThat((result as FulfillResult.Failed).reason).isEqualTo("could not stop service")
    }

    private fun stubController(defaultReturn: Boolean = false) = object : SystemController {
        override suspend fun stopService(packageName: String, className: String) = defaultReturn
        override suspend fun forceStopPackage(packageName: String) = defaultReturn
        override suspend fun disablePackage(packageName: String) = defaultReturn
        override suspend fun enablePackage(packageName: String) = defaultReturn
        override suspend fun uninstallPackage(packageName: String) = defaultReturn
        override suspend fun disableComponent(packageName: String, className: String) = defaultReturn
        override suspend fun enableComponent(packageName: String, className: String) = defaultReturn
        override suspend fun grantUsbPermission(packageName: String) = defaultReturn
        override suspend fun revokeUsbPermissions(packageName: String) = defaultReturn
    }
}
