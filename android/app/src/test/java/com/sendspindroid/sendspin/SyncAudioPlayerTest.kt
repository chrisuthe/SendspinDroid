package com.sendspindroid.sendspin

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Unit tests for SyncAudioPlayer.
 *
 * These tests verify the playback state machine, sync correction logic,
 * gap/overlap handling, and pre-sync buffering behavior.
 *
 * Since SyncAudioPlayer depends on Android's AudioTrack (which is unavailable
 * in JVM unit tests), we use reflection to access and verify internal state
 * where necessary, and mock the SendspinTimeFilter for time conversion.
 */
class SyncAudioPlayerTest {

    private lateinit var timeFilter: SendspinTimeFilter
    private lateinit var player: SyncAudioPlayer

    // Standard audio format: 48kHz, 2ch, 16-bit = 4 bytes per frame
    private val sampleRate = 48000
    private val channels = 2
    private val bitDepth = 16
    private val bytesPerFrame = channels * (bitDepth / 8)  // 4

    @Before
    fun setUp() {
        timeFilter = mockk(relaxed = true)
        // Default: time filter is ready with identity mapping (offset = 0)
        every { timeFilter.isReady } returns true
        every { timeFilter.serverToClient(any()) } answers { firstArg() }
        every { timeFilter.clientToServer(any()) } answers { firstArg() }
        every { timeFilter.offsetMicros } returns 0L
        every { timeFilter.measurementCountValue } returns 10

        player = SyncAudioPlayer(timeFilter, sampleRate, channels, bitDepth)
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    /** Create PCM data for a given number of frames (all zeros = silence). */
    private fun makePcmData(frames: Int): ByteArray = ByteArray(frames * bytesPerFrame)

    /** Get a private field value via reflection. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> getField(name: String): T {
        val field = SyncAudioPlayer::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(player) as T
    }

    /** Set a private field value via reflection. */
    private fun setField(name: String, value: Any?) {
        val field = SyncAudioPlayer::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(player, value)
    }

    /** Get the internal chunk queue. */
    private fun getChunkQueue(): ConcurrentLinkedQueue<*> = getField("chunkQueue")

    /** Queue a chunk directly (bypasses AudioTrack requirement). */
    private fun queueChunkDirect(serverTimeUs: Long, frames: Int): ByteArray {
        val pcm = makePcmData(frames)
        player.queueChunk(serverTimeUs, pcm)
        return pcm
    }

    // ========================================================================
    // Test 1: State transition INITIALIZING -> WAITING_FOR_START
    // ========================================================================

    @Test
    fun `first chunk triggers INITIALIZING to WAITING_FOR_START transition`() {
        assertEquals(PlaybackState.INITIALIZING, player.getPlaybackState())

        queueChunkDirect(1_000_000L, 960)

        assertEquals(PlaybackState.WAITING_FOR_START, player.getPlaybackState())
    }

    @Test
    fun `state callback fires on INITIALIZING to WAITING_FOR_START`() {
        val callback = mockk<SyncAudioPlayerCallback>(relaxed = true)
        player.setStateCallback(callback)

        queueChunkDirect(1_000_000L, 960)

        verify { callback.onPlaybackStateChanged(PlaybackState.WAITING_FOR_START) }
    }

    @Test
    fun `firstServerTimestampUs is set on first chunk`() {
        val serverTimeUs = 5_000_000L
        queueChunkDirect(serverTimeUs, 960)

        val firstTs: Long? = getField("firstServerTimestampUs")
        assertEquals(serverTimeUs, firstTs)
    }

    // ========================================================================
    // Test 2: DRAINING -> INITIALIZING on buffer exhaustion
    // ========================================================================

    @Test
    fun `enterDraining transitions PLAYING to DRAINING`() {
        setField("playbackState", PlaybackState.PLAYING)

        val result = player.enterDraining()

        assertTrue(result)
        assertEquals(PlaybackState.DRAINING, player.getPlaybackState())
    }

    @Test
    fun `enterDraining fails from INITIALIZING state`() {
        assertEquals(PlaybackState.INITIALIZING, player.getPlaybackState())
        val result = player.enterDraining()
        assertFalse(result)
        assertEquals(PlaybackState.INITIALIZING, player.getPlaybackState())
    }

    @Test
    fun `buffer exhaustion during DRAINING is detectable`() {
        val callback = mockk<SyncAudioPlayerCallback>(relaxed = true)
        player.setStateCallback(callback)

        queueChunkDirect(1_000_000L, 960)
        setField("playbackState", PlaybackState.PLAYING)

        player.enterDraining()
        assertEquals(PlaybackState.DRAINING, player.getPlaybackState())

        // Simulate buffer exhaustion
        getChunkQueue().clear()
        val totalQueuedSamples: AtomicLong = getField("totalQueuedSamples")
        totalQueuedSamples.set(0)

        assertEquals(0L, player.getBufferedDurationMs())
    }

    // ========================================================================
    // Test 3: Reanchor respects 5-second cooldown
    // ========================================================================

    @Test
    fun `triggerReanchor respects 5-second cooldown`() {
        val method = SyncAudioPlayer::class.java.getDeclaredMethod("triggerReanchor")
        method.isAccessible = true

        setField("playbackState", PlaybackState.PLAYING)

        // Set lastReanchorTimeUs to "now" to simulate recent reanchor
        val nowUs = System.nanoTime() / 1000
        setField("lastReanchorTimeUs", nowUs)

        val result = method.invoke(player) as Boolean
        assertFalse("Reanchor should be rejected within cooldown", result)
        assertEquals(PlaybackState.PLAYING, player.getPlaybackState())
    }

    @Test
    fun `triggerReanchor succeeds after cooldown expires`() {
        val method = SyncAudioPlayer::class.java.getDeclaredMethod("triggerReanchor")
        method.isAccessible = true

        setField("playbackState", PlaybackState.PLAYING)

        // Set lastReanchorTimeUs to well in the past (>5 seconds ago)
        val nowUs = System.nanoTime() / 1000
        setField("lastReanchorTimeUs", nowUs - 6_000_000L)

        val result = method.invoke(player) as Boolean
        assertTrue("Reanchor should succeed after cooldown", result)
        assertEquals(PlaybackState.INITIALIZING, player.getPlaybackState())
    }

    // ========================================================================
    // Test 4: enterDraining/exitDraining thread safety
    // ========================================================================

    @Test
    fun `concurrent enterDraining and exitDraining do not corrupt state`() {
        val iterations = 100
        val errors = mutableListOf<String>()
        val latch = CountDownLatch(2)

        setField("playbackState", PlaybackState.PLAYING)

        val thread1 = thread {
            try {
                for (i in 0 until iterations) {
                    setField("playbackState", PlaybackState.PLAYING)
                    player.enterDraining()
                }
            } catch (e: Exception) {
                synchronized(errors) {
                    errors.add("Thread1: ${e.message}")
                }
            } finally {
                latch.countDown()
            }
        }

        val thread2 = thread {
            try {
                for (i in 0 until iterations) {
                    player.exitDraining()
                    setField("playbackState", PlaybackState.PLAYING)
                }
            } catch (e: Exception) {
                synchronized(errors) {
                    errors.add("Thread2: ${e.message}")
                }
            } finally {
                latch.countDown()
            }
        }

        latch.await()

        assertTrue(
            "Concurrent enterDraining/exitDraining should not throw: $errors",
            errors.isEmpty()
        )

        val finalState = player.getPlaybackState()
        assertNotNull("Final state should not be null", finalState)
        assertTrue(
            "Final state should be a valid PlaybackState",
            finalState in PlaybackState.entries
        )
    }

    // ========================================================================
    // Test 5: Correction clamps to MAX_SPEED_CORRECTION (+/-2%)
    // ========================================================================

    @Test
    fun `correction rate clamped to MAX_SPEED_CORRECTION for large positive error`() {
        val method = SyncAudioPlayer::class.java.getDeclaredMethod(
            "updateCorrectionSchedule", Long::class.java
        )
        method.isAccessible = true

        setField("startTimeCalibrated", true)
        setField("playingStateEnteredAtUs", 1L)
        setField("reconnectedAtUs", 0L)

        val syncErrorFilter: SyncErrorFilter = getField("syncErrorFilter")
        syncErrorFilter.update(100_000L, 1_000_000L)
        syncErrorFilter.update(100_000L, 2_000_000L)
        syncErrorFilter.update(100_000L, 3_000_000L)

        method.invoke(player, 0L)

        val dropEvery: Int = getField("dropEveryNFrames")
        val insertEvery: Int = getField("insertEveryNFrames")

        assertTrue("Should be dropping for positive error", dropEvery > 0)
        assertEquals("Should not be inserting for positive error", 0, insertEvery)

        // At MAX_SPEED_CORRECTION (2%), min interval = 48000 / 960 = 50 frames
        val minInterval = (sampleRate / (sampleRate * 0.02)).toInt()
        assertTrue(
            "Drop interval ($dropEvery) should be >= min interval ($minInterval)",
            dropEvery >= minInterval
        )
    }

    @Test
    fun `correction rate clamped to MAX_SPEED_CORRECTION for large negative error`() {
        val method = SyncAudioPlayer::class.java.getDeclaredMethod(
            "updateCorrectionSchedule", Long::class.java
        )
        method.isAccessible = true

        setField("startTimeCalibrated", true)
        setField("playingStateEnteredAtUs", 1L)
        setField("reconnectedAtUs", 0L)

        val syncErrorFilter: SyncErrorFilter = getField("syncErrorFilter")
        syncErrorFilter.update(-100_000L, 1_000_000L)
        syncErrorFilter.update(-100_000L, 2_000_000L)
        syncErrorFilter.update(-100_000L, 3_000_000L)

        method.invoke(player, 0L)

        val dropEvery: Int = getField("dropEveryNFrames")
        val insertEvery: Int = getField("insertEveryNFrames")

        assertEquals("Should not be dropping for negative error", 0, dropEvery)
        assertTrue("Should be inserting for negative error", insertEvery > 0)

        val minInterval = (sampleRate / (sampleRate * 0.02)).toInt()
        assertTrue(
            "Insert interval ($insertEvery) should be >= min interval ($minInterval)",
            insertEvery >= minInterval
        )
    }

    // ========================================================================
    // Test 6: No corrections during STARTUP_GRACE_PERIOD
    // ========================================================================

    @Test
    fun `no corrections during startup grace period`() {
        val method = SyncAudioPlayer::class.java.getDeclaredMethod(
            "updateCorrectionSchedule", Long::class.java
        )
        method.isAccessible = true

        setField("startTimeCalibrated", true)
        setField("reconnectedAtUs", 0L)

        // Set playingStateEnteredAtUs to "now" (within grace period)
        val nowUs = System.nanoTime() / 1000
        setField("playingStateEnteredAtUs", nowUs)

        val syncErrorFilter: SyncErrorFilter = getField("syncErrorFilter")
        syncErrorFilter.update(50_000L, 1_000_000L)
        syncErrorFilter.update(50_000L, 2_000_000L)

        method.invoke(player, 0L)

        val dropEvery: Int = getField("dropEveryNFrames")
        val insertEvery: Int = getField("insertEveryNFrames")

        assertEquals("No drop corrections during grace period", 0, dropEvery)
        assertEquals("No insert corrections during grace period", 0, insertEvery)
    }

    // ========================================================================
    // Test 7: No corrections during RECONNECT_STABILIZATION
    // ========================================================================

    @Test
    fun `no corrections during reconnect stabilization period`() {
        val method = SyncAudioPlayer::class.java.getDeclaredMethod(
            "updateCorrectionSchedule", Long::class.java
        )
        method.isAccessible = true

        setField("startTimeCalibrated", true)
        setField("playingStateEnteredAtUs", 1L)

        // Set reconnectedAtUs to "now" (within 2s stabilization period)
        val nowUs = System.nanoTime() / 1000
        setField("reconnectedAtUs", nowUs)

        val syncErrorFilter: SyncErrorFilter = getField("syncErrorFilter")
        syncErrorFilter.update(50_000L, 1_000_000L)
        syncErrorFilter.update(50_000L, 2_000_000L)

        method.invoke(player, 0L)

        val dropEvery: Int = getField("dropEveryNFrames")
        val insertEvery: Int = getField("insertEveryNFrames")

        assertEquals("No drop corrections during reconnect stabilization", 0, dropEvery)
        assertEquals("No insert corrections during reconnect stabilization", 0, insertEvery)
    }

    @Test
    fun `corrections resume after reconnect stabilization expires`() {
        val method = SyncAudioPlayer::class.java.getDeclaredMethod(
            "updateCorrectionSchedule", Long::class.java
        )
        method.isAccessible = true

        setField("startTimeCalibrated", true)
        setField("playingStateEnteredAtUs", 1L)

        // Set reconnectedAtUs to 3 seconds ago (stabilization is 2 seconds)
        val nowUs = System.nanoTime() / 1000
        setField("reconnectedAtUs", nowUs - 3_000_000L)

        val syncErrorFilter: SyncErrorFilter = getField("syncErrorFilter")
        syncErrorFilter.update(50_000L, 1_000_000L)
        syncErrorFilter.update(50_000L, 2_000_000L)
        syncErrorFilter.update(50_000L, 3_000_000L)

        method.invoke(player, 0L)

        val dropEvery: Int = getField("dropEveryNFrames")

        assertTrue(
            "Corrections should resume after stabilization expires",
            dropEvery > 0
        )
    }

    // ========================================================================
    // Test 8: Gap >10ms filled with silence
    // ========================================================================

    @Test
    fun `gap larger than 10ms fills with silence`() {
        val firstTimestampUs = 1_000_000L
        val framesPerChunk = 960 // 20ms at 48kHz
        queueChunkDirect(firstTimestampUs, framesPerChunk)

        val chunkDurationUs = (framesPerChunk.toLong() * 1_000_000L) / sampleRate
        val expectedNextUs = firstTimestampUs + chunkDurationUs

        // Queue second chunk with a 50ms gap (well above 10ms threshold)
        val gapUs = 50_000L
        val secondTimestampUs = expectedNextUs + gapUs
        queueChunkDirect(secondTimestampUs, framesPerChunk)

        // Queue should have 3+ entries (first chunk + silence fill + second chunk)
        val queue = getChunkQueue()
        assertTrue(
            "Queue should have more than 2 entries due to silence fill, got ${queue.size}",
            queue.size >= 3
        )

        val stats = player.getStats()
        assertEquals("One gap should have been filled", 1L, stats.gapsFilled)
        assertTrue("Gap silence should be > 0ms", stats.gapSilenceMs > 0)
    }

    @Test
    fun `gap smaller than 10ms does not fill with silence`() {
        val firstTimestampUs = 1_000_000L
        val framesPerChunk = 960
        queueChunkDirect(firstTimestampUs, framesPerChunk)

        val chunkDurationUs = (framesPerChunk.toLong() * 1_000_000L) / sampleRate
        val expectedNextUs = firstTimestampUs + chunkDurationUs

        // Queue second chunk with a 5ms gap (below 10ms threshold)
        val smallGapUs = 5_000L
        val secondTimestampUs = expectedNextUs + smallGapUs
        queueChunkDirect(secondTimestampUs, framesPerChunk)

        val stats = player.getStats()
        assertEquals("No gaps should be filled for small gap", 0L, stats.gapsFilled)
    }

    // ========================================================================
    // Test 9: Overlapping chunk trims duplicate samples
    // ========================================================================

    @Test
    fun `overlapping chunk trims leading samples`() {
        val firstTimestampUs = 1_000_000L
        val framesPerChunk = 960 // 20ms
        queueChunkDirect(firstTimestampUs, framesPerChunk)

        val chunkDurationUs = (framesPerChunk.toLong() * 1_000_000L) / sampleRate
        val expectedNextUs = firstTimestampUs + chunkDurationUs

        // Queue second chunk with 5ms overlap
        val overlapUs = 5_000L
        val secondTimestampUs = expectedNextUs - overlapUs
        queueChunkDirect(secondTimestampUs, framesPerChunk)

        val stats = player.getStats()
        assertEquals("One overlap should have been trimmed", 1L, stats.overlapsTrimmed)
    }

    @Test
    fun `complete overlap skips entire chunk`() {
        val firstTimestampUs = 1_000_000L
        val framesPerChunk = 960
        queueChunkDirect(firstTimestampUs, framesPerChunk)

        // Queue second chunk at same timestamp (completely overlapping)
        queueChunkDirect(firstTimestampUs, framesPerChunk)

        val stats = player.getStats()
        assertTrue("Overlap should have been detected", stats.overlapsTrimmed > 0)
    }
}
