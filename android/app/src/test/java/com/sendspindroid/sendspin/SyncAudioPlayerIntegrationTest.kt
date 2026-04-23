package com.sendspindroid.sendspin

import com.sendspindroid.sendspin.audio.AudioSink
import com.sendspindroid.sendspin.audio.FakeAudioSink
import com.sendspindroid.sendspin.latency.OutputLatencyEstimator
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
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
}
