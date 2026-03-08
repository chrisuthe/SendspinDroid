package com.sendspindroid.debug

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [FileLogger] concurrent access safety.
 *
 * Verifies that rapid interleaved calls from multiple threads
 * do not crash or corrupt the logger state.
 */
class FileLoggerConcurrencyTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        tempDir = File(System.getProperty("java.io.tmpdir"), "filelogger-test-${System.nanoTime()}")
        tempDir.mkdirs()
    }

    @After
    fun tearDown() {
        FileLogger.isEnabled = false
        tempDir.deleteRecursively()
        unmockkAll()
    }

    @Test
    fun `concurrent logging from multiple threads does not crash`() {
        // Set up FileLogger with a real temp file
        val logFileField = FileLogger::class.java.getDeclaredField("logFile")
        logFileField.isAccessible = true
        val logFile = File(tempDir, "debug.log")
        logFile.writeText("=== Test ===\n")
        logFileField.set(FileLogger, logFile)
        FileLogger.isEnabled = true

        val threadCount = 8
        val messagesPerThread = 100
        val barrier = CyclicBarrier(threadCount)
        val errorCount = AtomicInteger(0)
        val latch = CountDownLatch(threadCount)

        val threads = (0 until threadCount).map { threadIdx ->
            Thread {
                try {
                    barrier.await(5, TimeUnit.SECONDS) // synchronize start
                    for (i in 0 until messagesPerThread) {
                        when (i % 5) {
                            0 -> FileLogger.i("Thread$threadIdx", "Info $i")
                            1 -> FileLogger.d("Thread$threadIdx", "Debug $i")
                            2 -> FileLogger.w("Thread$threadIdx", "Warn $i")
                            3 -> FileLogger.e("Thread$threadIdx", "Error $i")
                            4 -> FileLogger.section("Section $threadIdx-$i")
                        }
                    }
                } catch (e: Exception) {
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        threads.forEach { it.start() }
        assertTrue("Threads should complete within 10 seconds", latch.await(10, TimeUnit.SECONDS))
        assertEquals(0, errorCount.get())

        // Verify log file was written to
        assertTrue("Log file should exist", logFile.exists())
        assertTrue("Log file should have content", logFile.length() > 0)
    }

    @Test
    fun `concurrent enable and disable toggling does not crash`() {
        val logFileField = FileLogger::class.java.getDeclaredField("logFile")
        logFileField.isAccessible = true
        val logFile = File(tempDir, "debug-toggle.log")
        logFile.writeText("=== Test ===\n")
        logFileField.set(FileLogger, logFile)

        val threadCount = 4
        val iterations = 200
        val barrier = CyclicBarrier(threadCount)
        val errorCount = AtomicInteger(0)
        val latch = CountDownLatch(threadCount)

        val threads = (0 until threadCount).map { threadIdx ->
            Thread {
                try {
                    barrier.await(5, TimeUnit.SECONDS)
                    for (i in 0 until iterations) {
                        FileLogger.isEnabled = (i % 2 == 0)
                        FileLogger.i("Toggle$threadIdx", "Message $i")
                        FileLogger.clear()
                    }
                } catch (e: Exception) {
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        threads.forEach { it.start() }
        assertTrue("Threads should complete within 10 seconds", latch.await(10, TimeUnit.SECONDS))
        assertEquals(0, errorCount.get())
    }

    private fun assertEquals(expected: Int, actual: Int) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
