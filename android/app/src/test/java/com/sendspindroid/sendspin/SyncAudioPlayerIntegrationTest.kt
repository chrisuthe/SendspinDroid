package com.sendspindroid.sendspin

import com.sendspindroid.sendspin.audio.AudioSink
import com.sendspindroid.sendspin.audio.FakeAudioSink
import com.sendspindroid.sendspin.latency.OutputLatencyEstimator
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration-style regression test for the DAC-aware start-gating
 * tick-starvation deadlock.
 *
 * Scenario covered:
 *   Once `dacTimestampsStable` flips to true, the main playback loop stops
 *   calling `preCalibrateDacTiming()` -- which was the only other path that
 *   ticked [OutputLatencyEstimator]. If the estimator had not yet accepted
 *   enough samples to converge, it could stay in `Status.Measuring`
 *   indefinitely, holding the player in `WAITING_FOR_START` forever (surfaces
 *   on-device as `MediaSession` BUFFERING with a growing chunk queue and no
 *   audio).
 *
 * The fix is a single call to `latencyEstimator.tick()` at the top of
 * `handleStartGatingDacAware()`. This test will fail (the assertion on
 * `Status.TimedOut` after advancing the clock past 2 s) if that call is
 * removed.
 *
 * Because `SyncAudioPlayer.initialize()` calls `AudioTrack.getMinBufferSize`
 * (an Android-only API unavailable in JVM unit tests), we cannot use the
 * normal init path to start the estimator. Instead we start the estimator
 * directly via reflection -- that is fine for this test because we are
 * exercising `handleStartGatingDacAware` in isolation, not the full
 * initialisation sequence.
 */
class SyncAudioPlayerIntegrationTest {

    // Controllable monotonic clock shared between the player and its estimator.
    private var now: Long = 0L
    private val nowNs: () -> Long = { now }

    // Standard audio format: 48kHz, 2ch, 16-bit.
    private val sampleRate = 48_000
    private val channels = 2
    private val bitDepth = 16

    /** Get a private field value via reflection. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> getField(player: SyncAudioPlayer, name: String): T {
        val field = SyncAudioPlayer::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(player) as T
    }

    /** Set a private field value via reflection. */
    private fun setField(player: SyncAudioPlayer, name: String, value: Any?) {
        val field = SyncAudioPlayer::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(player, value)
    }

    /** Invoke the private handleStartGatingDacAware(AudioSink) method. */
    private fun invokeHandleStartGatingDacAware(
        player: SyncAudioPlayer,
        sink: AudioSink,
    ): Boolean {
        val method = SyncAudioPlayer::class.java.getDeclaredMethod(
            "handleStartGatingDacAware",
            AudioSink::class.java,
        )
        method.isAccessible = true
        return method.invoke(player, sink) as Boolean
    }

    @Test
    fun `handleStartGatingDacAware tick fires estimator timeout after 2s`() {
        // Identity time-filter mock -- clientToServer/serverToClient are
        // pass-throughs. We don't rely on any time-filter branch in this
        // test because we expect the "Measuring" early-return to keep us
        // from reaching the alignment math.
        val timeFilter = mockk<SendspinTimeFilter>(relaxed = true)
        every { timeFilter.isReady } returns true
        every { timeFilter.serverToClient(any()) } answers { firstArg() }
        every { timeFilter.clientToServer(any()) } answers { firstArg() }
        every { timeFilter.offsetMicros } returns 0L
        every { timeFilter.measurementCountValue } returns 10

        val fakeSink = FakeAudioSink()

        val player = SyncAudioPlayer(
            timeFilter = timeFilter,
            sampleRate = sampleRate,
            channels = channels,
            bitDepth = bitDepth,
            nowNs = nowNs,
            sinkFactory = { _, _, _, _ -> fakeSink },
        )

        // Reach into the player and put it into the on-device deadlock state:
        //   - audioSink is our FakeAudioSink (handleStartGating's DAC-aware
        //     branch checks audioSink != null && dacTimestampsStable)
        //   - dacTimestampsStable = true
        //   - playbackState = WAITING_FOR_START
        setField(player, "audioSink", fakeSink)
        setField(player, "dacTimestampsStable", true)
        setField(player, "playbackState", PlaybackState.WAITING_FOR_START)

        // The estimator is normally started inside SyncAudioPlayer.initialize()
        // (which calls AudioTrack.getMinBufferSize, unavailable in JVM tests),
        // so start it manually via reflection. It shares `nowNs` with the
        // player by construction (see SyncAudioPlayer init), so advancing
        // `now` advances the estimator's internal clock too.
        val estimator: OutputLatencyEstimator = getField(player, "latencyEstimator")
        estimator.start { /* no-op: we only care about status transitions */ }
        assertEquals(
            "estimator should be Measuring after start()",
            OutputLatencyEstimator.Status.Measuring,
            estimator.status,
        )

        // --- First call: estimator still Measuring, clock well before
        // the 2 s timeout. handleStartGatingDacAware should tick() (harmless,
        // no timeout yet), see Status.Measuring, and return true ("keep
        // waiting"). Status must remain Measuring.
        now = 0L
        val first = invokeHandleStartGatingDacAware(player, fakeSink)
        assertTrue(
            "should keep waiting while estimator is Measuring",
            first,
        )
        assertEquals(
            "status should remain Measuring before timeout",
            OutputLatencyEstimator.Status.Measuring,
            estimator.status,
        )

        // --- Advance the clock past the 2 s estimator timeout.
        // OutputLatencyEstimator.TIMEOUT_NS = 2_000_000_000L.
        now = 2_100_000_000L

        // --- Second call: the tick() at the top of handleStartGatingDacAware
        // should observe the elapsed timeout and transition the estimator to
        // TimedOut. If the tick() call is removed from production code, the
        // estimator will still be Measuring here and the player will report
        // "keep waiting" forever -- this assertion fails the regression test.
        invokeHandleStartGatingDacAware(player, fakeSink)
        assertEquals(
            "tick() in handleStartGatingDacAware must fire estimator timeout",
            OutputLatencyEstimator.Status.TimedOut,
            estimator.status,
        )
    }

