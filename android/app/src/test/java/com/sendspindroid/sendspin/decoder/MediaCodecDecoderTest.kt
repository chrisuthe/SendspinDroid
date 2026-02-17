package com.sendspindroid.sendspin.decoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import io.mockk.Ordering
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Unit tests for [MediaCodecDecoder].
 *
 * Uses a concrete test subclass with a mock MediaCodec to verify:
 * - C-05: Input retry under queue pressure (no silent frame drops)
 * - C-06: flush() does not call start() in synchronous mode
 * - C-07: Output drain handles INFO_OUTPUT_FORMAT_CHANGED mid-stream
 */
class MediaCodecDecoderTest {

    private lateinit var mockCodec: MediaCodec
    private lateinit var decoder: TestMediaCodecDecoder

    /**
     * Concrete subclass that exposes the protected mediaCodec field for testing
     * and provides a no-op configureFormat.
     */
    private class TestMediaCodecDecoder : MediaCodecDecoder("audio/opus") {
        fun injectCodec(codec: MediaCodec) {
            mediaCodec = codec
        }

        override fun configureFormat(
            format: MediaFormat,
            sampleRate: Int,
            channels: Int,
            bitDepth: Int,
            codecHeader: ByteArray?
        ) {
            // No-op for tests
        }
    }

    @Before
    fun setUp() {
        // Mock android.util.Log which is unavailable on JVM
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockCodec = mockk(relaxed = true)
        decoder = TestMediaCodecDecoder()
        decoder.injectCodec(mockCodec)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ---- Helper to create a ByteBuffer wrapping test data ----

    private fun testBuffer(size: Int): ByteBuffer {
        val data = ByteArray(size) { it.toByte() }
        return ByteBuffer.wrap(data)
    }

    private fun setupSingleOutputBuffer(pcmSize: Int) {
        val outBuffer = testBuffer(pcmSize)
        every { mockCodec.dequeueOutputBuffer(any(), any()) } returnsMany listOf(
            0,
            MediaCodec.INFO_TRY_AGAIN_LATER
        )
        every { mockCodec.getOutputBuffer(0) } returns outBuffer
    }

    // =========================================================================
    // C-05: Input retry under queue pressure
    // =========================================================================

    @Test
    fun decode_inputBufferAvailable_submitsFrameImmediately() {
        // Input available on first attempt
        every { mockCodec.dequeueInputBuffer(any()) } returns 0
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)

        // No output
        every { mockCodec.dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER

        val input = ByteArray(100) { 0x42 }
        decoder.decode(input)

        verify(exactly = 1) { mockCodec.queueInputBuffer(0, 0, 100, 0, 0) }
    }

    @Test
    fun decode_inputBufferUnavailableThenAvailable_retriesAndSubmits() {
        // First attempt: no buffer. Second attempt: buffer available.
        every { mockCodec.dequeueInputBuffer(any()) } returnsMany listOf(-1, 0)
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)

        // Drain during retry returns nothing
        every { mockCodec.dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER

        val input = ByteArray(50)
        decoder.decode(input)

        // Input was submitted on the second attempt
        verify(exactly = 1) { mockCodec.queueInputBuffer(0, 0, 50, 0, 0) }
        // dequeueInputBuffer called twice (initial fail + retry)
        verify(exactly = 2) { mockCodec.dequeueInputBuffer(any()) }
    }

    @Test
    fun decode_inputBufferNeverAvailable_logsErrorAfterMaxRetries() {
        // All attempts fail
        every { mockCodec.dequeueInputBuffer(any()) } returns -1
        every { mockCodec.dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER

        val input = ByteArray(200)
        decoder.decode(input)

        // Should have tried MAX_INPUT_RETRIES + 1 = 4 times
        verify(exactly = 4) { mockCodec.dequeueInputBuffer(any()) }
        // Frame was not submitted
        verify(exactly = 0) { mockCodec.queueInputBuffer(any(), any(), any(), any(), any()) }
        // Error was logged (not just a warning)
        verify { Log.e(any(), match { it.contains("Failed to submit input") }) }
    }

    @Test
    fun decode_inputRetry_drainsOutputBetweenAttempts() {
        // First two attempts fail, third succeeds
        every { mockCodec.dequeueInputBuffer(any()) } returnsMany listOf(-1, -1, 0)
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)

        // Output drain yields a buffer on first drain call
        val outBuffer = testBuffer(960)
        var drainCallCount = 0
        every { mockCodec.dequeueOutputBuffer(any(), any()) } answers {
            drainCallCount++
            // Return a buffer on the first drain call, then TRY_AGAIN for the rest
            if (drainCallCount == 1) 0
            else MediaCodec.INFO_TRY_AGAIN_LATER
        }
        every { mockCodec.getOutputBuffer(0) } returns outBuffer

        val input = ByteArray(100)
        val result = decoder.decode(input)

        // Input was eventually submitted
        verify(exactly = 1) { mockCodec.queueInputBuffer(0, 0, 100, 0, 0) }
        // Output was collected during the drain-between-retries
        assertTrue("Should have collected PCM output during retry drain", result.isNotEmpty())
    }

    // =========================================================================
    // C-06: flush() does not call start() in synchronous mode
    // =========================================================================

    @Test
    fun flush_doesNotCallStart() {
        decoder.flush()

        verify(exactly = 1) { mockCodec.flush() }
        verify(exactly = 0) { mockCodec.start() }
    }

    @Test
    fun flush_thenDecode_worksWithoutStart() {
        // After flush, decode should work by going directly to dequeueInputBuffer
        every { mockCodec.dequeueInputBuffer(any()) } returns 0
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)
        every { mockCodec.dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER

        decoder.flush()
        decoder.decode(ByteArray(50))

        verify(ordering = Ordering.ORDERED) {
            mockCodec.flush()
            mockCodec.dequeueInputBuffer(any())
        }
        // start() is never called
        verify(exactly = 0) { mockCodec.start() }
    }

    @Test
    fun flush_exceptionIsCaughtAndLogged() {
        every { mockCodec.flush() } throws IllegalStateException("test error")

        // Should not throw
        decoder.flush()

        verify { Log.e(any(), match { it.contains("Error flushing") }, any()) }
    }

    // =========================================================================
    // C-07: Output drain handles FORMAT_CHANGED mid-stream
    // =========================================================================

    @Test
    fun decode_formatChangeMidDrain_continuesDrainingAfterFormatChange() {
        every { mockCodec.dequeueInputBuffer(any()) } returns 0
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)

        // Output sequence: buffer0 -> FORMAT_CHANGED -> buffer1 -> TRY_AGAIN
        val buf0 = testBuffer(480)
        val buf1 = testBuffer(480)
        every { mockCodec.dequeueOutputBuffer(any(), any()) } returnsMany listOf(
            0,
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
            1,
            MediaCodec.INFO_TRY_AGAIN_LATER,
            // Final drain after input submit also gets TRY_AGAIN
            MediaCodec.INFO_TRY_AGAIN_LATER
        )
        every { mockCodec.getOutputBuffer(0) } returns buf0
        every { mockCodec.getOutputBuffer(1) } returns buf1

        val mockFormat = mockk<MediaFormat>()
        every { mockCodec.outputFormat } returns mockFormat

        val input = ByteArray(100)
        val result = decoder.decode(input)

        // Both output buffers should have been collected (480 + 480 = 960 bytes)
        assertEquals(960, result.size)

        // Both buffers were released
        verify { mockCodec.releaseOutputBuffer(0, false) }
        verify { mockCodec.releaseOutputBuffer(1, false) }
    }

