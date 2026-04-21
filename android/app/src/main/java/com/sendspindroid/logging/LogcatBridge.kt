package com.sendspindroid.logging

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Reads the `logcat` subprocess filtered to the app's own PID and streams each line to
 * [LogFileWriter]. Manages the subprocess lifecycle as a function of the current [LogLevel].
 *
 * When [level] is [LogLevel.OFF], the bridge is fully stopped (no subprocess, no coroutines).
 * Level changes restart the subprocess with the new priority filter.
 *
 * If the subprocess dies unexpectedly, the reader writes a marker line and retries with 1s backoff,
 * capped at 3 retries per 60s window. After the cap the bridge gives up silently; the app's own
 * [AppLog] facade calls still work via plain `android.util.Log`.
 */
internal class LogcatBridge(
    private val writer: LogFileWriter,
    private val scope: CoroutineScope,
) {
    private val stateLock = Any()
    private var readerJob: Job? = null
    private var stderrJob: Job? = null
    private var process: java.lang.Process? = null

    fun start(level: LogLevel) {
        if (level == LogLevel.OFF) return
        synchronized(stateLock) {
            if (readerJob?.isActive == true) return
            spawn(level)
        }
    }

    fun stop() {
        synchronized(stateLock) {
            try {
                process?.destroy()
            } catch (_: Exception) { /* best-effort */ }
            process = null
            // Join briefly; avoid deadlocking the main thread if caller runs on it.
            runBlocking {
                try {
                    readerJob?.cancelAndJoin()
                    stderrJob?.cancelAndJoin()
                } catch (_: Exception) { /* ignore */ }
            }
            readerJob = null
            stderrJob = null
        }
    }

    fun setLevel(level: LogLevel) {
        if (level == LogLevel.OFF) {
            stop()
        } else {
            stop()
            start(level)
        }
    }

    private fun spawn(level: LogLevel) {
        val pid = android.os.Process.myPid().toString()
        val priority = level.logcatPriorityChar()
        val cmd = listOf("logcat", "-v", "threadtime", "--pid=$pid", "-T", "1", "*:$priority")

        val restartWindow = 60_000L
        val maxRestarts = 3

        readerJob = scope.launch(Dispatchers.IO) {
            var restartTimestamps = mutableListOf<Long>()
            while (isActive) {
                val proc = try {
                    ProcessBuilder(cmd).redirectErrorStream(false).start()
                } catch (t: Throwable) {
                    writer.appendLine("[bridge] failed to spawn logcat: ${t.message}")
                    return@launch
                }
                process = proc

                stderrJob = launch(Dispatchers.IO) {
                    try {
                        BufferedReader(InputStreamReader(proc.errorStream)).useLines { lines ->
                            for (line in lines) {
                                if (!isActive) break
                                writer.appendLine("[logcat-stderr] $line")
                            }
                        }
                    } catch (_: Exception) { /* ignore */ }
                }

                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
                        for (line in lines) {
                            if (!isActive) return@launch
                            writer.appendLine(line)
                        }
                    }
                } catch (_: Exception) {
                    // fall through to restart logic
                }

                if (!isActive) return@launch

                writer.appendLine("[bridge] logcat process ended unexpectedly, restarting")

                val now = System.currentTimeMillis()
                restartTimestamps = restartTimestamps.filter { now - it < restartWindow }.toMutableList()
                if (restartTimestamps.size >= maxRestarts) {
                    writer.appendLine("[bridge] giving up after $maxRestarts restart attempts in ${restartWindow / 1000}s")
                    return@launch
                }
                restartTimestamps.add(now)
                delay(1000)
            }
        }
    }
}
