package com.sendspindroid.logging

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogFileWriterShareIntentTest {

    private lateinit var tempDir: File
    private lateinit var writer: LogFileWriter

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        tempDir = File(ctx.cacheDir, "logwriter-share-test-${System.nanoTime()}")
        tempDir.mkdirs()
        writer = LogFileWriter(tempDir, maxFiles = 3, maxBytesPerFile = 1024L)
        writer.init()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `shareIntent returns null when no logs exist and dir is wiped`() {
        tempDir.deleteRecursively()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = writer.shareIntent(ctx)
        assertEquals("should be null when no files", null, intent)
    }

    @Test
    fun `shareIntent returns SEND intent with a single combined URI`() {
        writer.appendLine("line-A")
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = writer.shareIntent(ctx)

        assertNotNull("intent should be non-null when logs exist", intent)
        assertEquals(Intent.ACTION_SEND, intent!!.action)
        assertEquals("text/plain", intent.type)

        val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        assertNotNull("intent should carry a URI", uri)
    }

    @Test
    fun `shareIntent concatenates all files in oldest-to-newest order`() {
        // Force two files by exceeding maxBytesPerFile.
        val chunk = "a".repeat(500)
        writer.appendLine(chunk) // log-0
        writer.appendLine(chunk) // log-0 ~= 1kB, next append triggers rotate
        writer.appendLine("MARKER_NEW") // in new log-0
        // log-1 now contains the older chunks; log-0 contains MARKER_NEW.

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        writer.shareIntent(ctx)

        // Combined file is the concatenation target.
        val combined = File(tempDir, "sendspin-log-combined.txt")
        assertTrue("combined file should exist", combined.exists())
        val text = combined.readText()

        val idxOld = text.indexOf(chunk)
        val idxNew = text.indexOf("MARKER_NEW")
        assertTrue("both markers should be present", idxOld >= 0 && idxNew >= 0)
        assertTrue("older content should appear before newer in combined file", idxOld < idxNew)
    }
}