    /**
     * Build an AudioChunk with the supplied PCM data via reflection (the
     * data class is private to SyncAudioPlayer).
     */
    private fun makeAudioChunkReflective(pcmData: ByteArray, frames: Int): Any {
        val clazz = Class.forName("com.sendspindroid.sendspin.SyncAudioPlayer\$AudioChunk")
        val ctor = clazz.declaredConstructors.first()
        ctor.isAccessible = true
        return ctor.newInstance(0L, 0L, pcmData, frames)
    }

    private fun invokePlayChunkWithCorrection(player: SyncAudioPlayer, chunk: Any) {
        val clazz = Class.forName("com.sendspindroid.sendspin.SyncAudioPlayer\$AudioChunk")
        val method = SyncAudioPlayer::class.java.getDeclaredMethod(
            "playChunkWithCorrection", clazz,
        )
        method.isAccessible = true
        method.invoke(player, chunk)
    }

    @Test
    fun `setSyncMuted true zero-fills writes through playChunkWithCorrection`() {
        val timeFilter = mockk<SendspinTimeFilter>(relaxed = true)
        every { timeFilter.isReady } returns true
        every { timeFilter.serverToClient(any()) } answers { firstArg() }
        every { timeFilter.clientToServer(any()) } answers { firstArg() }
        every { timeFilter.offsetMicros } returns 0L
        every { timeFilter.measurementCountValue } returns 10

        val fakeSink = FakeAudioSink()
        val player = SyncAudioPlayer(
            timeFilter = timeFilter,
            sampleRate = sampleRate,
            channels = channels,
            bitDepth = bitDepth,
            nowNs = nowNs,
            sinkFactory = { _, _, _, _ -> fakeSink },
        )
        setField(player, "audioSink", fakeSink)
        // initialize() (which builds lastOutputFrame buffers) calls
        // Android-only AudioTrack APIs; supply equivalents directly.
        val bytesPerFrame = channels * (bitDepth / 8)
        setField(player, "lastOutputFrame", ByteArray(bytesPerFrame))
        setField(player, "secondLastOutputFrame", ByteArray(bytesPerFrame))

        val frames = 240
        val pcm = ByteArray(frames * 4) { 0x42 }
        val chunk = makeAudioChunkReflective(pcm, frames)

        player.setSyncMuted(true)
        invokePlayChunkWithCorrection(player, chunk)

        val record = fakeSink.writes.firstOrNull()
            ?: error("expected one write to fake sink")
        assertTrue(
            "muted writes must be zero-filled",
            record.snapshotFirstBytes.all { it == 0.toByte() },
        )
    }

