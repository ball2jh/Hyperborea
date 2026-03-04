package com.nettarion.hyperborea.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FulfillResultTest {

    @Test
    fun `Success is a singleton`() {
        assertThat(FulfillResult.Success).isSameInstanceAs(FulfillResult.Success)
    }

    @Test
    fun `Success implements FulfillResult`() {
        assertThat(FulfillResult.Success).isInstanceOf(FulfillResult::class.java)
    }

    @Test
    fun `Failed implements FulfillResult`() {
        assertThat(FulfillResult.Failed("err")).isInstanceOf(FulfillResult::class.java)
    }

    @Test
    fun `Failed carries reason`() {
        val result = FulfillResult.Failed("service not found")
        assertThat(result.reason).isEqualTo("service not found")
    }

    @Test
    fun `Failed carries optional cause`() {
        val cause = RuntimeException("boom")
        val result = FulfillResult.Failed("failed", cause)
        assertThat(result.cause).isSameInstanceAs(cause)
    }

    @Test
    fun `Failed cause defaults to null`() {
        val result = FulfillResult.Failed("failed")
        assertThat(result.cause).isNull()
    }

    @Test
    fun `Failed equality based on reason and cause`() {
        val cause = RuntimeException("boom")
        val a = FulfillResult.Failed("failed", cause)
        val b = FulfillResult.Failed("failed", cause)
        assertEquals(a, b)
    }

    @Test
    fun `Failed inequality on different reasons`() {
        val a = FulfillResult.Failed("reason A")
        val b = FulfillResult.Failed("reason B")
        assertNotEquals(a, b)
    }

    @Test
    fun `Failed toString includes reason`() {
        val result = FulfillResult.Failed("service not found")
        assertThat(result.toString()).contains("service not found")
    }
}
