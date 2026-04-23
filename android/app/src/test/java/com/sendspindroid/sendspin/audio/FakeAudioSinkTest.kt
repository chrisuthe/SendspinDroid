package com.sendspindroid.sendspin.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FakeAudioSinkTest {

    @Test
    fun `records write calls with byte counts`() {
        val sink = FakeAudioSink()
        val buf = ByteArray(480 * 4)  // 10ms of stereo 16-bit at 48k
        sink.write(buf, 0, buf.size)
        sink.write(buf, 0, buf.size)
        assertEquals(2, sink.writes.size)
        assertEquals((480 * 4 * 2).toLong(), sink.totalBytesWritten.get())
    }

    @Test
    fun `scriptTimestamp returns configured value, null by default`() {
        val sink = FakeAudioSink()
        assertNull(sink.getTimestamp())
        sink.scriptTimestamp(framePosition = 960, nanoTime = 1_000_000)
        val ts = sink.getTimestamp()!!
        assertEquals(960L, ts.framePosition)
        assertEquals(1_000_000L, ts.nanoTime)
    }

    @Test
    fun `play pause stop flush release counted`() {
        val sink = FakeAudioSink()
        sink.play(); sink.pause(); sink.stop(); sink.flush(); sink.release()
        assertEquals(1, sink.playCallCount.get())
        assertEquals(1, sink.pauseCallCount.get())
        assertEquals(1, sink.stopCallCount.get())
        assertEquals(1, sink.flushCallCount.get())
        assertEquals(1, sink.releaseCallCount.get())
    }

    @Test
    fun `write captures snapshot of first bytes for inspection`() {
        val sink = FakeAudioSink()
        val buf = ByteArray(100) { i -> i.toByte() }
        sink.write(buf, offset = 0, size = 100)
        val record = sink.writes.first()
        // First 16 bytes of 0..15 sequence.
        for (i in 0 until 16) {
            assertEquals(i.toByte(), record.snapshotFirstBytes[i])
        }
    }

    @Test
    fun `write returns size written`() {
        val sink = FakeAudioSink()
        val buf = ByteArray(1000)
        val returned = sink.write(buf, offset = 100, size = 500)
        assertEquals(500, returned)
        assertEquals(500L, sink.totalBytesWritten.get())
    }

    @Test
    fun `bufferSizeInBytes customisable`() {
        val sink = FakeAudioSink(bufferSizeInBytes = 4096)
        assertEquals(4096, sink.bufferSizeInBytes)
    }

    @Test
    fun `playbackHeadPosition scriptable`() {
        val sink = FakeAudioSink()
        assertEquals(0, sink.playbackHeadPosition)
        sink.scriptedPlaybackHeadPosition = 48000
        assertEquals(48000, sink.playbackHeadPosition)
    }

    @Test
    fun `playState defaults to PLAYSTATE_PLAYING and is scriptable`() {
        val sink = FakeAudioSink()
        assertEquals(FakeAudioSink.PLAYSTATE_PLAYING, sink.playState)
        sink.scriptedPlayState = 2  // PLAYSTATE_PAUSED
        assertEquals(2, sink.playState)
    }
}
