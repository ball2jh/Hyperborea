package com.nettarion.hyperborea.platform.systemlog

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessRunnerTest {

    private fun fakeProcess(
        stdout: String,
        exitValue: Int = 0,
    ): Process = object : Process() {
        override fun getOutputStream() = System.out
        override fun getInputStream(): InputStream = ByteArrayInputStream(stdout.toByteArray())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = exitValue
        override fun exitValue(): Int = exitValue
        override fun destroy() {}
    }

    @Test
    fun `emits lines from process stdout`() = runTest {
        val runner = ProcessRunner(processFactory = {
            fakeProcess("line1\nline2\nline3\n")
        })

        val lines = runner.run("test-command").take(3).toList()
        assertThat(lines).containsExactly("line1", "line2", "line3").inOrder()
    }

    @Test
    fun `command is wrapped with su -c`() = runTest {
        var capturedArgs: Array<String>? = null
        val runner = ProcessRunner(processFactory = { args ->
            capturedArgs = args
            fakeProcess("done\n")
        })

        runner.run("logcat -v threadtime").take(1).toList()
        assertThat(capturedArgs).isNotNull()
        assertThat(capturedArgs!!.toList()).containsExactly("su", "-c", "logcat -v threadtime").inOrder()
    }

    @Test
    fun `completes flow after max restarts exceeded`() = runTest {
        var startCount = 0
        val runner = ProcessRunner(maxRestarts = 2, processFactory = {
            startCount++
            fakeProcess("") // immediately EOF → triggers restart
        })

        val lines = runner.run("test").toList()
        assertThat(lines).isEmpty()
        // Initial start + 2 restarts = 3 total
        assertThat(startCount).isEqualTo(3)
    }

    @Test
    fun `cancellation stops collection`() = runTest {
        val runner = ProcessRunner(processFactory = {
            fakeProcess("line1\nline2\n")
        })

        val collected = mutableListOf<String>()
        val job = launch {
            runner.run("test").collect { collected.add(it) }
        }

        advanceUntilIdle()
        job.cancel()
        // Should have collected at least some lines without hanging
        assertThat(collected).isNotEmpty()
    }

    @Test
    fun `handles process creation failure`() = runTest {
        val runner = ProcessRunner(processFactory = {
            throw SecurityException("su not available")
        })

        var caughtException: Throwable? = null
        try {
            runner.run("test").toList()
        } catch (e: Exception) {
            caughtException = e
        }
        assertThat(caughtException).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun `emits empty list for empty process output`() = runTest {
        val runner = ProcessRunner(maxRestarts = 0, processFactory = {
            fakeProcess("")
        })

        val lines = runner.run("test").toList()
        assertThat(lines).isEmpty()
    }
}
