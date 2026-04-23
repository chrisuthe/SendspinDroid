package com.sendspindroid.playback

import com.sendspindroid.playback.FakeAudioDecoder.Companion.makeChunk
import com.sendspindroid.playback.FakeAudioDecoder.Companion.makePcmWithSeq
import com.sendspindroid.playback.FakeAudioDecoder.Companion.readLongBE
import com.sendspindroid.playback.FakeAudioDecoder.Companion.writeLongBE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for [FakeAudioDecoder], the JVM-side test harness that enforces
 * codec-input contiguity. These tests verify the fake itself so that future
 * integration tests can rely on it to detect non-contiguous decode bugs.
 */
class FakeAudioDecoderTest {

    private fun newConfigured(): FakeAudioDecoder {
        val d = FakeAudioDecoder()
        d.configure(sampleRate = 48000, channels = 2, bitDepth = 16, codecHeader = null)
        return d
    }

    @Test
    fun `contiguous sequence decodes cleanly`() {
        val d = newConfigured()
        for (i in 0L..9L) {
            val out = d.decode(makeChunk(i))
            assertNotNull("decode($i) returned null", out)
            assertEquals(
                "output bytes wrong for seq $i",
                FakeAudioDecoder.OUTPUT_PCM_BYTES,
                out.size
            )
            assertEquals(
                "output PCM did not embed seq $i",
                i,
                readLongBE(out, 0)
            )
        }
        assertEquals((0L..9L).toList(), d.observedSequences)
        assertFalse(
            "nonContiguousInputDetected should remain false on clean run",
            d.nonContiguousInputDetected.get()
        )
        assertEquals(10, d.decodeCalls.get())
    }

    @Test
    fun `missing chunk causes error on next decode`() {
        val d = newConfigured()
        d.decode(makeChunk(0L))
        d.decode(makeChunk(1L))

        try {
            d.decode(makeChunk(3L))
            fail("expected IllegalStateException for non-contiguous input (skipped seq 2)")
        } catch (e: IllegalStateException) {
            assertTrue(
                "message should identify non-contiguous input, got: ${e.message}",
                (e.message ?: "").contains("non-contiguous input")
            )
            assertTrue(
                "message should mention expected seq, got: ${e.message}",
                (e.message ?: "").contains("expected 2")
            )
            assertTrue(
                "message should mention received seq, got: ${e.message}",
                (e.message ?: "").contains("got 3")
            )
        }

        assertTrue(
            "nonContiguousInputDetected must be set after the mismatch",
            d.nonContiguousInputDetected.get()
        )
        // The two clean decodes are recorded; the failing one is not.
        assertEquals(listOf(0L, 1L), d.observedSequences)
    }

    @Test
    fun `subsequent decode after error also throws`() {
        val d = newConfigured()
        d.decode(makeChunk(0L))
        d.decode(makeChunk(1L))

        // First non-contiguous decode flips the fake into ERROR state.
        try {
            d.decode(makeChunk(3L))
            fail("expected first non-contiguous decode to throw")
        } catch (e: IllegalStateException) {
            // expected
        }

        // Even a "correct looking" follow-up must throw because the decoder
        // is now stuck in ERROR state until flush().
        try {
            d.decode(makeChunk(4L))
            fail("expected ERROR-state decode to throw")
        } catch (e: IllegalStateException) {
            assertTrue(
                "post-error message should mention ERROR state, got: ${e.message}",
                (e.message ?: "").contains("ERROR state")
            )
        }
    }

    @Test
    fun `flush resets error state and sequence tracking`() {
        val d = newConfigured()
        d.decode(makeChunk(0L))
        d.decode(makeChunk(1L))

        // Drive into ERROR state.
        try {
            d.decode(makeChunk(3L))
            fail("expected non-contiguous decode to throw")
        } catch (e: IllegalStateException) {
            // expected
        }

        d.flush()
        assertEquals("flush should be counted once", 1, d.flushCalls.get())

        // After flush, expected seq resets to 0 and ERROR state clears.
        val out = d.decode(makeChunk(0L))
        assertEquals(
            "flush should allow a fresh seq-0 decode",
            0L,
            readLongBE(out, 0)
        )
    }

    @Test
    fun `release is counted`() {
        val d = FakeAudioDecoder()
        d.release()
        d.release()
        d.release()
        assertEquals(3, d.releaseCalls.get())
    }

    @Test
    fun `output pcm encodes the sequence number`() {
        val pcm = makePcmWithSeq(42L)
        assertEquals(FakeAudioDecoder.OUTPUT_PCM_BYTES, pcm.size)
        assertEquals(42L, readLongBE(pcm, 0))
    }

    @Test
    fun `configure resets sequence tracking`() {
        val d = newConfigured()
        d.decode(makeChunk(0L))
        d.decode(makeChunk(1L))
        d.decode(makeChunk(2L))

        // Reconfigure mid-stream: sequence tracking and observed history reset.
        d.configure(sampleRate = 48000, channels = 2, bitDepth = 16, codecHeader = null)
        assertEquals(
            "configure should clear observedSequences",
            emptyList<Long>(),
            d.observedSequences
        )

        val out = d.decode(makeChunk(0L))
        assertEquals(
            "post-configure decode of seq 0 should succeed",
            0L,
            readLongBE(out, 0)
        )
    }

    @Test
    fun `readLongBE and writeLongBE roundtrip`() {
        val buf = ByteArray(8)
        writeLongBE(buf, 0, 0x0123456789ABCDEFL)
        assertEquals(0x0123456789ABCDEFL, readLongBE(buf, 0))

        writeLongBE(buf, 0, -1L)
        assertEquals(-1L, readLongBE(buf, 0))

        writeLongBE(buf, 0, 0L)
        assertEquals(0L, readLongBE(buf, 0))
    }
}
