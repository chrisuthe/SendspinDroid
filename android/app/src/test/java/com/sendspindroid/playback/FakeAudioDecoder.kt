package com.sendspindroid.playback

import com.sendspindroid.sendspin.decoder.AudioDecoder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Test double for [AudioDecoder] that models a real codec's input-contiguity
 * sensitivity. Real MediaCodec (FLAC especially) transitions to an internal
 * ERROR state if it receives non-contiguous chunks mid-stream; this fake
 * simulates that sensitivity so JVM tests can catch regressions without
 * needing a real codec on an Android device.
 *
 * Contract modeled:
 * - Each input chunk produced by [makeChunk] starts with an 8-byte big-endian
 *   sequence number. [decode] tracks the next expected sequence; a mismatch
 *   flips [nonContiguousInputDetected] and throws.
 * - Once [decode] has thrown due to non-contiguity, every subsequent call
 *   throws "decoder is in ERROR state" until [flush] resets the fake.
 * - [configure] fully resets sequence tracking and error state.
 * - [decode] returns a deterministic 1920-byte PCM output (480 frames,
 *   stereo 16-bit) whose first 8 bytes encode the input sequence number,
 *   so tests can reconstruct the observed output order from
 *   [observedSequences].
 */
class FakeAudioDecoder : AudioDecoder {

    val configureCalls = AtomicInteger(0)
    val flushCalls = AtomicInteger(0)
    val releaseCalls = AtomicInteger(0)
    val decodeCalls = AtomicInteger(0)
    val nonContiguousInputDetected = AtomicBoolean(false)

    private val expectedNextSeq = AtomicLong(0L)
    private val errored = AtomicBoolean(false)
    private val configured = AtomicBoolean(false)
    private val observed = ConcurrentLinkedQueue<Long>()

    /**
     * Sequences that were successfully decoded, in the order they were
     * accepted. Useful for asserting end-to-end pipeline ordering.
     */
    val observedSequences: List<Long>
        get() = observed.toList()

    override fun configure(
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        codecHeader: ByteArray?
    ) {
        configureCalls.incrementAndGet()
        expectedNextSeq.set(0L)
        errored.set(false)
        observed.clear()
        configured.set(true)
    }

    override fun decode(compressedData: ByteArray): ByteArray {
        decodeCalls.incrementAndGet()
        if (errored.get()) {
            throw IllegalStateException("decoder is in ERROR state")
        }
        if (compressedData.size < 8) {
            errored.set(true)
            throw IllegalStateException(
                "decoder is in ERROR state: chunk smaller than 8-byte seq header"
            )
        }
        val seq = readLongBE(compressedData, 0)
        val expected = expectedNextSeq.get()
        if (seq != expected) {
            errored.set(true)
            nonContiguousInputDetected.set(true)
            throw IllegalStateException("non-contiguous input: expected $expected got $seq")
        }
        expectedNextSeq.set(expected + 1L)
        observed.add(seq)
        return makePcmWithSeq(seq)
    }

    override fun flush() {
        flushCalls.incrementAndGet()
        errored.set(false)
        expectedNextSeq.set(0L)
    }

    override fun release() {
        releaseCalls.incrementAndGet()
    }

    override val isConfigured: Boolean
        get() = configured.get()

    companion object {
        /** Output frames per decoded chunk (matches a typical 10ms @ 48kHz frame). */
        const val OUTPUT_FRAMES: Int = 480

        /** Bytes per output frame: stereo 16-bit = 2 channels * 2 bytes. */
        const val OUTPUT_BYTES_PER_FRAME: Int = 4

        /** Total decoded PCM size per chunk, in bytes. */
        const val OUTPUT_PCM_BYTES: Int = OUTPUT_FRAMES * OUTPUT_BYTES_PER_FRAME

        /**
         * Build a compressed-chunk payload tagged with an 8-byte big-endian
         * sequence number followed by [payloadBytes] zero bytes of filler.
         */
        fun makeChunk(seq: Long, payloadBytes: Int = 32): ByteArray {
            require(payloadBytes >= 0) { "payloadBytes must be >= 0" }
            val out = ByteArray(8 + payloadBytes)
            writeLongBE(out, 0, seq)
            return out
        }

        /**
         * Build a deterministic PCM output whose first 8 bytes encode the
         * sequence number. Tests use [readLongBE] on the output to recover
         * the sequence.
         */
        fun makePcmWithSeq(seq: Long): ByteArray {
            val out = ByteArray(OUTPUT_PCM_BYTES)
            writeLongBE(out, 0, seq)
            return out
        }

        /** Read a big-endian int64 from [bytes] starting at [off]. */
        fun readLongBE(bytes: ByteArray, off: Int): Long {
            require(off + 8 <= bytes.size) { "readLongBE: not enough bytes" }
            var v = 0L
            for (i in 0 until 8) {
                v = (v shl 8) or (bytes[off + i].toLong() and 0xFFL)
            }
            return v
        }

        /** Write a big-endian int64 [v] into [bytes] starting at [off]. */
        fun writeLongBE(bytes: ByteArray, off: Int, v: Long) {
            require(off + 8 <= bytes.size) { "writeLongBE: not enough bytes" }
            for (i in 0 until 8) {
                bytes[off + i] = ((v ushr (56 - i * 8)) and 0xFFL).toByte()
            }
        }
    }
}
