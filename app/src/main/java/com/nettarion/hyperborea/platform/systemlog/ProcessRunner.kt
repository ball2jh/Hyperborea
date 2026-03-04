package com.nettarion.hyperborea.platform.systemlog

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Bridges shell process execution into a coroutine [Flow] of output lines.
 *
 * Tries `su -c "..."` for root access first; falls back to running the command
 * directly via `sh -c "..."` if `su` is unavailable. Auto-restarts the process
 * on unexpected death with exponential backoff (1s, 2s, 4s... up to [maxRestarts]).
 * Completes the flow when max restarts are exceeded.
 */
class ProcessRunner(
    private val maxRestarts: Int = 5,
    private val processFactory: (Array<String>) -> Process = { Runtime.getRuntime().exec(it) },
) {

    fun run(command: String): Flow<String> = callbackFlow {
        var restartCount = 0
        var backoffMs = 1000L

        while (isActive) {
            val process = try {
                processFactory(arrayOf("su", "-c", command))
            } catch (_: Exception) {
                // su unavailable — fall back to non-root shell
                try {
                    processFactory(arrayOf("sh", "-c", command))
                } catch (e: Exception) {
                    close(e)
                    return@callbackFlow
                }
            }

            try {
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line = reader.readLine()
                while (line != null && isActive) {
                    trySend(line)
                    line = reader.readLine()
                }
            } catch (_: Exception) {
                // Process stream closed or read error — fall through to restart logic
            } finally {
                process.destroy()
            }

            if (!isActive) break

            restartCount++
            if (restartCount > maxRestarts) {
                close()
                return@callbackFlow
            }

            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(16000L)
        }

        awaitClose()
    }
}
