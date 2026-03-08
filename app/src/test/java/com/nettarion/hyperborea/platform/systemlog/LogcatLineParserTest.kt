package com.nettarion.hyperborea.platform.systemlog

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.LogLevel
import com.nettarion.hyperborea.core.system.SystemLogSource
import org.junit.Test

class LogcatLineParserTest {

    // --- Valid lines ---

    @Test
    fun `parses standard threadtime line`() {
        val line = "01-15 12:34:56.789  1234  5678 D MyTag  : Hello world"
        val entry = LogcatLineParser.parse(line)
        assertThat(entry).isNotNull()
        assertThat(entry!!.tag).isEqualTo("MyTag")
        assertThat(entry.message).isEqualTo("Hello world")
        assertThat(entry.pid).isEqualTo(1234)
        assertThat(entry.tid).isEqualTo(5678)
        assertThat(entry.level).isEqualTo(LogLevel.DEBUG)
        assertThat(entry.source).isEqualTo(SystemLogSource.LOGCAT)
    }

    @Test
    fun `parses line with no extra spaces in tag`() {
        val line = "03-01 08:00:00.000   100   200 I ActivityManager: Start proc"
        val entry = LogcatLineParser.parse(line)
        assertThat(entry).isNotNull()
        assertThat(entry!!.tag).isEqualTo("ActivityManager")
        assertThat(entry.message).isEqualTo("Start proc")
    }

    @Test
    fun `parses line with colon in message`() {
        val line = "03-01 08:00:00.000   100   200 I Tag: key: value: data"
        val entry = LogcatLineParser.parse(line)
        assertThat(entry).isNotNull()
        assertThat(entry!!.message).isEqualTo("key: value: data")
    }

    @Test
    fun `parses empty message`() {
        val line = "03-01 08:00:00.000   100   200 I Tag: "
        val entry = LogcatLineParser.parse(line)
        assertThat(entry).isNotNull()
        assertThat(entry!!.message).isEmpty()
    }

    // --- Priority mapping ---

    @Test
    fun `V maps to DEBUG`() {
        val line = "01-01 00:00:00.000     1     1 V Tag: msg"
        assertThat(LogcatLineParser.parse(line)!!.level).isEqualTo(LogLevel.DEBUG)
    }

    @Test
    fun `D maps to DEBUG`() {
        val line = "01-01 00:00:00.000     1     1 D Tag: msg"
        assertThat(LogcatLineParser.parse(line)!!.level).isEqualTo(LogLevel.DEBUG)
    }

    @Test
    fun `I maps to INFO`() {
        val line = "01-01 00:00:00.000     1     1 I Tag: msg"
        assertThat(LogcatLineParser.parse(line)!!.level).isEqualTo(LogLevel.INFO)
    }

    @Test
    fun `W maps to WARN`() {
        val line = "01-01 00:00:00.000     1     1 W Tag: msg"
        assertThat(LogcatLineParser.parse(line)!!.level).isEqualTo(LogLevel.WARN)
    }

    @Test
    fun `E maps to ERROR`() {
        val line = "01-01 00:00:00.000     1     1 E Tag: msg"
        assertThat(LogcatLineParser.parse(line)!!.level).isEqualTo(LogLevel.ERROR)
    }

    @Test
    fun `F maps to ERROR`() {
        val line = "01-01 00:00:00.000     1     1 F Tag: msg"
        assertThat(LogcatLineParser.parse(line)!!.level).isEqualTo(LogLevel.ERROR)
    }

    @Test
    fun `A maps to ERROR`() {
        val line = "01-01 00:00:00.000     1     1 A Tag: msg"
        assertThat(LogcatLineParser.parse(line)!!.level).isEqualTo(LogLevel.ERROR)
    }

    // --- Timestamp ---

    @Test
    fun `timestamp is positive`() {
        val line = "06-15 12:00:00.000   100   200 I Tag: msg"
        val entry = LogcatLineParser.parse(line)
        assertThat(entry).isNotNull()
        assertThat(entry!!.timestamp).isGreaterThan(0L)
    }

    // --- Source ---

    @Test
    fun `source is always LOGCAT`() {
        val line = "01-01 00:00:00.000     1     1 D Tag: msg"
        assertThat(LogcatLineParser.parse(line)!!.source).isEqualTo(SystemLogSource.LOGCAT)
    }

    // --- Malformed / unparseable input ---

    @Test
    fun `returns null for empty string`() {
        assertThat(LogcatLineParser.parse("")).isNull()
    }

    @Test
    fun `returns null for beginning-of-log marker`() {
        assertThat(LogcatLineParser.parse("--------- beginning of main")).isNull()
    }

    @Test
    fun `returns null for garbage`() {
        assertThat(LogcatLineParser.parse("not a logcat line at all")).isNull()
    }

    @Test
    fun `returns null for partial line`() {
        assertThat(LogcatLineParser.parse("01-15 12:34:56.789")).isNull()
    }

    @Test
    fun `returns null for missing priority`() {
        assertThat(LogcatLineParser.parse("01-15 12:34:56.789  1234  5678 Tag: msg")).isNull()
    }
}