    @Test
    fun decode_formatChangeOnly_updatesOutputFormat() {
        every { mockCodec.dequeueInputBuffer(any()) } returns 0
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)

        every { mockCodec.dequeueOutputBuffer(any(), any()) } returnsMany listOf(
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
            MediaCodec.INFO_TRY_AGAIN_LATER,
            MediaCodec.INFO_TRY_AGAIN_LATER
        )

        val mockFormat = mockk<MediaFormat>()
        every { mockCodec.outputFormat } returns mockFormat

        decoder.decode(ByteArray(100))

        verify { Log.d(any(), match { it.contains("Output format changed") }) }
    }

    @Test
    @Suppress("DEPRECATION")
    fun decode_outputBuffersChanged_continuesDraining() {
        every { mockCodec.dequeueInputBuffer(any()) } returns 0
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)

        // Sequence: BUFFERS_CHANGED -> buffer0 -> TRY_AGAIN
        val buf0 = testBuffer(480)
        every { mockCodec.dequeueOutputBuffer(any(), any()) } returnsMany listOf(
            MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED,
            0,
            MediaCodec.INFO_TRY_AGAIN_LATER,
            MediaCodec.INFO_TRY_AGAIN_LATER
        )
        every { mockCodec.getOutputBuffer(0) } returns buf0

        val result = decoder.decode(ByteArray(100))

        // The buffer after BUFFERS_CHANGED was collected
        assertEquals(480, result.size)
        verify { mockCodec.releaseOutputBuffer(0, false) }
    }

    // =========================================================================
    // General decode behavior
    // =========================================================================

    @Test
    fun decode_notConfigured_throwsIllegalState() {
        val unconfigured = TestMediaCodecDecoder()
        // mediaCodec is null (not injected)

        try {
            unconfigured.decode(ByteArray(100))
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("not configured"))
        }
    }

    @Test
    fun decode_emptyOutput_returnsEmptyByteArray() {
        every { mockCodec.dequeueInputBuffer(any()) } returns 0
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)
        every { mockCodec.dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER

        val result = decoder.decode(ByteArray(100))
        assertEquals(0, result.size)
    }
}