    @Test
    fun `setSyncMuted false leaves PCM bytes untouched in playChunkWithCorrection`() {
        val timeFilter = mockk<SendspinTimeFilter>(relaxed = true)
        every { timeFilter.isReady } returns true
        every { timeFilter.serverToClient(any()) } answers { firstArg() }
        every { timeFilter.clientToServer(any()) } answers { firstArg() }
        every { timeFilter.offsetMicros } returns 0L
        every { timeFilter.measurementCountValue } returns 10

        val fakeSink = FakeAudioSink()
        val player = SyncAudioPlayer(
            timeFilter = timeFilter,
            sampleRate = sampleRate,
            channels = channels,
            bitDepth = bitDepth,
            nowNs = nowNs,
            sinkFactory = { _, _, _, _ -> fakeSink },
        )
        setField(player, "audioSink", fakeSink)
        val bytesPerFrame = channels * (bitDepth / 8)
        setField(player, "lastOutputFrame", ByteArray(bytesPerFrame))
        setField(player, "secondLastOutputFrame", ByteArray(bytesPerFrame))

        val frames = 240
        val pcm = ByteArray(frames * 4) { 0x42 }
        val chunk = makeAudioChunkReflective(pcm, frames)

        invokePlayChunkWithCorrection(player, chunk)

        val record = fakeSink.writes.firstOrNull()
            ?: error("expected one write to fake sink")
        assertTrue(
            "unmuted writes must pass PCM through unchanged",
            record.snapshotFirstBytes.all { it == 0x42.toByte() },
        )
    }

    /**
     * Build a SyncAudioPlayer wired to the shared `nowNs` clock and a
     * relaxed time-filter mock. Used by the watchdog tests.
     */
    private fun newPlayerForWatchdog(): SyncAudioPlayer {
        val timeFilter = mockk<SendspinTimeFilter>(relaxed = true)
        every { timeFilter.isReady } returns true
        every { timeFilter.serverToClient(any()) } answers { firstArg() }
        every { timeFilter.clientToServer(any()) } answers { firstArg() }
        every { timeFilter.offsetMicros } returns 0L
        every { timeFilter.measurementCountValue } returns 10

        val fakeSink = FakeAudioSink()
        return SyncAudioPlayer(
            timeFilter = timeFilter,
            sampleRate = sampleRate,
            channels = channels,
            bitDepth = bitDepth,
            nowNs = nowNs,
            sinkFactory = { _, _, _, _ -> fakeSink },
        )
    }

    /** Invoke the private parameterless checkStuckState() method. */
    private fun invokeCheckStuckState(player: SyncAudioPlayer) {
        val method = SyncAudioPlayer::class.java.getDeclaredMethod("checkStuckState")
        method.isAccessible = true
        method.invoke(player)
    }

    @Test
    fun `watchdog warns when non-PLAYING state persists with chunks arriving`() {
        now = 0L
        val player = newPlayerForWatchdog()

        setField(player, "playbackState", PlaybackState.WAITING_FOR_START)
        // Simulate chunks arriving: set totalQueuedSamples > 0.
        val totalQueuedSamples = getField<AtomicLong>(player, "totalQueuedSamples")
        totalQueuedSamples.set(sampleRate.toLong() * 5)  // 5 seconds of audio

        // First tick: establishes baseline, no warning yet.
        invokeCheckStuckState(player)
        val warn1: Long = getField(player, "lastStuckWarningAtUs")
        assertEquals("no warning on first observation", 0L, warn1)

        // Advance clock past the 5 s stuck threshold.
        now = 6_000_000_000L
        invokeCheckStuckState(player)
        val warn2: Long = getField(player, "lastStuckWarningAtUs")
        assertNotEquals("warning should have fired after 5s stuck", 0L, warn2)
    }

    /**
     * Put a player into the "waiting for alignment" state. Returns the
     * FakeAudioSink so the caller can script timestamps.
     *
     * To reach the startErr > tolerance branch of handleStartGatingDacAware
     * we need:
     *   - audioSink non-null and dacTimestampsStable
     *   - estimator not Measuring (so the status check doesn't early-return)
     *   - pendingToDacUs > 0 (non-zero fakeSink.getTimestamp().framePosition
     *     and totalFramesWritten > framePosition)
     *   - chunkQueue has a head chunk whose serverTimeMicros is far in the
     *     future relative to nowMicros, so startErrUs computes positive
     *     and exceeds START_ALIGN_TOL_US (50 ms)
     */
    private fun setupAlignmentWaitState(player: SyncAudioPlayer): FakeAudioSink {
        // Like the tick-starvation test, SyncAudioPlayer.initialize() is
        // unavailable in JVM tests (AudioTrack.getMinBufferSize is Android-only),
        // so we inject the audioSink via reflection instead of going through
        // the sinkFactory init path.
        val fakeSink = FakeAudioSink()
        setField(player, "audioSink", fakeSink)
        setField(player, "dacTimestampsStable", true)
        setField(player, "playbackState", PlaybackState.WAITING_FOR_START)

        // Estimator: cancel it so status != Measuring. (Same as TimedOut/Converged
        // for the purposes of the status check.)
        val estimator: OutputLatencyEstimator = getField(player, "latencyEstimator")
        estimator.start { }
        estimator.cancel()

        // DAC timestamp: framePosition=1 means DAC has produced 1 frame.
        // totalFramesWritten > 1 so pendingFrames > 0 and pendingToDacUs > 0.
        fakeSink.scriptTimestamp(framePosition = 1L, nanoTime = 0L)
        val totalFramesWritten = getField<AtomicLong>(player, "totalFramesWritten")
        totalFramesWritten.set(sampleRate.toLong())  // 1 s worth so pendingToDacUs > 0

        // Queue a single AudioChunk whose serverTime is 5 s ahead of nowMicros,
        // so startErrUs will be ~5 s (way above the 50 ms tolerance).
        val audioChunkClass = SyncAudioPlayer::class.java.declaredClasses
            .find { it.simpleName == "AudioChunk" }!!
        val ctor = audioChunkClass.getDeclaredConstructor(
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
            ByteArray::class.java, Int::class.javaPrimitiveType,
        )
        ctor.isAccessible = true
        val futureServerTime = (now / 1000) + 5_000_000L
        val chunk = ctor.newInstance(futureServerTime, 0L, ByteArray(0), 0)
        @Suppress("UNCHECKED_CAST")
        val chunkQueue = getField<java.util.Queue<Any>>(player, "chunkQueue")
        chunkQueue.add(chunk)

        return fakeSink
    }

