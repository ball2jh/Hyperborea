package com.nettarion.hyperborea.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SystemLogTypesTest {

    // --- SystemLogSource ---

    @Test
    fun `SystemLogSource has exactly two values`() {
        assertThat(SystemLogSource.entries).hasSize(2)
    }

    @Test
    fun `SystemLogSource values are LOGCAT and DMESG`() {
        assertThat(SystemLogSource.entries).containsExactly(
            SystemLogSource.LOGCAT,
            SystemLogSource.DMESG,
        )
    }

    // --- SystemLogEntry ---

    @Test
    fun `SystemLogEntry construction and field access`() {
        val entry = SystemLogEntry(
            timestamp = 1000L,
            level = LogLevel.INFO,
            tag = "TestTag",
            message = "hello",
            pid = 42,
            tid = 43,
            source = SystemLogSource.LOGCAT,
        )
        assertThat(entry.timestamp).isEqualTo(1000L)
        assertThat(entry.level).isEqualTo(LogLevel.INFO)
        assertThat(entry.tag).isEqualTo("TestTag")
        assertThat(entry.message).isEqualTo("hello")
        assertThat(entry.pid).isEqualTo(42)
        assertThat(entry.tid).isEqualTo(43)
        assertThat(entry.source).isEqualTo(SystemLogSource.LOGCAT)
    }

    @Test
    fun `SystemLogEntry equality`() {
        val a = SystemLogEntry(1L, LogLevel.DEBUG, "T", "m", 1, 1, SystemLogSource.LOGCAT)
        val b = SystemLogEntry(1L, LogLevel.DEBUG, "T", "m", 1, 1, SystemLogSource.LOGCAT)
        assertEquals(a, b)
    }

    @Test
    fun `SystemLogEntry inequality on different source`() {
        val a = SystemLogEntry(1L, LogLevel.DEBUG, "T", "m", 1, 1, SystemLogSource.LOGCAT)
        val b = SystemLogEntry(1L, LogLevel.DEBUG, "T", "m", 1, 1, SystemLogSource.DMESG)
        assertNotEquals(a, b)
    }

    @Test
    fun `SystemLogEntry copy`() {
        val entry = SystemLogEntry(1L, LogLevel.DEBUG, "T", "m", 1, 1, SystemLogSource.LOGCAT)
        val copy = entry.copy(level = LogLevel.ERROR)
        assertThat(copy.level).isEqualTo(LogLevel.ERROR)
        assertThat(copy.tag).isEqualTo("T")
    }

    // --- CaptureConfig ---

    @Test
    fun `CaptureConfig defaults`() {
        val config = CaptureConfig()
        assertThat(config.logcat).isTrue()
        assertThat(config.dmesg).isFalse()
        assertThat(config.logcatFilterSpecs).isEmpty()
        assertThat(config.logcatBuffers).containsExactly("main", "system")
    }

    @Test
    fun `CaptureConfig custom values`() {
        val config = CaptureConfig(
            logcat = false,
            dmesg = true,
            logcatFilterSpecs = listOf("ifit:V", "UsbDeviceManager:D"),
            logcatBuffers = setOf("main"),
        )
        assertThat(config.logcat).isFalse()
        assertThat(config.dmesg).isTrue()
        assertThat(config.logcatFilterSpecs).containsExactly("ifit:V", "UsbDeviceManager:D")
        assertThat(config.logcatBuffers).containsExactly("main")
    }

    @Test
    fun `CaptureConfig equality`() {
        val a = CaptureConfig()
        val b = CaptureConfig()
        assertEquals(a, b)
    }

    // --- CaptureState ---

    @Test
    fun `CaptureState sealed interface has four subtypes`() {
        val subtypes = listOf(
            CaptureState.Inactive,
            CaptureState.Starting,
            CaptureState.Active,
            CaptureState.Error("test"),
        )
        assertThat(subtypes).hasSize(4)
    }

    @Test
    fun `CaptureState data objects are singletons`() {
        assertThat(CaptureState.Inactive).isSameInstanceAs(CaptureState.Inactive)
        assertThat(CaptureState.Starting).isSameInstanceAs(CaptureState.Starting)
        assertThat(CaptureState.Active).isSameInstanceAs(CaptureState.Active)
    }

    @Test
    fun `CaptureState all subtypes implement CaptureState`() {
        assertThat(CaptureState.Inactive).isInstanceOf(CaptureState::class.java)
        assertThat(CaptureState.Starting).isInstanceOf(CaptureState::class.java)
        assertThat(CaptureState.Active).isInstanceOf(CaptureState::class.java)
        assertThat(CaptureState.Error("err")).isInstanceOf(CaptureState::class.java)
    }

    @Test
    fun `CaptureState Error carries message`() {
        val error = CaptureState.Error("logcat died")
        assertThat(error.message).isEqualTo("logcat died")
    }

    @Test
    fun `CaptureState Error carries optional cause`() {
        val cause = RuntimeException("boom")
        val error = CaptureState.Error("failed", cause)
        assertThat(error.cause).isSameInstanceAs(cause)
    }

    @Test
    fun `CaptureState Error cause defaults to null`() {
        val error = CaptureState.Error("failed")
        assertThat(error.cause).isNull()
    }

    @Test
    fun `CaptureState Error equality`() {
        val cause = RuntimeException("boom")
        val a = CaptureState.Error("failed", cause)
        val b = CaptureState.Error("failed", cause)
        assertEquals(a, b)
    }

    @Test
    fun `CaptureState Error inequality on different messages`() {
        val a = CaptureState.Error("error A")
        val b = CaptureState.Error("error B")
        assertNotEquals(a, b)
    }

    @Test
    fun `CaptureState Error toString includes message`() {
        val error = CaptureState.Error("logcat died")
        assertThat(error.toString()).contains("logcat died")
    }
}
