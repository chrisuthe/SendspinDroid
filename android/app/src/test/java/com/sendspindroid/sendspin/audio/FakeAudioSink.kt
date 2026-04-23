package com.sendspindroid.sendspin.audio

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Test-only AudioSink that records all calls and returns scripted values.
 *
 * Usage:
 * ```
 * val sink = FakeAudioSink()
 * sink.scriptTimestamp(framePosition = 960, nanoTime = 1_000_000)
 * // ... drive SyncAudioPlayer ...
 * assertEquals(1, sink.playCallCount.get())
 * assertEquals(960 * 4L, sink.totalBytesWritten.get())
 * ```
 */
class FakeAudioSink(
    override val bufferSizeInBytes: Int = DEFAULT_BUFFER_BYTES,
) : AudioSink {

    companion object {
        // 1 second at 48kHz stereo 16-bit = 48000 * 2 * 2 = 192000 bytes.
        const val DEFAULT_BUFFER_BYTES = 192_000
        // AudioTrack.STATE_INITIALIZED = 1 (not 3 -- 3 is STATE_UNINITIALIZED).
        // See android.media.AudioTrack source. We report INITIALIZED so callers
        // that guard on state see a healthy sink.
        const val STATE_INITIALIZED = 1
    }

    val playCallCount = AtomicInteger(0)
    val pauseCallCount = AtomicInteger(0)
    val stopCallCount = AtomicInteger(0)
    val flushCallCount = AtomicInteger(0)
    val releaseCallCount = AtomicInteger(0)

    data class WriteRecord(val offset: Int, val size: Int, val snapshotFirstBytes: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WriteRecord) return false
            return offset == other.offset && size == other.size &&
                snapshotFirstBytes.contentEquals(other.snapshotFirstBytes)
        }
        override fun hashCode(): Int {
            var result = offset
            result = 31 * result + size
            result = 31 * result + snapshotFirstBytes.contentHashCode()
            return result
        }
    }

    private val _writes = ConcurrentLinkedQueue<WriteRecord>()
    val writes: List<WriteRecord> get() = _writes.toList()
    val totalBytesWritten = AtomicLong(0)

    @Volatile private var nextTimestamp: SinkTimestamp? = null

    /** Configure what getTimestamp() returns next. Null means "not ready." */
    fun scriptTimestamp(ts: SinkTimestamp?) {
        nextTimestamp = ts
    }

    fun scriptTimestamp(framePosition: Long, nanoTime: Long) {
        nextTimestamp = SinkTimestamp(framePosition, nanoTime)
    }

    @Volatile var scriptedPlaybackHeadPosition: Int = 0

    override fun play() { playCallCount.incrementAndGet() }
    override fun pause() { pauseCallCount.incrementAndGet() }
    override fun stop() { stopCallCount.incrementAndGet() }
    override fun flush() { flushCallCount.incrementAndGet() }
    override fun release() { releaseCallCount.incrementAndGet() }

    override fun write(buffer: ByteArray, offset: Int, size: Int): Int {
        val snapshotEnd = minOf(offset + 16, offset + size)
        _writes.add(WriteRecord(
            offset = offset,
            size = size,
            snapshotFirstBytes = buffer.copyOfRange(offset, snapshotEnd),
        ))
        totalBytesWritten.addAndGet(size.toLong())
        return size
    }

    override fun getTimestamp(): SinkTimestamp? = nextTimestamp

    override val playbackHeadPosition: Int
        get() = scriptedPlaybackHeadPosition

    override val state: Int = STATE_INITIALIZED
}
