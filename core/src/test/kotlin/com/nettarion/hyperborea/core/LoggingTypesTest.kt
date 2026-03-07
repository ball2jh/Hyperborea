package com.nettarion.hyperborea.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LoggingTypesTest {

    @Test
    fun `LogLevel has exactly four entries`() {
        assertThat(LogLevel.entries).hasSize(4)
    }

    @Test
    fun `LogLevel ordinals match severity order`() {
        assertThat(LogLevel.DEBUG.ordinal).isEqualTo(0)
        assertThat(LogLevel.INFO.ordinal).isEqualTo(1)
        assertThat(LogLevel.WARN.ordinal).isEqualTo(2)
        assertThat(LogLevel.ERROR.ordinal).isEqualTo(3)
    }

    @Test
    fun `LogLevel valueOf round-trip`() {
        for (level in LogLevel.entries) {
            assertThat(level).isEqualTo(LogLevel.valueOf(level.name))
        }
    }

    @Test
    fun `LogEntry without throwable`() {
        val entry = LogEntry(
            timestamp = 1000L,
            level = LogLevel.INFO,
            tag = "Test",
            message = "Hello",
        )
        assertThat(entry.throwable).isNull()
        assertThat(entry.timestamp).isEqualTo(1000L)
        assertThat(entry.level).isEqualTo(LogLevel.INFO)
        assertThat(entry.tag).isEqualTo("Test")
        assertThat(entry.message).isEqualTo("Hello")
    }

    @Test
    fun `LogEntry with throwable`() {
        val entry = LogEntry(
            timestamp = 2000L,
            level = LogLevel.ERROR,
            tag = "Crash",
            message = "Something failed",
            throwable = "java.lang.RuntimeException: boom",
        )
        assertThat(entry.throwable).isEqualTo("java.lang.RuntimeException: boom")
    }

    @Test
    fun `LogEntry data class equality`() {
        val a = LogEntry(1000L, LogLevel.DEBUG, "Tag", "Msg", null)
        val b = LogEntry(1000L, LogLevel.DEBUG, "Tag", "Msg", null)
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `LogEntry inequality on different level`() {
        val a = LogEntry(1000L, LogLevel.DEBUG, "Tag", "Msg")
        val b = LogEntry(1000L, LogLevel.ERROR, "Tag", "Msg")
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `LogEntry inequality on different message`() {
        val a = LogEntry(1000L, LogLevel.INFO, "Tag", "Msg A")
        val b = LogEntry(1000L, LogLevel.INFO, "Tag", "Msg B")
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `LogEntry default throwable is null`() {
        val entry = LogEntry(0L, LogLevel.DEBUG, "T", "M")
        assertThat(entry.throwable).isNull()
    }
}
