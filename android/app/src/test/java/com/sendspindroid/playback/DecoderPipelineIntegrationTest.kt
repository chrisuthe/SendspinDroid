package com.sendspindroid.playback

import android.util.Log
import com.sendspindroid.playback.FakeAudioDecoder.Companion.makeChunk
import com.sendspindroid.playback.FakeAudioDecoder.Companion.readLongBE
import com.sendspindroid.sendspin.decoder.AudioDecoder
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Integration tests for PlaybackService's decoder pipeline: onStreamStart ->
 * onAudioChunk* -> {onStreamClear | onStreamEnd}. These tests assert two
 * behavior-level invariants the pipeline MUST preserve through any refactor:
 *
 *  (A) Input contiguity -- the decoder sees chunks in strict sequential order,
 *      with zero drops. [FakeAudioDecoder] enforces this and throws on
 *      violation, modeling real MediaCodec (FLAC) sensitivity.
 *
 *  (B) Output completeness -- every chunk the pipeline accepts eventually
 *      yields a PCM output delivered to [SyncAudioPlayer.queueChunk].
 *
 * These invariants are stable across the codec-safe decoder redesign (Tasks
 * 3-4 move the decoder onto a dedicated coroutine with a bounded channel).
 * They also capture the exact failure mode of PR #142 (drop-oldest on channel
 * full), which would flunk test 1 with a non-contiguous-input throw.
 *
 * PlaybackService itself is an Android Service that cannot be instantiated in
 * a JVM unit test. Following the pattern in [StreamStartDecoderPipelineTest]
 * and [DecoderSetupTest], we reproduce the relevant callback bodies in a
 * small [DecoderPipelineSimulator] that mirrors PlaybackService's decoder
 * pipeline and [SyncAudioPlayer.queueChunk] seam. The FakeAudioDecoder is
 * injected via `mockkObject(AudioDecoderFactory)` so `create()` returns it,
 * matching the production code path exercised by onStreamStart.
 *
 * When the channel-based refactor lands in Task 4, the simulator's internal
 * wiring will be updated to mirror the new flow. The assertions in this file
 * should not change.
 */
class DecoderPipelineIntegrationTest {

    private lateinit var fakeDecoder: FakeAudioDecoder
    private lateinit var simulator: DecoderPipelineSimulator

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        fakeDecoder = FakeAudioDecoder()

        // Inject the FakeAudioDecoder via AudioDecoderFactory. The simulator
        // calls AudioDecoderFactory.create(codec) during onStreamStart, mirroring
        // PlaybackService. This is the same seam used by DecoderSetupTest and
        // StreamStartDecoderPipelineTest.
        mockkObject(AudioDecoderFactory)
        every { AudioDecoderFactory.create(any<String>()) } returns fakeDecoder

