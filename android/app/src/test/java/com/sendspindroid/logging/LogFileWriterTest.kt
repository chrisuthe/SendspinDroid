package com.sendspindroid.logging

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class LogFileWriterTest {

    private lateinit var tempDir: File
    private lateinit var writer: LogFileWriter

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "logwriter-test-${System.nanoTime()}")
        tempDir.mkdirs()
        writer = LogFileWriter(tempDir, maxFiles = 3, maxBytesPerFile = 1024L)
        writer.init()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `init creates log-0 with header`() {
        val log0 = File(tempDir, "sendspin-log-0.txt")
        assertTrue("log-0 should exist after init", log0.exists())
        assertTrue("log-0 should contain a header line", log0.readText().contains("==="))
    }

    @Test
    fun `appendLine writes to log-0 below threshold`() {
        writer.appendLine("hello world")
        val content = File(tempDir, "sendspin-log-0.txt").readText()
        assertTrue("log-0 should contain appended line", content.contains("hello world"))
    }

    @Test
    fun `rotation triggers when file exceeds maxBytes`() {
        val line = "x".repeat(200)
        repeat(10) { writer.appendLine(line) }

        val log0 = File(tempDir, "sendspin-log-0.txt")
        val log1 = File(tempDir, "sendspin-log-1.txt")

        assertTrue("log-1 should exist after rotation", log1.exists())
        assertTrue("log-0 should be under threshold after rotation", log0.length() < 1024L)
    }

    @Test
    fun `rotation respects maxFiles cap`() {
        val line = "x".repeat(500)
        repeat(30) { writer.appendLine(line) }

        val allFiles = (0..5).map { File(tempDir, "sendspin-log-$it.txt") }
        val existing = allFiles.count { it.exists() }
        assertEquals("exactly maxFiles should exist", 3, existing)
        assertFalse("log-3 must not exist beyond cap", File(tempDir, "sendspin-log-3.txt").exists())
    }

    @Test
    fun `rotation preserves line atomicity`() {
        val near = "y".repeat(900)
        writer.appendLine(near)
        val bigLine = "z".repeat(400)
        writer.appendLine(bigLine)

        val log0 = File(tempDir, "sendspin-log-0.txt").readText()
        val log1 = File(tempDir, "sendspin-log-1.txt").readText()

        val log0HasBig = log0.contains(bigLine)
        val log1HasBig = log1.contains(bigLine)
        assertTrue("big line should appear in exactly one file",
            log0HasBig.xor(log1HasBig))
    }

    @Test
    fun `clear removes all rotated files and resets log-0`() {
        val line = "x".repeat(500)
        repeat(10) { writer.appendLine(line) }
        assertTrue("log-1 should exist before clear", File(tempDir, "sendspin-log-1.txt").exists())

        writer.clear()

        assertFalse("log-1 should be removed", File(tempDir, "sendspin-log-1.txt").exists())
        val log0 = File(tempDir, "sendspin-log-0.txt")
        assertTrue("log-0 should be recreated", log0.exists())
        assertTrue("log-0 should contain a fresh header", log0.readText().contains("==="))
    }

    @Test
    fun `currentFiles returns oldest to newest`() {
        val line = "x".repeat(500)
        repeat(10) { writer.appendLine(line) }

        val files = writer.currentFiles()
        assertTrue("should have at least two files", files.size >= 2)
        val names = files.map { it.name }
        val expectedOrder = names.sortedByDescending { it.substringAfter("sendspin-log-").substringBefore(".txt").toInt() }
        assertEquals("files should be oldest->newest (highest index first)", expectedOrder, names)
    }
}

private fun Boolean.xor(other: Boolean): Boolean = this != other