    @Test
    fun `alignment wait log emits entry on first call and not on immediate follow-up`() {
        now = 0L
        val player = newPlayerForWatchdog()
        val sink = setupAlignmentWaitState(player)

        // Initial state: alignment tracking fields are 0.
        assertEquals(0L, getField<Long>(player, "alignmentWaitStartedAtUs"))
        assertEquals(0L, getField<Long>(player, "alignmentWaitLastLoggedUs"))

        // First call: entry log fires; fields take the current clock value.
        now = 100_000_000L  // 100 ms
        invokeHandleStartGatingDacAware(player, sink)
        val startedAfterFirst = getField<Long>(player, "alignmentWaitStartedAtUs")
        val loggedAfterFirst = getField<Long>(player, "alignmentWaitLastLoggedUs")
        assertNotEquals("entry log must set alignmentWaitStartedAtUs", 0L, startedAfterFirst)
        assertEquals("on entry the two fields match", startedAfterFirst, loggedAfterFirst)

        // Immediate follow-up (no clock advance): no new log; fields unchanged.
        invokeHandleStartGatingDacAware(player, sink)
        invokeHandleStartGatingDacAware(player, sink)
        invokeHandleStartGatingDacAware(player, sink)
        assertEquals(
            "follow-up calls within 1 s must not change alignmentWaitStartedAtUs",
            startedAfterFirst, getField<Long>(player, "alignmentWaitStartedAtUs"),
        )
        assertEquals(
            "follow-up calls within 1 s must not change alignmentWaitLastLoggedUs",
            loggedAfterFirst, getField<Long>(player, "alignmentWaitLastLoggedUs"),
        )
    }

    @Test
    fun `alignment wait progress log fires after 1 second while still waiting`() {
        now = 0L
        val player = newPlayerForWatchdog()
        val sink = setupAlignmentWaitState(player)

        // Enter alignment wait at t=0.
        now = 100_000_000L  // 100 ms clock
        invokeHandleStartGatingDacAware(player, sink)
        val startedAt = getField<Long>(player, "alignmentWaitStartedAtUs")
        val loggedAtEntry = getField<Long>(player, "alignmentWaitLastLoggedUs")

        // Advance clock by 1.1 s so the progress-log gate (1 s interval) fires.
        // Re-script chunk so it's still in the future relative to the new clock.
        now = 1_200_000_000L  // 1.2 s
        invokeHandleStartGatingDacAware(player, sink)

        val loggedAfterProgress = getField<Long>(player, "alignmentWaitLastLoggedUs")
        assertNotEquals(
            "progress log must advance alignmentWaitLastLoggedUs after 1 s",
            loggedAtEntry, loggedAfterProgress,
        )
        assertEquals(
            "alignmentWaitStartedAtUs must not change during progress",
            startedAt, getField<Long>(player, "alignmentWaitStartedAtUs"),
        )
    }

    @Test
    fun `watchdog does not warn when non-PLAYING state has no buffered chunks`() {
        now = 0L
        val player = newPlayerForWatchdog()

        setField(player, "playbackState", PlaybackState.WAITING_FOR_START)
        // No buffered audio -- user paused / genuinely idle, not a deadlock.
        val totalQueuedSamples = getField<AtomicLong>(player, "totalQueuedSamples")
        totalQueuedSamples.set(0L)

        // First tick: establishes baseline.
        invokeCheckStuckState(player)

        // Advance past the 5 s threshold; watchdog must STILL stay silent.
        now = 6_000_000_000L
        invokeCheckStuckState(player)
        val warn: Long = getField(player, "lastStuckWarningAtUs")
        assertEquals("no warning when buffer is empty", 0L, warn)
    }
}