        simulator = DecoderPipelineSimulator()
    }

    @After
    fun tearDown() {
        simulator.shutdown()
        unmockkAll()
    }

    // =========================================================================
    // Test 1: 200-chunk burst at stream start preserves codec contiguity.
    // This is the regression test that would have failed against PR #142
    // (drop-oldest policy on channel full would skip sequence numbers and the
    // FakeAudioDecoder would throw, flipping nonContiguousInputDetected).
    // =========================================================================

    @Test
    fun `burst of 200 chunks at stream start preserves codec contiguity`() {
        simulator.onStreamStart(
            codec = "flac",
            sampleRate = 48000,
            channels = 2,
            bitDepth = 16,
            codecHeader = ByteArray(42)
        )

        // Tight burst, simulating the 2-second pre-buffered chunk burst that
        // killed PR #142. Sequence numbers 0..199 in order.
        for (i in 0 until 200) {
            simulator.onAudioChunk(
                serverTimeMicros = i * 10_000L,
                audioData = makeChunk(i.toLong())
            )
        }

        // Drain any asynchronous processing. The current (pre-refactor) code is
        // synchronous so this is a no-op; after Task 4 it will block until the
        // decode channel is empty.
        simulator.waitForIdle()

        assertFalse(
            "decoder must not have seen non-contiguous input",
            fakeDecoder.nonContiguousInputDetected.get()
        )
        assertEquals(
            "all 200 chunks must have been decoded",
            200,
            fakeDecoder.decodeCalls.get()
        )
        assertEquals(
            "decoder should have observed sequences 0..199 in order",
            (0L until 200L).toList(),
            fakeDecoder.observedSequences
        )
        assertEquals(
            "SyncAudioPlayer should have received 200 PCM outputs",
            200,
            simulator.queuedChunks.size
        )
        // Also assert the queued PCM payloads carry the original sequence
        // numbers in order. This catches reorderings that would still pass
        // the decoder contiguity check (e.g., a future refactor that
        // parallelizes decode).
        val queuedSeqs = simulator.queuedChunks.map { readLongBE(it.pcm, 0) }
        assertEquals(
            "queued PCM sequences must be 0..199 in order",
            (0L until 200L).toList(),
            queuedSeqs
        )
    }

    // =========================================================================
    // Test 2: onStreamStart -> chunks -> onStreamEnd does NOT release the
    // decoder. The real PlaybackService.onStreamEnd (PlaybackService.kt line
    // 1394) only enters idle mode on the SyncAudioPlayer; decoder release
    // happens later, either on the next onStreamStart (which releases the
    // prior decoder before creating a new one) or on onDestroy. This test
    // locks that behavior so the simulator cannot silently diverge from the
    // production decoder lifecycle.
    // =========================================================================

    @Test
    fun `stream start then chunks then stream end does not release the decoder`() {
        simulator.onStreamStart(
            codec = "flac",
            sampleRate = 48000,
            channels = 2,
            bitDepth = 16,
            codecHeader = ByteArray(42)
        )

        for (i in 0 until 10) {
            simulator.onAudioChunk(
                serverTimeMicros = i * 10_000L,
                audioData = makeChunk(i.toLong())
            )
        }
        simulator.waitForIdle()

        simulator.onStreamEnd()
        simulator.waitForIdle()

        assertFalse(
            "decoder must not have seen non-contiguous input",
            fakeDecoder.nonContiguousInputDetected.get()
        )
        assertEquals(
            "all 10 chunks must have been decoded before onStreamEnd",
            10,
            fakeDecoder.decodeCalls.get()
        )
        assertEquals(
            "onStreamEnd must NOT release the decoder (see PlaybackService.kt line 1394)",
            0,
            fakeDecoder.releaseCalls.get()
        )
        assertEquals(
            "SyncAudioPlayer should have received all 10 PCM outputs",
            10,
            simulator.queuedChunks.size
        )
    }

    // =========================================================================
    // Test 3: onStreamClear flushes the decoder without losing chunks submitted
    // before the flush. FakeAudioDecoder's flush resets the expected-next-seq
    // to 0, matching the realistic protocol where stream/clear discards
    // buffered audio and fresh audio begins from a new anchor.
    // =========================================================================

    @Test
    fun `stream clear flushes decoder without losing chunks submitted before the flush`() {
        simulator.onStreamStart(
            codec = "flac",
            sampleRate = 48000,
            channels = 2,
            bitDepth = 16,
            codecHeader = ByteArray(42)
        )

        for (i in 0 until 5) {
            simulator.onAudioChunk(
                serverTimeMicros = i * 10_000L,
                audioData = makeChunk(i.toLong())
            )
        }
        simulator.waitForIdle()

        simulator.onStreamClear()
        simulator.waitForIdle()

        // Fresh run of sequences 0..4. Flush reset expectedNextSeq to 0 so
        // the fake accepts these without flipping into ERROR state.
        for (i in 0 until 5) {
            simulator.onAudioChunk(
                serverTimeMicros = (100 + i) * 10_000L,
                audioData = makeChunk(i.toLong())
            )
        }
        simulator.waitForIdle()

        assertFalse(
            "decoder must not have seen non-contiguous input across flush",
            fakeDecoder.nonContiguousInputDetected.get()
        )
        assertEquals(
            "flush should have been called exactly once",
            1,
            fakeDecoder.flushCalls.get()
        )
        assertEquals(
            "all 10 chunks (5 before flush + 5 after) must have been decoded",
            10,
            fakeDecoder.decodeCalls.get()
        )
        assertEquals(
            "observed sequences should be 0..4 then 0..4 (flush resets seq)",
            (0L until 5L).toList() + (0L until 5L).toList(),
            fakeDecoder.observedSequences
        )
        assertEquals(
            "SyncAudioPlayer should have received all 10 PCM outputs",
            10,
            simulator.queuedChunks.size
        )
    }

    // =========================================================================
    // Test 4: a second onStreamStart releases the prior decoder and recreates
    // cleanly. This is the real lifecycle that PR #142 was worried about and
    // is what actually exercises the decoder-recreate path (onStreamEnd does
    // NOT release -- see test 2 -- so the only point where the previous
    // decoder is torn down is the next onStreamStart, per PlaybackService.kt
    // around line 1313).
    //
    // Note on AudioDecoderFactory mockk behavior: setUp() uses
    //   every { AudioDecoderFactory.create(any<String>()) } returns fakeDecoder
    // which returns the SAME FakeAudioDecoder instance on every call. So the
    // single fake sees two configure() calls and one release() call across
    // the two stream starts. FakeAudioDecoder.configure() resets
    // expectedNextSeq/errored/observed/configured but does NOT reset the
    // accumulating call counters (configureCalls, releaseCalls, decodeCalls),
    // which is why decodeCalls ends at 10 while observedSequences only
    // contains the 5 sequences seen after the second configure.
    // =========================================================================

    @Test
    fun `second stream start releases the prior decoder and recreates cleanly`() {
        simulator.onStreamStart(
            codec = "flac",
            sampleRate = 48000,
            channels = 2,
            bitDepth = 16,
            codecHeader = ByteArray(42)
        )

        for (i in 0 until 5) {
            simulator.onAudioChunk(
                serverTimeMicros = i * 10_000L,
                audioData = makeChunk(i.toLong())
            )
        }
        simulator.waitForIdle()

        // Second stream start. The simulator mirrors PlaybackService here:
        // release the prior decoder, then create+configure a new one. Because
        // the mockk factory returns the same fake instance, configure() on
        // that fake resets expectedNextSeq to 0 so sequences restart from 0.
        simulator.onStreamStart(
            codec = "flac",
            sampleRate = 48000,
            channels = 2,
            bitDepth = 16,
            codecHeader = ByteArray(42)
        )

        for (i in 0 until 5) {
            simulator.onAudioChunk(
                serverTimeMicros = (100 + i) * 10_000L,
                audioData = makeChunk(i.toLong())
            )
        }
        simulator.waitForIdle()

        assertFalse(
            "decoder must not have seen non-contiguous input across recreate",
            fakeDecoder.nonContiguousInputDetected.get()
        )
        assertEquals(
            "configure should have been called twice (once per stream start)",
            2,
            fakeDecoder.configureCalls.get()
        )
        assertEquals(
            "prior decoder must be released exactly once on the second stream start",
            1,
            fakeDecoder.releaseCalls.get()
        )
        assertEquals(
            "all 10 chunks (5 before recreate + 5 after) must have been decoded",
            10,
            fakeDecoder.decodeCalls.get()
        )
        // FakeAudioDecoder.configure() clears its observed queue, so only the
        // post-recreate sequences remain visible via observedSequences.
        assertEquals(
            "observedSequences should reflect only the post-recreate run (0..4)",
            (0L until 5L).toList(),
            fakeDecoder.observedSequences
        )
        assertEquals(
            "SyncAudioPlayer should have received all 10 PCM outputs",
            10,
            simulator.queuedChunks.size
        )
    }

    // =========================================================================
    // Test 5: stress test -- a burst larger than the production channel
    // capacity (1000 slots) preserves contiguity and delivers every chunk.
    //
    // In production this would exercise the channel-capacity boundary and
    // trigger producer-side suspend on the last ~200 sends. In the
    // synchronous simulator there is no channel, so this is a stronger
    // version of test 1: the logical invariant is zero loss + zero
    // corruption at any burst size, regardless of queueing strategy.
    // =========================================================================

    @Test
    fun `burst of 1200 chunks submitted in rapid succession preserves contiguity`() {
        simulator.onStreamStart(
            codec = "flac",
            sampleRate = 48000,
            channels = 2,
            bitDepth = 16,
            codecHeader = ByteArray(42)
        )

        // Submit 1200 chunks. In production this would exercise the channel
        // capacity boundary (1000 slots) and trigger producer-side suspend
        // on the last ~200 sends. In the simulator it's just a big sequence.
        val chunkCount = 1200
        for (i in 0 until chunkCount) {
            simulator.onAudioChunk(
                serverTimeMicros = i * 10_000L,
                audioData = makeChunk(i.toLong())
            )
        }
        simulator.waitForIdle()

        // No drops, no corruption, all in order.
        assertFalse(
            "decoder must not see non-contiguous input",
            fakeDecoder.nonContiguousInputDetected.get()
        )
        assertEquals(
            "all chunks must have been decoded",
            chunkCount,
            fakeDecoder.decodeCalls.get()
        )
        assertEquals(
            "all PCM outputs delivered to queueChunk",
            chunkCount,
            simulator.queuedChunks.size
        )
        // Verify sequence ordering in the queueChunk output stream.
        simulator.queuedChunks.toList().forEachIndexed { idx, chunk ->
            val recoveredSeq = readLongBE(chunk.pcm, 0)
            assertEquals(
                "chunk at index $idx must carry seq $idx",
                idx.toLong(),
                recoveredSeq
            )
        }
    }

    // =========================================================================
    // Test 6: onDestroy releases the decoder exactly once, after pending
    // chunks have been processed. Documents the teardown contract added in
    // Task 4 (real PlaybackService sends a final Release through the channel
    // and closes it). In the synchronous simulator onDestroy just releases
    // the captured decoder reference, which is the corresponding behavioral
    // assertion.
    // =========================================================================

    @Test
    fun `onDestroy releases decoder after draining pending chunks`() {
        simulator.onStreamStart(
            codec = "flac",
            sampleRate = 48000,
            channels = 2,
            bitDepth = 16,
            codecHeader = ByteArray(42)
        )

        // Submit a modest batch.
        for (i in 0 until 50) {
            simulator.onAudioChunk(
                serverTimeMicros = i * 10_000L,
                audioData = makeChunk(i.toLong())
            )
        }
        simulator.waitForIdle()

        // Before teardown: 50 chunks decoded, no release yet.
        assertEquals(
            "50 chunks should have been decoded",
            50,
            fakeDecoder.decodeCalls.get()
        )
        assertEquals(
            "decoder should not be released mid-session",
            0,
            fakeDecoder.releaseCalls.get()
        )

        // Teardown: simulator's onDestroy mirrors the real PlaybackService
        // path -- sending a Release and letting the worker process it.
        simulator.onDestroy()

        assertEquals(
            "decoder must be released exactly once on teardown",
            1,
            fakeDecoder.releaseCalls.get()
        )
        assertFalse(
            "no contiguity violations during teardown",
            fakeDecoder.nonContiguousInputDetected.get()
        )
    }

    // =========================================================================
    // Test helper: captured queueChunk call.
    // =========================================================================

    private data class QueuedChunk(val serverTimeMicros: Long, val pcm: ByteArray)

    /**
     * Mirrors the decoder-pipeline portion of PlaybackService's
     * SendSpinClientCallback: onStreamStart / onAudioChunk / onStreamClear /
     * onStreamEnd. Captures queueChunk calls in [queuedChunks].
     *
     * This simulator models the CURRENT (pre-refactor) synchronous flow where:
     *   - onStreamStart releases any prior decoder, then creates+configures
     *     a fresh one
     *   - onAudioChunk decodes on the calling thread and forwards PCM to
     *     "queueChunk" (captured here as list insertion)
     *   - onStreamClear flushes the decoder
     *   - onStreamEnd does NOT touch the decoder (mirroring
     *     PlaybackService.kt line 1394); release only happens on the next
     *     onStreamStart or on shutdown
     *
     * After the Task 4 refactor the decoder work moves onto a dedicated
     * coroutine with a bounded channel. The simulator body will be updated
     * then, but the asserted invariants (contiguity + completeness) hold
     * across both designs.
     */
    private inner class DecoderPipelineSimulator {
        val queuedChunks: ConcurrentLinkedQueue<QueuedChunk> = ConcurrentLinkedQueue()

        private var audioDecoder: AudioDecoder? = null
        @Volatile
        private var decoderReady: Boolean = false
        private var currentCodec: String = ""

        fun onStreamStart(
            codec: String,
            sampleRate: Int,
            channels: Int,
            bitDepth: Int,
            codecHeader: ByteArray?
        ) {
            // Mirrors PlaybackService.onStreamStart decoder-setup portion.
            decoderReady = false
            currentCodec = codec
            audioDecoder?.release()
            audioDecoder = null
            try {
                audioDecoder = AudioDecoderFactory.create(codec)
                audioDecoder?.configure(sampleRate, channels, bitDepth, codecHeader)
                decoderReady = true
            } catch (e: Exception) {
                try {
                    val fallback = AudioDecoderFactory.create("pcm")
                    fallback.configure(sampleRate, channels, bitDepth)
                    audioDecoder = fallback
                    decoderReady = true
                } catch (fallbackEx: Exception) {
                    audioDecoder = null
                }
            }
        }

        fun onAudioChunk(serverTimeMicros: Long, audioData: ByteArray) {
            // Mirrors PlaybackService.onAudioChunk: drop-gate on !decoderReady,
            // capture local decoder ref, decode, forward PCM to the player.
            if (!decoderReady) return
            val decoder = audioDecoder
            val pcmData: ByteArray? = try {
                when {
                    decoder != null -> decoder.decode(audioData)
                    currentCodec == "pcm" -> audioData
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
            if (pcmData != null) {
                queuedChunks.add(QueuedChunk(serverTimeMicros, pcmData))
            }
        }

        fun onStreamClear() {
            // Mirrors PlaybackService.onStreamClear decoder-flush portion.
            audioDecoder?.flush()
        }

        fun onStreamEnd() {
            // Mirrors PlaybackService.onStreamEnd (PlaybackService.kt line
            // 1394): it enters idle mode on the SyncAudioPlayer and does NOT
            // touch the decoder. Decoder release happens on the next
            // onStreamStart (which releases the prior decoder before creating
            // a new one) or on onDestroy. The simulator has no SyncAudioPlayer
            // to idle, so this handler is intentionally empty.
        }

        /**
         * Mirrors the decoder-release portion of PlaybackService.onDestroy.
         * In the real service this is done via decodeChannel.send(Release)
         * followed by channel.close() and a bounded join on the decode
         * worker -- handleDecodeRelease is what actually calls
         * audioDecoder?.release(). The simulator is synchronous and has no
         * worker, so it releases the captured decoder directly. We also
         * clear decoderReady so any post-destroy onAudioChunk calls would
         * be gated off, matching production's behavior once the channel
         * is closed.
         */
        fun onDestroy() {
            decoderReady = false
            audioDecoder?.release()
            audioDecoder = null
        }

        /**
         * Wait until all submitted work has been processed. The current
         * simulator is synchronous; this is a no-op. After Task 4 this should
         * block until the decode channel is empty and the decode coroutine is
         * idle.
         */
        fun waitForIdle() {
            // Synchronous pipeline: nothing to drain.
        }

        fun shutdown() {
            audioDecoder?.release()
            audioDecoder = null
        }
    }
}
