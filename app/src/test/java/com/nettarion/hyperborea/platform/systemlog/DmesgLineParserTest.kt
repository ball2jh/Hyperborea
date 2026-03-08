package com.nettarion.hyperborea.platform.systemlog

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.LogLevel
import com.nettarion.hyperborea.core.system.SystemLogSource
import org.junit.Test

class DmesgLineParserTest {

    // Boot time: 2024-01-15 12:00:00.000 UTC
    private val bootTimeMillis = 1705320000000L

    // --- Valid lines ---

    @Test
    fun `parses standard dmesg line`() {
        val line = "[  123.456789] usb 1-1: new high-speed USB device"
        val entry = DmesgLineParser.parse(line, bootTimeMillis)
        assertThat(entry).isNotNull()
        assertThat(entry!!.message).isEqualTo("usb 1-1: new high-speed USB device")
        assertThat(entry.tag).isEqualTo("kernel")
        assertThat(entry.pid).isEqualTo(0)
        assertThat(entry.tid).isEqualTo(0)
        assertThat(entry.source).isEqualTo(SystemLogSource.DMESG)
    }

    @Test
    fun `parses line with zero uptime`() {
        val line = "[    0.000000] Booting Linux"
        val entry = DmesgLineParser.parse(line, bootTimeMillis)
        assertThat(entry).isNotNull()
        assertThat(entry!!.timestamp).isEqualTo(bootTimeMillis)
    }

    @Test
    fun `parses line with large uptime`() {
        val line = "[99999.123456] some message"
        val entry = DmesgLineParser.parse(line, bootTimeMillis)
        assertThat(entry).isNotNull()
        assertThat(entry!!.timestamp).isEqualTo(bootTimeMillis + 99999123L)
    }

    // --- Timestamp conversion ---

    @Test
    fun `timestamp is boot time plus uptime offset`() {
        val line = "[   10.000000] init started"
        val entry = DmesgLineParser.parse(line, bootTimeMillis)
        assertThat(entry).isNotNull()
        assertThat(entry!!.timestamp).isEqualTo(bootTimeMillis + 10000L)
    }

    @Test
    fun `timestamp includes milliseconds from uptime`() {
        val line = "[    1.500000] half second after boot"
        val entry = DmesgLineParser.parse(line, bootTimeMillis)
        assertThat(entry).isNotNull()
        assertThat(entry!!.timestamp).isEqualTo(bootTimeMillis + 1500L)
    }

    // --- Level inference ---

    @Test
    fun `error keyword maps to ERROR`() {
        val line = "[    1.000000] usb error: device not responding"
        val entry = DmesgLineParser.parse(line, bootTimeMillis)
        assertThat(entry!!.level).isEqualTo(LogLevel.ERROR)
    }

    @Test
    fun `fail keyword maps to ERROR`() {
        val line = "[    1.000000] Failed to initialize device"
        val entry = DmesgLineParser.parse(line, bootTimeMillis)
        assertThat(entry!!.level).isEqualTo(LogLevel.ERROR)
    }

    @Test
    fun `panic keyword maps to ERROR`() {
        val line = "[    1.000000] Kernel panic - not syncing"
        val entry = DmesgLineParser.parse(line, bootTimeMillis)
        assertThat(entry!!.level).isEqualTo(LogLevel.ERROR)
    }

    @Test
    fun `fatal keyword maps to ERROR`() {
        val line = "[    1.000000] fatal exception in interrupt handler"
        val entry = DmesgLineParser.parse(line, bootTimeMillis)
        assertThat(entry!!.level).isEqualTo(LogLevel.ERROR)
    }

    @Test
    fun `warning keyword maps to WARN`() {
        val line = "[    1.000000] WARNING: CPU temperature above threshold"
        val entry = DmesgLineParser.parse(line, bootTimeMillis)
        assertThat(entry!!.level).isEqualTo(LogLevel.WARN)
    }

    @Test
    fun `warn keyword maps to WARN`() {
        val line = "[    1.000000] warn: deprecated API usage"
        val entry = DmesgLineParser.parse(line, bootTimeMillis)
        assertThat(entry!!.level).isEqualTo(LogLevel.WARN)
    }

    @Test
    fun `deprecated keyword maps to WARN`() {
        val line = "[    1.000000] deprecated sysctl used"
        val entry = DmesgLineParser.parse(line, bootTimeMillis)
        assertThat(entry!!.level).isEqualTo(LogLevel.WARN)
    }

    @Test
    fun `normal message maps to INFO`() {
        val line = "[    1.000000] usb 1-1: new high-speed USB device"
        val entry = DmesgLineParser.parse(line, bootTimeMillis)
        assertThat(entry!!.level).isEqualTo(LogLevel.INFO)
    }

    // --- Source ---

    @Test
    fun `source is always DMESG`() {
        val line = "[    1.000000] any message"
        assertThat(DmesgLineParser.parse(line, bootTimeMillis)!!.source).isEqualTo(SystemLogSource.DMESG)
    }

    // --- Tag ---

    @Test
    fun `tag is always kernel`() {
        val line = "[    1.000000] any message"
        assertThat(DmesgLineParser.parse(line, bootTimeMillis)!!.tag).isEqualTo("kernel")
    }

    // --- Malformed input ---

    @Test
    fun `returns null for empty string`() {
        assertThat(DmesgLineParser.parse("", bootTimeMillis)).isNull()
    }

    @Test
    fun `returns null for garbage`() {
        assertThat(DmesgLineParser.parse("not a dmesg line", bootTimeMillis)).isNull()
    }

    @Test
    fun `returns null for missing timestamp brackets`() {
        assertThat(DmesgLineParser.parse("1.000000 some message", bootTimeMillis)).isNull()
    }

    @Test
    fun `returns null for empty message`() {
        assertThat(DmesgLineParser.parse("[    1.000000] ", bootTimeMillis)).isNull()
    }
}
