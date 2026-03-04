package com.nettarion.hyperborea.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ClientInfoTest {

    @Test
    fun `data class carries all fields`() {
        val info = ClientInfo(id = "AA:BB:CC:DD:EE:FF", protocol = "FTMS", connectedAt = 1000L)
        assertThat(info.id).isEqualTo("AA:BB:CC:DD:EE:FF")
        assertThat(info.protocol).isEqualTo("FTMS")
        assertThat(info.connectedAt).isEqualTo(1000L)
    }

    @Test
    fun `equality based on all fields`() {
        val a = ClientInfo(id = "host:1234", protocol = "DIRCON", connectedAt = 500L)
        val b = ClientInfo(id = "host:1234", protocol = "DIRCON", connectedAt = 500L)
        assertEquals(a, b)
    }

    @Test
    fun `inequality on different id`() {
        val a = ClientInfo(id = "host:1234", protocol = "DIRCON", connectedAt = 500L)
        val b = ClientInfo(id = "host:5678", protocol = "DIRCON", connectedAt = 500L)
        assertNotEquals(a, b)
    }

    @Test
    fun `inequality on different protocol`() {
        val a = ClientInfo(id = "host:1234", protocol = "FTMS", connectedAt = 500L)
        val b = ClientInfo(id = "host:1234", protocol = "DIRCON", connectedAt = 500L)
        assertNotEquals(a, b)
    }

    @Test
    fun `inequality on different connectedAt`() {
        val a = ClientInfo(id = "host:1234", protocol = "FTMS", connectedAt = 500L)
        val b = ClientInfo(id = "host:1234", protocol = "FTMS", connectedAt = 600L)
        assertNotEquals(a, b)
    }

    @Test
    fun `copy preserves unchanged fields`() {
        val original = ClientInfo(id = "host:1234", protocol = "DIRCON", connectedAt = 500L)
        val copy = original.copy(protocol = "FTMS")
        assertThat(copy.id).isEqualTo("host:1234")
        assertThat(copy.protocol).isEqualTo("FTMS")
        assertThat(copy.connectedAt).isEqualTo(500L)
    }

    @Test
    fun `toString includes all fields`() {
        val info = ClientInfo(id = "dev1", protocol = "FTMS", connectedAt = 100L)
        val str = info.toString()
        assertThat(str).contains("dev1")
        assertThat(str).contains("FTMS")
        assertThat(str).contains("100")
    }
}
