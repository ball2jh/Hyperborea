package com.nettarion.hyperborea.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AdapterStateTest {

    @Test
    fun `sealed interface has exactly four subtypes`() {
        val subtypes = listOf(
            AdapterState.Inactive,
            AdapterState.Activating,
            AdapterState.Active,
            AdapterState.Error("test"),
        )
        assertThat(subtypes).hasSize(4)
    }

    @Test
    fun `data objects are singletons`() {
        assertThat(AdapterState.Inactive).isSameInstanceAs(AdapterState.Inactive)
        assertThat(AdapterState.Activating).isSameInstanceAs(AdapterState.Activating)
        assertThat(AdapterState.Active).isSameInstanceAs(AdapterState.Active)
    }

    @Test
    fun `all subtypes implement AdapterState`() {
        assertThat(AdapterState.Inactive).isInstanceOf(AdapterState::class.java)
        assertThat(AdapterState.Activating).isInstanceOf(AdapterState::class.java)
        assertThat(AdapterState.Active).isInstanceOf(AdapterState::class.java)
        assertThat(AdapterState.Error("err")).isInstanceOf(AdapterState::class.java)
    }

    @Test
    fun `Error carries message`() {
        val error = AdapterState.Error("USB disconnected")
        assertThat(error.message).isEqualTo("USB disconnected")
    }

    @Test
    fun `Error carries optional cause`() {
        val cause = RuntimeException("boom")
        val error = AdapterState.Error("failed", cause)
        assertThat(error.cause).isSameInstanceAs(cause)
    }

    @Test
    fun `Error cause defaults to null`() {
        val error = AdapterState.Error("failed")
        assertThat(error.cause).isNull()
    }

    @Test
    fun `Error equality based on message and cause`() {
        val cause = RuntimeException("boom")
        val a = AdapterState.Error("failed", cause)
        val b = AdapterState.Error("failed", cause)
        assertEquals(a, b)
    }

    @Test
    fun `Error inequality on different messages`() {
        val a = AdapterState.Error("error A")
        val b = AdapterState.Error("error B")
        assertNotEquals(a, b)
    }

    @Test
    fun `Error toString includes message`() {
        val error = AdapterState.Error("USB disconnected")
        assertThat(error.toString()).contains("USB disconnected")
    }
}
