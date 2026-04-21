package com.sendspindroid.logging

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LogcatBridgeInstrumentedTest {

    private lateinit var tempDir: File
    private lateinit var writer: LogFileWriter
    private lateinit var scope: CoroutineScope
    private lateinit var bridge: LogcatBridge

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(ctx.cacheDir, "logbridge-test-${System.nanoTime()}")
        tempDir.mkdirs()
        writer = LogFileWriter(tempDir, maxFiles = 3, maxBytesPerFile = 10 * 1024 * 1024)
        writer.init()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        bridge = LogcatBridge(writer, scope)
    }

    @After
    fun tearDown() {
        bridge.stop()
        tempDir.deleteRecursively()
    }

    @Ignore("requires emulator")
    @Test
    fun bridge_captures_logd_calls_into_file() {
        bridge.start(LogLevel.DEBUG)
        val probe = "bridge-probe-${UUID.randomUUID()}"
        Log.d("SendSpin.TestProbe", probe)

        // Poll up to 5 seconds (logcat has some delivery lag on CI emulators).
        val deadline = System.currentTimeMillis() + 5_000
        var found = false
        while (System.currentTimeMillis() < deadline && !found) {
            val content = writer.currentFiles().joinToString("\n") { it.readText() }
            if (content.contains(probe)) {
                found = true
            } else {
                Thread.sleep(200)
            }
        }
        assertTrue("probe line should appear in log file within 5s", found)
    }

    @Ignore("requires emulator")
    @Test
    fun bridge_stop_halts_capture() {
        bridge.start(LogLevel.DEBUG)
        Thread.sleep(500)
        bridge.stop()
        Thread.sleep(200)

        val baselineSize = writer.currentFiles().sumOf { it.length() }
        val probe = "bridge-poststop-${UUID.randomUUID()}"
        Log.d("SendSpin.TestProbe", probe)

        Thread.sleep(2_000)
        val postSize = writer.currentFiles().sumOf { it.length() }
        val content = writer.currentFiles().joinToString("\n") { it.readText() }

        assertFalse("probe written after stop must not appear", content.contains(probe))
        assertTrue("file must not grow materially after stop", (postSize - baselineSize) < 1024)
    }
}
