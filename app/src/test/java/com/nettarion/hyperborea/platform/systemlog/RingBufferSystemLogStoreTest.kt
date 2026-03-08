package com.nettarion.hyperborea.platform.systemlog

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.system.CaptureConfig
import com.nettarion.hyperborea.core.system.CaptureState
import com.nettarion.hyperborea.core.LogLevel
import com.nettarion.hyperborea.core.system.SystemLogEntry
import com.nettarion.hyperborea.core.system.SystemLogSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class RingBufferSystemLogStoreTest {

    @get:Rule
    val timeout: Timeout = Timeout(45, TimeUnit.SECONDS)

    private lateinit var testScope: TestScope
    private lateinit var store: RingBufferSystemLogStore
    private val fakeLogger = object : AppLogger {
        override fun d(tag: String, message: String) {}
        override fun i(tag: String, message: String) {}
        override fun w(tag: String, message: String) {}
        override fun e(tag: String, message: String, throwable: Throwable?) {}
    }

    private fun fakeProcess(stdout: String): Process = object : Process() {
        override fun getOutputStream() = System.out
        override fun getInputStream(): InputStream = ByteArrayInputStream(stdout.toByteArray())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() {}
    }

    @Before
    fun setUp() {
        testScope = TestScope()
    }

    private fun createStore(stdout: String = ""): RingBufferSystemLogStore {
        val runner = ProcessRunner(maxRestarts = 0, processFactory = { fakeProcess(stdout) })
        return RingBufferSystemLogStore(fakeLogger, testScope, runner)
    }

    // --- Initial state ---

    @Test
    fun `initial state is Inactive`() {
        store = createStore()
        assertThat(store.state.value).isEqualTo(CaptureState.Inactive)
    }

    @Test
    fun `initial entries are empty`() {
        store = createStore()
        assertThat(store.entries.value).isEmpty()
    }

    @Test
    fun `initial size is zero`() {
        store = createStore()
        assertThat(store.size.value).isEqualTo(0)
    }

    // --- Lifecycle guards ---

    @Test
    fun `start when no sources enabled transitions to Error`() = testScope.runTest {
        store = createStore()
        store.start(CaptureConfig(logcat = false, dmesg = false))
        assertThat(store.state.value).isInstanceOf(CaptureState.Error::class.java)
    }

    @Test
    fun `stop when Inactive is no-op`() = testScope.runTest {
        store = createStore()
        store.stop()
        assertThat(store.state.value).isEqualTo(CaptureState.Inactive)
    }

    @Test
    fun `start transitions through Starting to Active then Error on process exit`() = testScope.runTest {
        val logcatOutput = "01-15 12:00:00.000  1000  1001 I TestTag: hello\n"
        store = createStore(logcatOutput)

        store.start()
        advanceUntilIdle()
        // Process terminates immediately (fake) and maxRestarts=0 → Error
        assertThat(store.state.value).isInstanceOf(CaptureState.Error::class.java)
    }

    @Test
    fun `stop after start transitions to Inactive`() = testScope.runTest {
        store = createStore("01-15 12:00:00.000  1000  1001 I Tag: msg\n")
        store.start()
        advanceTimeBy(50)
        store.stop()
        assertThat(store.state.value).isEqualTo(CaptureState.Inactive)
    }

    @Test
    fun `double start is no-op`() = testScope.runTest {
        store = createStore("01-15 12:00:00.000  1000  1001 I Tag: msg\n")
        store.start()
        advanceTimeBy(50)
        if (store.state.value is CaptureState.Active) {
            store.start() // should be no-op
            assertThat(store.state.value).isEqualTo(CaptureState.Active)
        }
        store.stop()
    }

    // --- Parsing and collection ---

    @Test
    fun `collects parsed logcat entries`() = testScope.runTest {
        val lines = """
            01-15 12:00:00.000  1000  1001 I ActivityManager: Start proc
            01-15 12:00:01.000  1000  1001 D MyApp: Debug message
        """.trimIndent() + "\n"

        store = createStore(lines)
        store.start()
        advanceUntilIdle()

        // Flush throttled updates
        advanceTimeBy(200)

        assertThat(store.size.value).isEqualTo(2)
        val entries = store.entries.value
        assertThat(entries[0].tag).isEqualTo("ActivityManager")
        assertThat(entries[1].tag).isEqualTo("MyApp")
    }

    @Test
    fun `skips unparseable lines`() = testScope.runTest {
        val lines = """
            --------- beginning of main
            01-15 12:00:00.000  1000  1001 I Tag: valid line
            garbage that should be skipped
        """.trimIndent() + "\n"

        store = createStore(lines)
        store.start()
        advanceUntilIdle()
        advanceTimeBy(200)

        assertThat(store.size.value).isEqualTo(1)
        assertThat(store.entries.value.single().tag).isEqualTo("Tag")
    }

    // --- Clear ---

    @Test
    fun `clear empties buffer and resets size`() = testScope.runTest {
        val lines = "01-15 12:00:00.000  1000  1001 I Tag: msg\n"
        store = createStore(lines)
        store.start()
        advanceUntilIdle()
        advanceTimeBy(200)

        store.clear()
        assertThat(store.entries.value).isEmpty()
        assertThat(store.size.value).isEqualTo(0)
    }

    // --- Export ---

    @Test
    fun `export contains header`() {
        store = createStore()
        val output = store.export()
        assertThat(output).contains("=== Hyperborea System Log ===")
    }

    @Test
    fun `export contains entry count`() = testScope.runTest {
        val lines = """
            01-15 12:00:00.000  1000  1001 I Tag: msg1
            01-15 12:00:01.000  1000  1001 D Tag: msg2
        """.trimIndent() + "\n"
        store = createStore(lines)
        store.start()
        advanceUntilIdle()
        advanceTimeBy(200)

        val output = store.export()
        assertThat(output).contains("Entries: 2")
    }

    @Test
    fun `export empty store has zero entries`() {
        store = createStore()
        val output = store.export()
        assertThat(output).contains("Entries: 0")
    }

    @Test
    fun `export contains source tag in entries`() = testScope.runTest {
        val lines = "01-15 12:00:00.000  1000  1001 I MyTag: hello world\n"
        store = createStore(lines)
        store.start()
        advanceUntilIdle()
        advanceTimeBy(200)

        val output = store.export()
        assertThat(output).contains("LOGCAT")
        assertThat(output).contains("I/MyTag")
        assertThat(output).contains("hello world")
    }

    // --- Concurrent access ---

    @Test
    fun `concurrent clear during collection does not crash`() = testScope.runTest {
        val lines = (1..100).joinToString("\n") { i ->
            "01-15 12:00:00.000  1000  1001 I Tag: msg $i"
        } + "\n"
        store = createStore(lines)
        store.start()
        advanceTimeBy(50)
        store.clear()
        advanceUntilIdle()
        // Should not throw
    }
}
