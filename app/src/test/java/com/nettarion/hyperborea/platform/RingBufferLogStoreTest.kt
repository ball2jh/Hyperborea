package com.nettarion.hyperborea.platform

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.LogLevel
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class RingBufferLogStoreTest {

    private lateinit var store: RingBufferLogStore

    @Before
    fun setUp() {
        store = RingBufferLogStore()
    }

    // --- AppLogger methods create correct LogEntry level ---

    @Test
    fun `d() creates DEBUG entry`() {
        store.d("Tag", "debug message")
        val entry = store.entries.value.single()
        assertThat(entry.level).isEqualTo(LogLevel.DEBUG)
        assertThat(entry.tag).isEqualTo("Tag")
        assertThat(entry.message).isEqualTo("debug message")
    }

    @Test
    fun `i() creates INFO entry`() {
        store.i("Tag", "info message")
        val entry = store.entries.value.single()
        assertThat(entry.level).isEqualTo(LogLevel.INFO)
    }

    @Test
    fun `w() creates WARN entry`() {
        store.w("Tag", "warn message")
        val entry = store.entries.value.single()
        assertThat(entry.level).isEqualTo(LogLevel.WARN)
    }

    @Test
    fun `e() creates ERROR entry`() {
        store.e("Tag", "error message")
        val entry = store.entries.value.single()
        assertThat(entry.level).isEqualTo(LogLevel.ERROR)
    }

    // --- Error with and without throwable ---

    @Test
    fun `e() without throwable has null throwable field`() {
        store.e("Tag", "error")
        val entry = store.entries.value.single()
        assertThat(entry.throwable).isNull()
    }

    @Test
    fun `e() with throwable captures stack trace`() {
        val exception = RuntimeException("boom")
        store.e("Tag", "error", exception)
        val entry = store.entries.value.single()
        assertThat(entry.throwable).contains("RuntimeException")
        assertThat(entry.throwable).contains("boom")
    }

    // --- StateFlow updates ---

    @Test
    fun `entries StateFlow updates on each log`() {
        store.d("A", "first")
        assertThat(store.entries.value).hasSize(1)
        store.d("B", "second")
        assertThat(store.entries.value).hasSize(2)
    }

    @Test
    fun `size StateFlow updates on each log`() {
        assertThat(store.size.value).isEqualTo(0)
        store.d("A", "first")
        assertThat(store.size.value).isEqualTo(1)
        store.d("B", "second")
        assertThat(store.size.value).isEqualTo(2)
    }

    // --- Clear ---

    @Test
    fun `clear empties buffer`() {
        store.d("A", "msg")
        store.i("B", "msg")
        store.clear()
        assertThat(store.entries.value).isEmpty()
        assertThat(store.size.value).isEqualTo(0)
    }

    // --- Ring buffer eviction ---

    @Test
    fun `ring buffer evicts oldest entries at capacity`() {
        repeat(5001) { i ->
            store.d("Tag", "message $i")
        }
        assertThat(store.size.value).isEqualTo(5000)
        assertThat(store.entries.value).hasSize(5000)
        val oldest = store.entries.value.first()
        assertThat(oldest.message).isEqualTo("message 1")
    }

    // --- Export format ---

    @Test
    fun `export contains header`() {
        val output = store.export()
        assertThat(output).contains("=== Hyperborea Diagnostic Log ===")
    }

    @Test
    fun `export contains entry count`() {
        store.d("Tag", "msg")
        store.i("Tag", "msg2")
        val output = store.export()
        assertThat(output).contains("Entries: 2")
    }

    @Test
    fun `export contains formatted entries`() {
        store.i("MyTag", "hello world")
        val output = store.export()
        assertThat(output).contains("I/MyTag: hello world")
    }

    @Test
    fun `export empty store has zero entries`() {
        val output = store.export()
        assertThat(output).contains("Entries: 0")
    }

    // --- Concurrent logging thread safety ---

    @Test
    fun `concurrent logging does not lose entries`() = runTest {
        val threads = (1..10).map { threadNum ->
            Thread {
                repeat(100) { i ->
                    store.d("Thread-$threadNum", "msg $i")
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertThat(store.size.value).isEqualTo(1000)
    }
}
